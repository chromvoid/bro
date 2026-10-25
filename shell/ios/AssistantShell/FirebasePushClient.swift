import FirebaseCore
import FirebaseMessaging
import Foundation

@MainActor protocol NativeFCMProvider: AnyObject {
    func configure(path: String) throws
    func setAPNSToken(_ token: Data, environment: String)
    func observeRefresh(_ handler: (@MainActor @Sendable () -> Void)?)
    func token() async throws -> String
    func deleteToken() async
}

/// The Firebase delegate is replaced for each native session. A callback queued for a
/// discarded delegate retains that session's generation, rather than borrowing new authority.
private final class FirebaseTokenObserver: NSObject, MessagingDelegate, @unchecked Sendable {
    let refresh: @MainActor @Sendable () -> Void
    init(_ refresh: @escaping @MainActor @Sendable () -> Void) { self.refresh = refresh }
    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        guard fcmToken != nil else { return }
        let callback = refresh
        Task { @MainActor in callback() }
    }
}

@MainActor final class FirebaseMessagingProvider: NativeFCMProvider {
    private var observer: FirebaseTokenObserver?
    func configure(path: String) throws {
        if FirebaseApp.app() == nil {
            guard let options = FirebaseOptions(contentsOfFile: path) else { throw NativeError.code("firebase_config_missing") }
            FirebaseApp.configure(options: options)
        }
        // Even after a prior process enabled it, never generate tokens before native opt-in.
        Messaging.messaging().isAutoInitEnabled = false
    }
    func setAPNSToken(_ token: Data, environment: String) {
        Messaging.messaging().setAPNSToken(token, type: environment == "sandbox" ? .sandbox : .prod)
    }
    func observeRefresh(_ handler: (@MainActor @Sendable () -> Void)?) {
        guard FirebaseApp.app() != nil else { return }
        observer = handler.map(FirebaseTokenObserver.init)
        Messaging.messaging().delegate = observer
        Messaging.messaging().isAutoInitEnabled = false
    }
    func token() async throws -> String {
        try await withCheckedThrowingContinuation { continuation in
            Messaging.messaging().token { token, _ in
                guard let token, PushTransportConfiguration.validFCMToken(token) else {
                    continuation.resume(throwing: NativeError.code("fcm_unavailable")); return
                }
                continuation.resume(returning: token)
            }
        }
    }
    func deleteToken() async {
        guard FirebaseApp.app() != nil else { return }
        await withCheckedContinuation { continuation in
            Messaging.messaging().deleteToken { _ in continuation.resume() }
        }
    }
}

/// Token deletion and acquisition share one device-wide SDK. Never let a delayed logout
/// deletion erase a new account's token. Relay capabilities and receiver keys are revoked by
/// NativeNotifications immediately, even while Firebase's network deletion is outstanding.
@MainActor final class FirebasePushClient {
    private let provider: NativeFCMProvider
    private let sdkGate = RelayOperationGate()
    private var activeGeneration: UUID?
    private var deleting: Task<Void, Never>?
    private var lastToken: String?
    private var refreshTask: Task<Void, Never>?
    init(provider: NativeFCMProvider? = nil) { self.provider = provider ?? FirebaseMessagingProvider() }

    func token(configuration: PushTransportConfiguration, apns: Data, generation: UUID,
               refreshed: @escaping @MainActor @Sendable (String) -> Void) async throws -> String {
        activeGeneration = generation
        if let deleting { await deleting.value }
        guard activeGeneration == generation, !Task.isCancelled else { throw NativeError.code("session_changed") }
        guard let path = configuration.firebasePath else { throw NativeError.code("firebase_config_missing") }
        try provider.configure(path: path)
        provider.observeRefresh { [weak self] in
            guard let self, activeGeneration == generation, refreshTask == nil else { return }
            refreshTask = Task { @MainActor [weak self] in
                guard let self else { return }
                defer { if activeGeneration == generation { refreshTask = nil } }
                guard let next = try? await fetchToken(generation: generation), activeGeneration == generation,
                      !Task.isCancelled, PushTransportConfiguration.validFCMToken(next), next != lastToken else { return }
                lastToken = next
                refreshed(next)
            }
        }
        provider.setAPNSToken(apns, environment: configuration.environment)
        let result = try await fetchToken(generation: generation)
        guard activeGeneration == generation, !Task.isCancelled else { throw NativeError.code("session_changed") }
        guard PushTransportConfiguration.validFCMToken(result) else { throw NativeError.code("invalid_fcm_token") }
        lastToken = result
        return result
    }

    private func fetchToken(generation: UUID) async throws -> String {
        await sdkGate.acquire()
        defer { Task { await sdkGate.release() } }
        guard activeGeneration == generation, !Task.isCancelled else { throw NativeError.code("session_changed") }
        return try await provider.token()
    }

    func invalidate() {
        activeGeneration = nil
        lastToken = nil
        refreshTask?.cancel(); refreshTask = nil
        provider.observeRefresh(nil)
        let prior = deleting
        let provider = provider
        let gate = sdkGate
        deleting = Task { @MainActor in
            if let prior { await prior.value }
            await gate.acquire()
            defer { Task { await gate.release() } }
            await provider.deleteToken()
        }
    }
}
