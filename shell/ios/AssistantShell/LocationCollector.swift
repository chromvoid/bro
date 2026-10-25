import CoreLocation
import Foundation
import UIKit

/// Background movement recording (docs/location-weather-spec.md §3.1): with «Всегда», significant
/// location changes and visits wake or relaunch the app. Points wait in a protected file outside
/// backups and go to the server with this phone's own collector grant, kept in the Keychain; the
/// server ends the grant at logout or device removal, and a refused upload stops recording here.
@MainActor
final class LocationCollector: NSObject, CLLocationManagerDelegate {
    static let shared = LocationCollector()

    struct Grant: Codable { let origin: String; let user_id: String; let token: String }
    /// One point as the collector route takes it.
    struct Point: Codable, Hashable, Sendable {
        let captured_at: String, latitude: Double, longitude: Double, accuracy_m: Int, source: String
    }

    private static let account = "location.collector.v1"
    private static let askedKey = "assistant.location.always.asked"
    private static let limit = 20_000
    private static let batch = 300
    private let manager = CLLocationManager()
    private var authorization: [CheckedContinuation<Void, Never>] = []
    private var queue: [Point]?
    private var uploading = false

    override init() {
        super.init()
        manager.delegate = self
        NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
            Task { @MainActor in LocationCollector.shared.becameActive() }
        }
    }

    // MARK: Bridge

    func handle(_ request: BridgeProtocol.Request, origin: ShellOrigin) async throws -> [String: Any] {
        switch request.type {
        case "location.background.status":
            return status(user: request.payload["user_id"] as? String, origin: origin)
        case "location.background.enable":
            guard let user = request.payload["user_id"] as? String, PushCrypto.validIdentifier(user) else {
                throw NativeError.code("bad_payload")
            }
            return try await enable(user: user, origin: origin)
        case "location.background.disable":
            return await disable(origin: origin)
        default: throw NativeError.code("unknown_type")
        }
    }

    /// What the page shows; a grant of another account or server is ended first.
    func status(user: String?, origin: ShellOrigin) -> [String: Any] {
        if let grant = storedGrant(), grant.origin != origin.canonical || (user != nil && grant.user_id != user) {
            disableLocally()
        }
        return ["enabled": storedGrant() != nil && always, "permission": permission, "queued": pending().count]
    }

    private var always: Bool { manager.authorizationStatus == .authorizedAlways }

    private var permission: String {
        switch manager.authorizationStatus {
        case .authorizedAlways: return "granted"
        case .denied, .restricted: return "denied"
        default: return "prompt"
        }
    }

    /// Asks for «Всегда» when needed, enrols this phone with the page's cookie session, then records.
    private func enable(user: String, origin: ShellOrigin) async throws -> [String: Any] {
        switch manager.authorizationStatus {
        case .denied, .restricted: throw NativeError.code("denied")
        case .authorizedAlways: break
        case .authorizedWhenInUse where UserDefaults.standard.bool(forKey: Self.askedKey):
            // iOS asks for «Всегда» once; afterwards it is chosen in the app's settings.
            guard let settings = URL(string: UIApplication.openSettingsURLString) else { throw NativeError.code("denied") }
            await withCheckedContinuation { continuation in
                authorization.append(continuation)
                UIApplication.shared.open(settings)
                timeout()
            }
        default:
            UserDefaults.standard.set(true, forKey: Self.askedKey)
            await withCheckedContinuation { continuation in
                authorization.append(continuation)
                manager.requestAlwaysAuthorization()
                timeout()
            }
        }
        guard always else { return status(user: user, origin: origin) }
        if storedGrant()?.user_id != user || storedGrant()?.origin != origin.canonical {
            disableLocally()
            try save(try await enroll(user: user, origin: origin))
        }
        start()
        return status(user: user, origin: origin)
    }

    /// The person turns it off: the server ends the grant, the queue and the grant leave the phone.
    private func disable(origin: ShellOrigin) async -> [String: Any] {
        if let grant = storedGrant(), let owner = ShellOrigin(grant.origin, allowLoopbackHTTP: AssistantConnection.debugHTTP) {
            var request = URLRequest(url: owner.url.appendingPathComponent("v1/me/location/collector/revoke"))
            request.httpMethod = "POST"
            request.httpBody = Data("{}".utf8)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.setValue("Bearer \(grant.token)", forHTTPHeaderField: "Authorization")
            _ = try? await send(request)
        }
        disableLocally()
        return status(user: nil, origin: origin)
    }

    /// POST /v1/me/location/collectors with the page's cookies; only the bearer grant stays.
    private func enroll(user: String, origin: ShellOrigin) async throws -> Grant {
        let cookies = await SessionVerifier.cookies(origin: origin)
        func request(_ path: String) -> URLRequest {
            var request = URLRequest(url: origin.url.appendingPathComponent(path), cachePolicy: .reloadIgnoringLocalCacheData)
            request.setValue("application/json", forHTTPHeaderField: "Accept")
            let allowed = cookies.filter { SessionVerifier.matches($0, path: "/" + path) }
            for (name, value) in HTTPCookie.requestHeaderFields(with: allowed) { request.setValue(value, forHTTPHeaderField: name) }
            return request
        }
        let (me, meStatus) = try await send(request("v1/me"))
        guard meStatus == 200, me["id"] as? String == user else { throw NativeError.code("not_authenticated") }
        let (csrf, csrfStatus) = try await send(request("v1/auth/csrf"))
        guard csrfStatus == 200, let token = csrf["token"] as? String, !token.isEmpty else {
            throw NativeError.code("not_authenticated")
        }
        var enrol = request("v1/me/location/collectors")
        enrol.httpMethod = "POST"
        enrol.httpBody = try JSONSerialization.data(withJSONObject: ["installation_id": UUID().uuidString.lowercased()])
        enrol.setValue("application/json", forHTTPHeaderField: "Content-Type")
        enrol.setValue(origin.canonical, forHTTPHeaderField: "Origin")
        enrol.setValue(token, forHTTPHeaderField: "X-CSRF-Token")
        enrol.setValue(user, forHTTPHeaderField: "X-Expected-User")
        let (grant, status) = try await send(enrol)
        guard status == 201, grant["user_id"] as? String == user, let bearer = grant["token"] as? String, !bearer.isEmpty else {
            throw NativeError.code("enrollment_failed")
        }
        return Grant(origin: origin.canonical, user_id: user, token: bearer)
    }

    // MARK: Recording

    /// At every launch, including a relaunch for a location event: monitoring is renewed so the event arrives.
    func resume() {
        if storedGrant() != nil && always { start() }
        Task { await upload() }
    }

    private func start() {
        manager.startMonitoringSignificantLocationChanges()
        manager.startMonitoringVisits()
    }

    func disableLocally() {
        manager.stopMonitoringSignificantLocationChanges()
        manager.stopMonitoringVisits()
        NativeSecretStore.delete(Self.account)
        queue = []
        try? FileManager.default.removeItem(at: Self.queueURL)
    }

    private func received(_ points: [Point]) {
        guard storedGrant() != nil, !points.isEmpty else { return }
        store(Array((pending() + points).suffix(Self.limit)))
        Task { await upload() }
    }

    /// Batches go while iOS keeps the app awake; what is left waits for the next event or the next time
    /// the app is opened. Points that arrive meanwhile join the queue and are never overwritten.
    func upload() async {
        guard !uploading, let grant = storedGrant(),
              let origin = ShellOrigin(grant.origin, allowLoopbackHTTP: AssistantConnection.debugHTTP) else { return }
        uploading = true
        let task = UIApplication.shared.beginBackgroundTask(withName: "location-upload")
        defer {
            uploading = false
            if task != .invalid { UIApplication.shared.endBackgroundTask(task) }
        }
        let stale = Set(pending().filter { !Self.fresh($0, now: Date()) })
        if !stale.isEmpty { store(pending().filter { !stale.contains($0) }) }
        while true {
            let sent = Array(pending().prefix(Self.batch))
            if sent.isEmpty { return }
            var request = URLRequest(url: origin.url.appendingPathComponent("v1/me/location/collector/points"))
            request.httpMethod = "POST"
            request.httpBody = try? JSONEncoder().encode(["points": sent])
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.setValue("Bearer \(grant.token)", forHTTPHeaderField: "Authorization")
            // Recording turned off meanwhile: the answer no longer concerns this queue.
            guard let reply = try? await send(request), storedGrant()?.token == grant.token else { return }
            switch reply.1 {
            // A batch the server can never accept would block the queue forever.
            case 200..<300, 413, 422:
                let done = Set(sent)
                store(pending().filter { !done.contains($0) })
            case 401, 403: disableLocally(); return
            default: return
            }
        }
    }

    // MARK: Storage

    private func storedGrant() -> Grant? {
        guard let data = try? NativeSecretStore.read(Self.account) else { return nil }
        return try? JSONDecoder().decode(Grant.self, from: data)
    }

    private func save(_ grant: Grant) throws { try NativeSecretStore.write(try JSONEncoder().encode(grant), Self.account) }

    private static var queueURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("location", isDirectory: true).appendingPathComponent("queue.json")
    }

    private func pending() -> [Point] {
        if let queue { return queue }
        let loaded = (try? Data(contentsOf: Self.queueURL)).flatMap { try? JSONDecoder().decode([Point].self, from: $0) } ?? []
        queue = loaded
        return loaded
    }

    private func store(_ points: [Point]) {
        queue = points
        var url = Self.queueURL
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        guard let data = try? JSONEncoder().encode(points),
              (try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])) != nil else { return }
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
    }

    /// A JSON answer from this origin only: redirects are refused, nothing is cached or stored.
    private func send(_ request: URLRequest) async throws -> ([String: Any], Int) {
        let options = URLSessionConfiguration.ephemeral
        options.httpCookieStorage = nil; options.httpShouldSetCookies = false
        options.urlCredentialStorage = nil; options.urlCache = nil
        options.timeoutIntervalForRequest = 20; options.timeoutIntervalForResource = 30
        let session = URLSession(configuration: options, delegate: SessionVerifier(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let (bytes, response) = try await NativeResponse.data(session: session, request: request, maximumBytes: 131_072)
        guard let http = response as? HTTPURLResponse else { throw NativeError.code("network_unavailable") }
        return ((try? JSONSerialization.jsonObject(with: bytes) as? [String: Any]) ?? [:], http.statusCode)
    }

    // MARK: Location events

    nonisolated static func point(latitude: Double, longitude: Double, accuracy: Double, at moment: Date, source: String) -> Point {
        let fix = BridgeProtocol.fix(latitude: latitude, longitude: longitude, accuracy: accuracy, timestamp: moment)
        return Point(captured_at: fix["captured_at"] as? String ?? "", latitude: latitude, longitude: longitude,
                     accuracy_m: fix["accuracy_m"] as? Int ?? 1, source: source)
    }

    nonisolated static func fresh(_ point: Point, now: Date) -> Bool {
        let format = ISO8601DateFormatter()
        format.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        guard let moment = format.date(from: point.captured_at) else { return false }
        // The server takes points of the last 30 days.
        return now.timeIntervalSince(moment) <= 29 * 86_400
    }

    /// The system's prompt or the settings app hands the screen back: the answer is in by then, or
    /// arrives a moment later through the delegate.
    private func becameActive() {
        if !authorization.isEmpty {
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                self?.authorizationChanged(force: true)
            }
        }
        Task { await upload() }
    }

    /// A request nobody answers (the prompt never shown, settings left open) ends after a minute.
    private func timeout() {
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 60_000_000_000)
            self?.authorizationChanged(force: true)
        }
    }

    private func authorizationChanged(force: Bool = false) {
        guard force || manager.authorizationStatus != .notDetermined else { return }
        let waiting = authorization
        authorization = []
        waiting.forEach { $0.resume() }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in self.authorizationChanged() }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let points = locations.filter { $0.horizontalAccuracy > 0 }.map {
            Self.point(latitude: $0.coordinate.latitude, longitude: $0.coordinate.longitude,
                       accuracy: $0.horizontalAccuracy, at: $0.timestamp, source: "background")
        }
        Task { @MainActor in self.received(points) }
    }

    /// A visit pins when the person arrived and left; iOS reports either side as it learns it.
    nonisolated func locationManager(_ manager: CLLocationManager, didVisit visit: CLVisit) {
        let latitude = visit.coordinate.latitude, longitude = visit.coordinate.longitude
        let accuracy = max(1, visit.horizontalAccuracy)
        var points: [Point] = []
        if visit.arrivalDate != .distantPast {
            points.append(Self.point(latitude: latitude, longitude: longitude, accuracy: accuracy,
                                     at: visit.arrivalDate, source: "visit_arrival"))
        }
        if visit.departureDate != .distantFuture {
            points.append(Self.point(latitude: latitude, longitude: longitude, accuracy: accuracy,
                                     at: visit.departureDate, source: "visit_departure"))
        }
        Task { @MainActor in self.received(points) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}
}
