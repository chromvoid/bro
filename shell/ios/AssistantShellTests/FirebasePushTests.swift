import XCTest
@testable import AssistantShell

final class FirebasePushTests: XCTestCase {
    private let info: [String: Any] = ["AssistantPushTransport": "fcm", "AssistantAPNsEnvironment": "development",
                                       "AssistantFirebaseProjectID": "synthetic-push", "FirebaseAppDelegateProxyEnabled": false,
                                       "FirebaseMessagingAutoInitEnabled": false, "FirebaseMessagingInstallationIdEnabled": false]
    private let plist: [String: Any] = ["PROJECT_ID": "synthetic-push", "BUNDLE_ID": "dev.synthetic.assistant",
                                        "GOOGLE_APP_ID": "1:123456:ios:synthetic", "GCM_SENDER_ID": "123456",
                                        "API_KEY": "synthetic-public-api-key"]

    private func configuration() throws -> PushTransportConfiguration {
        try PushTransportConfiguration.validate(info: info, bundleID: "dev.synthetic.assistant",
                                                firebase: plist, firebasePath: "/synthetic/GoogleService-Info.plist")
    }

    func testFCMOptInRequiresMatchingPublisherAndApp() throws {
        let legacy = try PushTransportConfiguration.validate(info: [:], bundleID: "dev.synthetic.assistant", firebase: nil)
        XCTAssertEqual(legacy.transport, .apns)
        XCTAssertEqual(legacy.environment, "production")
        XCTAssertThrowsError(try PushTransportConfiguration.validate(info: info, bundleID: "dev.synthetic.assistant", firebase: nil))
        XCTAssertThrowsError(try PushTransportConfiguration.validate(info: info, bundleID: "other.app", firebase: plist))
        var wrong = plist; wrong["PROJECT_ID"] = "other-publisher"
        XCTAssertThrowsError(try PushTransportConfiguration.validate(info: info, bundleID: "dev.synthetic.assistant", firebase: wrong))
        wrong = plist; wrong["API_KEY"] = ""
        XCTAssertThrowsError(try PushTransportConfiguration.validate(info: info, bundleID: "dev.synthetic.assistant", firebase: wrong))
        var unsafe = info; unsafe["FirebaseMessagingAutoInitEnabled"] = true
        XCTAssertThrowsError(try PushTransportConfiguration.validate(info: unsafe, bundleID: "dev.synthetic.assistant", firebase: plist))
        XCTAssertEqual(try configuration().environment, "sandbox")
    }

    func testAttestedFCMDataKeepsOpaqueTokenAndHidesItFromInstallation() throws {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let token = "AbC:def_Ghi-JK0123"
        let data = try configuration().clientData(token: token, expires: now.addingTimeInterval(90 * 24 * 3600), now: now)
        XCTAssertEqual(data["transport"] as? String, "fcm")
        XCTAssertEqual(data["fcm_token"] as? String, token)
        XCTAssertNil(data["apns_token"])
        XCTAssertEqual(NativeSchedule.date(data["expires_at"] as! String), now.addingTimeInterval(30 * 24 * 3600))
        for bad in ["", "hello world", "new\nline", "кириллица", String(repeating: "x", count: 2049)] {
            XCTAssertThrowsError(try configuration().clientData(token: bad, expires: now), bad)
        }
        XCTAssertTrue(PushTransportConfiguration.validFCMToken(String(repeating: "x", count: 2048)))
        let binding = RelayBinding(binding_id: "synthetic-binding", send_capability: "send", management_capability: "private", expires_at: "future")
        XCTAssertEqual(binding.publicFields["kind"] as? String, "apns")
        XCTAssertNil(binding.publicFields["fcm_token"])
        XCTAssertNil(binding.publicFields["management_capability"])
    }

    @MainActor func testTransportUpgradeDoesNotBorrowLegacyAttestationOrReceiver() {
        let legacyRecords = ["attest-key": "one-shot-apple-attestation", "binding": "old-apns-channel"]
        XCTAssertEqual(NativeSecretStore.pushAccount("attest-key", transport: .apns), "attest-key")
        XCTAssertEqual(NativeSecretStore.pushAccount("binding", transport: .apns), "binding")
        XCTAssertNil(legacyRecords[NativeSecretStore.pushAccount("attest-key", transport: .fcm)])
        XCTAssertNil(legacyRecords[NativeSecretStore.pushAccount("binding", transport: .fcm)])
        XCTAssertFalse(NativeNotifications.receiverMatches("old-apns-channel", connectionID: "connection", relayBindingID: nil))
        XCTAssertFalse(NativeNotifications.receiverMatches("old-apns-channel", connectionID: "connection", relayBindingID: "new-fcm-channel"))
        XCTAssertTrue(NativeNotifications.receiverMatches("new-fcm-channel", connectionID: "connection", relayBindingID: "new-fcm-channel"))
        XCTAssertTrue(NativeNotifications.receiverMatches("old-apns-channel", connectionID: "connection", relayBindingID: "old-apns-channel"))
        XCTAssertTrue(NativeNotifications.receiverMatches("connection", connectionID: "connection", relayBindingID: nil))
    }

    @MainActor func testLateTokenAndRefreshCannotSurviveAccountChange() async throws {
        let provider = TestFCMProvider()
        let client = FirebasePushClient(provider: provider)
        let config = try configuration()
        let old = Task { try await client.token(configuration: config, apns: Data([1]), generation: UUID()) { _ in
            XCTFail("stale refresh reached new account")
        } }
        await provider.waitForTokenRequests(1)
        let staleCallback = try XCTUnwrap(provider.refresh)
        client.invalidate()
        await Task.yield()
        staleCallback()
        provider.completeToken("old-session-token")
        do { _ = try await old.value; XCTFail("old token must be refused") }
        catch { XCTAssertEqual((error as? NativeError)?.code, "session_changed") }
        XCTAssertNil(provider.refresh)
        await provider.waitForDeletion()
        provider.completeDeletion()
    }

    @MainActor func testDeletionFinishesBeforeAnotherAccountObtainsToken() async throws {
        let provider = TestFCMProvider()
        let client = FirebasePushClient(provider: provider)
        let config = try configuration()
        let first = Task { try await client.token(configuration: config, apns: Data([1]), generation: UUID()) { _ in } }
        await provider.waitForTokenRequests(1)
        provider.completeToken("first-account-token")
        _ = try await first.value
        let staleCallback = try XCTUnwrap(provider.refresh)
        client.invalidate()
        await provider.waitForDeletion()
        var refreshed: [String] = []
        let second = Task { try await client.token(configuration: config, apns: Data([2]), generation: UUID()) { refreshed.append($0) } }
        for _ in 0..<5 { await Task.yield() }
        XCTAssertEqual(provider.requests, 1)
        provider.completeDeletion()
        await provider.waitForTokenRequests(2)
        provider.completeToken("second-account-token")
        let secondToken = try await second.value
        XCTAssertEqual(secondToken, "second-account-token")
        XCTAssertEqual(provider.apns, Data([2]))
        XCTAssertEqual(provider.environment, "sandbox")
        staleCallback()
        for _ in 0..<5 { await Task.yield() }
        XCTAssertEqual(provider.requests, 2)
        provider.refresh?()
        await provider.waitForTokenRequests(3)
        provider.completeToken("rotated-second-account-token")
        for _ in 0..<5 { await Task.yield() }
        XCTAssertEqual(refreshed, ["rotated-second-account-token"])
    }
}

@MainActor private final class TestFCMProvider: NativeFCMProvider {
    var refresh: (@MainActor @Sendable () -> Void)?
    var requests = 0
    var apns: Data?
    var environment: String?
    var pending: [CheckedContinuation<String, Error>] = []
    var deletion: CheckedContinuation<Void, Never>?
    func configure(path: String) throws {}
    func setAPNSToken(_ token: Data, environment: String) { apns = token; self.environment = environment }
    func observeRefresh(_ handler: (@MainActor @Sendable () -> Void)?) { refresh = handler }
    func token() async throws -> String {
        requests += 1
        return try await withCheckedThrowingContinuation { pending.append($0) }
    }
    func deleteToken() async { await withCheckedContinuation { deletion = $0 } }
    func completeToken(_ value: String) { pending.removeFirst().resume(returning: value) }
    func completeDeletion() { deletion?.resume(); deletion = nil }
    func waitForTokenRequests(_ count: Int) async {
        for _ in 0..<1000 { if requests >= count { return }; await Task.yield() }
        XCTFail("expected token request")
    }
    func waitForDeletion() async {
        for _ in 0..<1000 { if deletion != nil { return }; await Task.yield() }
        XCTFail("expected token deletion")
    }
}
