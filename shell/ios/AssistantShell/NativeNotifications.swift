import CryptoKit
import Foundation
import UIKit
import UserNotifications

struct NativeSession: Equatable {
    let instanceID: String
    let userID: String
    let deviceID: String
    let expires: Date
    let previews: Bool
    static func parse(_ payload: [String: Any], connection: AssistantConnection, now: Date = Date()) throws -> NativeSession {
        guard let instance = payload["instance_id"] as? String, instance == connection.configuration.instance_id,
              let user = payload["user_id"] as? String, PushCrypto.validIdentifier(user),
              let device = payload["device_id"] as? String, PushCrypto.validIdentifier(device),
              let expiry = payload["expires_at"] as? String, let expires = NativeSchedule.date(expiry), expires > now,
              payload["notification_previews"] == nil || BridgeProtocol.boolean(payload["notification_previews"]) != nil
        else { throw NativeError.code("invalid_session") }
        return NativeSession(instanceID: instance, userID: user, deviceID: device, expires: expires,
                             previews: BridgeProtocol.boolean(payload["notification_previews"]) ?? false)
    }
    func binding(id: String, origin: String) -> ConnectionBinding {
        ConnectionBinding(bindingID: id, instanceID: instanceID, origin: origin, userID: userID,
                          deviceID: deviceID, previews: previews, expiresAt: expires)
    }
}

struct NativeSchedule {
    struct Item {
        let key: String
        let revision: Int
        let due: Date
        let title: String
        let body: String
        let link: String
    }
    let snapshotID: String
    let serverNow: Date
    let expires: Date
    let items: [Item]

    static func date(_ value: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: value) { return date }
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: value)
    }

    static func parse(_ payload: [String: Any], session: NativeSession) throws -> NativeSchedule {
        guard let snapshotID = payload["snapshot_id"] as? String, UUID(uuidString: snapshotID) != nil,
              let serverNowString = payload["server_now"] as? String, let serverNow = date(serverNowString),
              let expiresString = payload["expires_at"] as? String, let expires = date(expiresString),
              expires > serverNow, expires <= session.expires,
              let raw = payload["items"] as? [[String: Any]], raw.count <= 60
        else { throw NativeError.code("invalid_schedule") }
        var seen = Set<String>()
        let items: [Item] = try raw.map { item in
            guard let key = item["key"] as? String, !key.isEmpty, key.utf8.count <= 256,
                  !key.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }),
                  seen.insert(key).inserted,
                  let revision = BridgeProtocol.integer(item["revision"]), revision > 0,
                  let dueString = item["due_at"] as? String, let due = date(dueString), due > serverNow, due <= expires,
                  let title = item["title"] as? String, !title.isEmpty, title.count <= 120,
                  let body = item["body"] as? String, body.count <= 1_000,
                  let rawLink = item["link"] as? String, let link = PushCrypto.safeRelativeLink(rawLink)
            else { throw NativeError.code("invalid_schedule") }
            return Item(key: key, revision: revision, due: due, title: title, body: body, link: link)
        }
        return NativeSchedule(snapshotID: snapshotID, serverNow: serverNow, expires: expires, items: items)
    }
}

@MainActor final class NativeNotifications: NSObject, ObservableObject, UNUserNotificationCenterDelegate {
    static let shared = NativeNotifications()
    private let center = UNUserNotificationCenter.current()
    private let relay = RelayClient()
    private let fcm = FirebasePushClient()
    private var connection: AssistantConnection?
    private var session: NativeSession?
    private var generation = UUID()
    private var relayBinding: RelayBinding?
    private var token: String?
    private var apnsData: Data?
    private var tokenWaiters: [UUID: CheckedContinuation<String, Error>] = [:]
    private var registering: Task<[String: Any], Error>?
    private var scheduleBusy = false
    private var pendingOpen: [String: Any]?
    private var webReady = false
    var eventSink: ((String, [String: Any]) -> Void)?
    var openConnection: (() -> Void)?
    private var enabled = false
    var pushAvailable: Bool { relay.available && (try? PushTransportConfiguration.load()) != nil }
    func accepts(connectionID: String) -> Bool { connection?.id == connectionID }

    override private init() {
        super.init()
        center.delegate = self
    }

    func attach(_ value: AssistantConnection) {
        connection = value
        generation = UUID()
        session = nil
        webReady = false
        eventSink = nil
        relayBinding = nil
        if let data = try? NativeSecretStore.read(NativeSecretStore.pushAccount("binding")),
           let record = try? JSONDecoder().decode(RelayBinding.self, from: data) { relayBinding = record }
        if let saved = try? SharedPushStore.activeBinding(),
           !Self.receiverMatches(saved.bindingID, connectionID: value.id, relayBindingID: relayBinding?.binding_id) {
            // Synchronous under this newly attached generation: a late old registration
            // cannot delete a receiver created after an await. Never reuse the prior
            // transport's decryption key or reminders when upgrading an APNs installation.
            try? SharedPushStore.delete(bindingID: saved.bindingID)
            try? SharedPushStore.deleteBinding()
            center.removeAllPendingNotificationRequests()
            center.removeAllDeliveredNotifications()
            UserDefaults.standard.removeObject(forKey: "assistant.notifications.enabled")
        }
        if let saved = try? SharedPushStore.activeBinding(), saved.origin == value.address,
           saved.instanceID == value.configuration.instance_id, saved.hasActiveSession(),
           let user = saved.userID, let device = saved.deviceID, let expires = saved.expiresAt {
            session = NativeSession(instanceID: saved.instanceID, userID: user, deviceID: device,
                                    expires: expires, previews: saved.previews)
            enabled = UserDefaults.standard.bool(forKey: "assistant.notifications.enabled")
        } else { enabled = false }
    }

    func detach() async {
        // SwiftUI dismantles its WebView later. Revoke the bridge's authority immediately,
        // before cookie erasure yields, so queued messages cannot revive the old connection.
        connection = nil
        eventSink = nil
        openConnection = nil
        await clearSession()
    }

    func clearSession() async {
        generation = UUID()
        fcm.invalidate()
        registering?.cancel(); registering = nil
        let old = relayBinding
        relayBinding = nil
        session = nil; enabled = false; pendingOpen = nil; webReady = false
        for continuation in tokenWaiters.values { continuation.resume(throwing: NativeError.code("session_changed")) }
        tokenWaiters.removeAll()
        center.removeAllPendingNotificationRequests()
        center.removeAllDeliveredNotifications()
        if let saved = try? SharedPushStore.activeBinding() { try? SharedPushStore.delete(bindingID: saved.bindingID) }
        try? SharedPushStore.deleteBinding()
        NativeSecretStore.delete(NativeSecretStore.pushAccount("binding"))
        UserDefaults.standard.removeObject(forKey: "assistant.notifications.enabled")
        if let old {
            // Detach locally at once; a failed network revoke never restores the old session.
            Task { try? await relay.revoke(old) }
        }
    }

    private func requireSession() throws -> NativeSession {
        guard let session, session.expires > Date(), connection != nil else { throw NativeError.code("not_authenticated") }
        return session
    }

    func handle(_ request: BridgeProtocol.Request, connectionID: String) async throws -> [String: Any] {
        guard accepts(connectionID: connectionID) else { throw NativeError.code("not_connected") }
        switch request.type {
        case "notifications.session":
            webReady = false
            if BridgeProtocol.boolean(request.payload["clear"]) == true || request.payload["user_id"] is NSNull {
                await clearSession(); return ["cleared": true]
            }
            guard let connection else { throw NativeError.code("not_connected") }
            let next = try NativeSession.parse(request.payload, connection: connection)
            let ticket = generation
            try await SessionVerifier().verify(next, origin: connection.origin)
            guard ticket == generation, accepts(connectionID: connectionID) else { throw NativeError.code("session_changed") }
            if session == nil {
                // An expired persisted session must not lend its receiver key to a fresh
                // login, even when relay registration recovers the same installation key.
                await clearSession()
            } else if let current = session, current != next {
                if current.userID != next.userID || current.deviceID != next.deviceID {
                    await clearSession()
                } else {
                    // A shorter verified expiry or a preview change invalidates suspended
                    // installs as surely as an account switch. Keep this account's opt-in.
                    generation = UUID()
                    fcm.invalidate()
                    registering?.cancel(); registering = nil
                    if next.expires < current.expires || next.previews != current.previews {
                        center.removeAllPendingNotificationRequests()
                        center.removeAllDeliveredNotifications()
                    }
                }
            }
            guard accepts(connectionID: connectionID) else { throw NativeError.code("session_changed") }
            session = next
            let id = relayBinding?.binding_id ?? connection.id
            try SharedPushStore.saveBinding(next.binding(id: id, origin: connection.address))
            var result: [String: Any] = ["active": true]
            if let pending = matchingPendingOpen() {
                result["pending_opened"] = pending
            }
            pendingOpen = nil
            return result
        case "notifications.state":
            webReady = true
            deliverPendingOpen()
            return await state()
        case "notifications.permission":
            _ = try requireSession()
            let ticket = generation
            let granted = try await center.requestAuthorization(options: [.alert, .badge, .sound])
            guard ticket == generation else { throw NativeError.code("session_changed") }
            enabled = granted
            if !granted { fcm.invalidate() }
            UserDefaults.standard.set(granted, forKey: "assistant.notifications.enabled")
            return ["granted": granted]
        case "notifications.register":
            return try await register()
        case "notifications.schedule":
            return try await schedule(request.payload)
        case "notifications.unregister":
            let current = session
            await clearSession()
            if let current, let connection, current.expires > Date() {
                session = current
                try SharedPushStore.saveBinding(current.binding(id: connection.id, origin: connection.address))
            }
            return ["enabled": false]
        case "connection.open":
            openConnection?(); return ["opened": true]
        default: throw NativeError.code("unknown_type")
        }
    }

    private func state() async -> [String: Any] {
        let settings = await center.notificationSettings()
        let granted = settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional
        let permission = granted ? "granted" : settings.authorizationStatus == .denied ? "denied" : "not-determined"
        return ["permission": permission, "enabled": granted && enabled && (session?.expires ?? .distantPast) > Date(),
                "push_available": pushAvailable]
    }

    private func schedule(_ payload: [String: Any]) async throws -> [String: Any] {
        let current = try requireSession()
        guard enabled else { throw NativeError.code("notifications_disabled") }
        guard !scheduleBusy else { throw NativeError.code("busy") }
        let snapshot = try NativeSchedule.parse(payload, session: current)
        let ticket = generation
        scheduleBusy = true
        defer { scheduleBusy = false }
        let settings = await center.notificationSettings()
        guard ticket == generation else { throw NativeError.code("session_changed") }
        guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else {
            throw NativeError.code("permission_denied")
        }
        let previous = await center.pendingNotificationRequests()
        guard ticket == generation else { throw NativeError.code("session_changed") }
        // iOS limits pending notifications. Validate the complete snapshot first, then replace;
        // restore the old calendar triggers if installation fails while this session is still active.
        center.removeAllPendingNotificationRequests()
        let now = Date()
        var installed: [String] = []
        var identifiers: [String] = []
        do {
            for item in snapshot.items {
                guard ticket == generation else { throw NativeError.code("session_changed") }
                let content = UNMutableNotificationContent()
                content.title = current.previews ? item.title : "Bro"
                content.body = current.previews ? item.body : "Напоминание"
                content.sound = .default
                content.userInfo = ["connection_id": connection!.id, "instance_id": current.instanceID,
                                    "user_id": current.userID, "device_id": current.deviceID,
                                    "id": item.key, "link": item.link]
                // Server-relative deadlines tolerate the phone's wall-clock being a little wrong.
                let fireAt = now.addingTimeInterval(item.due.timeIntervalSince(snapshot.serverNow))
                var calendar = Calendar(identifier: .gregorian)
                calendar.timeZone = TimeZone(secondsFromGMT: 0)!
                var components = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: fireAt)
                components.calendar = calendar
                components.timeZone = calendar.timeZone
                let hash = RelayClient.b64(Data(SHA256.hash(data: Data(item.key.utf8))))
                let identifier = "assistant." + snapshot.snapshotID + "." + hash
                identifiers.append(identifier)
                try await center.add(UNNotificationRequest(identifier: identifier, content: content,
                                      trigger: UNCalendarNotificationTrigger(dateMatching: components, repeats: false)))
                guard ticket == generation else { throw NativeError.code("session_changed") }
                installed.append(item.key)
            }
        } catch {
            center.removePendingNotificationRequests(withIdentifiers: identifiers)
            if ticket == generation {
                for request in previous { try? await center.add(request) }
            }
            throw error
        }
        return ["installed": installed]
    }

    private func apnsToken() async throws -> String {
        if let token { return token }
        let identifier = UUID()
        return try await withCheckedThrowingContinuation { continuation in
            tokenWaiters[identifier] = continuation
            UIApplication.shared.registerForRemoteNotifications()
            Task {
                try? await Task.sleep(nanoseconds: 20_000_000_000)
                if let pending = tokenWaiters.removeValue(forKey: identifier) {
                    pending.resume(throwing: NativeError.code("apns_unavailable"))
                }
            }
        }
    }

    private func register() async throws -> [String: Any] {
        _ = try requireSession()
        guard enabled else { throw NativeError.code("notifications_disabled") }
        guard pushAvailable else { throw NativeError.code("push_unavailable") }
        let ticket = generation
        if let registering {
            let fields = try await registering.value
            guard ticket == generation else { throw NativeError.code("session_changed") }
            return fields
        }
        let task = Task { @MainActor [self] () throws -> [String: Any] in
            let configuration = try PushTransportConfiguration.load()
            let appleToken = try await apnsToken()
            guard ticket == generation, !Task.isCancelled else { throw NativeError.code("session_changed") }
            let deviceToken: String
            if configuration.transport == .fcm {
                guard let data = apnsData else { throw NativeError.code("apns_unavailable") }
                deviceToken = try await fcm.token(configuration: configuration, apns: data, generation: ticket) { [weak self] _ in
                    guard let self, generation == ticket else { return }
                    refreshRegistration()
                }
            } else { deviceToken = appleToken }
            guard ticket == generation, !Task.isCancelled else { throw NativeError.code("session_changed") }
            let expiry = try requireSession().expires
            let result: RelayBinding
            if let old = relayBinding {
                do { result = try await relay.update(old, token: deviceToken, expires: expiry) }
                catch {
                    guard ticket == generation, !Task.isCancelled else { throw NativeError.code("session_changed") }
                    // A rotated management capability may have been lost with a PATCH response.
                    // A fresh assertion recovers this key/token's binding without re-attestation.
                    result = try await relay.register(token: deviceToken, expires: expiry)
                }
            } else { result = try await relay.register(token: deviceToken, expires: expiry) }
            guard ticket == generation, !Task.isCancelled else {
                Task { try? await relay.revoke(result) }
                throw NativeError.code("session_changed")
            }
            let keys = try SharedPushStore.loadOrCreate(bindingID: result.binding_id)
            let current = try requireSession()
            try SharedPushStore.saveBinding(current.binding(id: result.binding_id, origin: connection!.address))
            try NativeSecretStore.write(JSONEncoder().encode(result), NativeSecretStore.pushAccount("binding"))
            relayBinding = result
            return result.publicFields.merging(["public_key": keys.publicKey, "auth_secret": keys.auth,
                "instance_id": current.instanceID, "user_id": current.userID, "device_id": current.deviceID]) { _, new in new }
        }
        registering = task
        defer { if generation == ticket { registering = nil } }
        let fields = try await task.value
        guard ticket == generation else { throw NativeError.code("session_changed") }
        return fields
    }

    func receivedToken(_ data: Data) {
        apnsData = data
        let next = data.map { String(format: "%02x", $0) }.joined()
        let changed = token != nil && token != next
        token = next
        for continuation in tokenWaiters.values { continuation.resume(returning: next) }
        tokenWaiters.removeAll()
        if changed { refreshRegistration() }
    }

    private func refreshRegistration() {
        guard relayBinding != nil, session != nil, enabled else { return }
        let ticket = generation
        Task {
            if let fields = try? await register(), ticket == generation { eventSink?("notifications.token", fields) }
        }
    }

    static func receiverMatches(_ bindingID: String, connectionID: String, relayBindingID: String?) -> Bool {
        // Before registering push, local reminders bind to the connection itself.
        bindingID == connectionID || bindingID == relayBindingID
    }
    func registrationFailed() {
        for continuation in tokenWaiters.values { continuation.resume(throwing: NativeError.code("apns_unavailable")) }
        tokenWaiters.removeAll()
    }

    private func matchingPendingOpen() -> [String: Any]? {
        guard let payload = pendingOpen, let current = session, let connection, current.expires > Date(),
              payload["instance_id"] as? String == current.instanceID,
              payload["user_id"] as? String == current.userID, payload["device_id"] as? String == current.deviceID,
              payload["connection_id"] as? String == connection.id else { return nil }
        return payload
    }

    private func deliverPendingOpen() {
        guard webReady, let payload = matchingPendingOpen(), let sink = eventSink else { return }
        pendingOpen = nil
        sink("notifications.opened", payload)
    }

    func ready() { deliverPendingOpen() }
    func foreground() {
        if let session, session.expires <= Date() {
            Task { await clearSession(); eventSink?("shell.foreground", [:]) }
        } else { eventSink?("shell.foreground", [:]) }
    }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                           withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                           withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        Task { @MainActor in
            defer { completionHandler() }
            guard let binding = try? SharedPushStore.activeBinding(), binding.hasActiveSession(),
                  let link = PushCrypto.safeRelativeLink(info["link"] as? String) else { return }
            let local = info["connection_id"] as? String
            if let local {
                guard info["instance_id"] as? String == binding.instanceID,
                      info["user_id"] as? String == binding.userID, info["device_id"] as? String == binding.deviceID
                else { return }
                pendingOpen = ["connection_id": local, "instance_id": binding.instanceID,
                               "user_id": binding.userID!, "device_id": binding.deviceID!, "link": link]
            } else {
                guard info["binding_id"] as? String == binding.bindingID,
                      let data = UserDefaults.standard.data(forKey: "assistant.connection.v1"),
                      let saved = try? JSONDecoder().decode(AssistantConnection.self, from: data), saved.address == binding.origin,
                      saved.configuration.instance_id == binding.instanceID else { return }
                pendingOpen = ["connection_id": saved.id, "instance_id": binding.instanceID,
                               "user_id": binding.userID!, "device_id": binding.deviceID!, "link": link]
            }
            deliverPendingOpen()
        }
    }
}

final class AssistantAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        _ = NativeNotifications.shared
        // A launch for a location event delivers it only once monitoring is renewed.
        LocationCollector.shared.resume()
        return true
    }
    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        NativeNotifications.shared.receivedToken(deviceToken)
    }
    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        NativeNotifications.shared.registrationFailed()
    }
}
