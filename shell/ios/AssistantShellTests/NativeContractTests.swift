import XCTest
@testable import AssistantShell

final class NativeContractTests: XCTestCase {
    private let instance = "10000000-0000-0000-0000-000000000001"
    private let user = "20000000-0000-0000-0000-000000000001"
    private let device = "30000000-0000-0000-0000-000000000001"

    private func configuration() -> ClientConfiguration {
        ClientConfiguration(instance_id: instance, name: "Synthetic assistant", client_api: 1, shell_api: 1,
                            entry_path: "/assistant", capabilities: .init(notifications: true, calls: true, account_deletion: true))
    }
    private func session() -> NativeSession {
        NativeSession(instanceID: instance, userID: user, deviceID: device,
                      expires: Date().addingTimeInterval(3600), previews: false)
    }
    private func iso(_ value: Date) -> String { ISO8601DateFormatter().string(from: value) }
    private func snapshot(count: Int = 1) -> [String: Any] {
        let now = Date()
        let items: [[String: Any]] = (0..<count).map { i in
            ["key": "reminder:\(i)", "revision": 1, "due_at": iso(now.addingTimeInterval(600)),
             "title": String(repeating: "😀", count: 120), "body": String(repeating: "Я", count: 300), "link": "/timers"]
        }
        return ["snapshot_id": "40000000-0000-0000-0000-000000000001", "server_now": iso(now),
                "expires_at": iso(now.addingTimeInterval(1800)), "items": items]
    }

    func testConfigurationRequiresImplementedClientContract() throws {
        XCTAssertTrue(configuration().isCompatible)
        let bad = ClientConfiguration(instance_id: instance, name: "Synthetic", client_api: 2, shell_api: 1,
                                      entry_path: "/assistant", capabilities: .init(notifications: true, calls: true, account_deletion: true))
        XCTAssertFalse(bad.isCompatible)
        XCTAssertNil(ShellOrigin("https://assistant.example:0", allowLoopbackHTTP: false))
        XCTAssertNil(ShellOrigin("https://assistant.example:65536", allowLoopbackHTTP: false))
        XCTAssertEqual(ShellOrigin("https://ASSISTANT.example:443/", allowLoopbackHTTP: false)?.canonical,
                       "https://assistant.example")
    }

    func testSupportContactsAcceptOnlyHTTPSWithoutCredentials() {
        XCTAssertEqual(SupportContact.httpsURL("https://publisher.example/support?app=ios")?.absoluteString,
                       "https://publisher.example/support?app=ios")
        for value: String? in [nil, "", "$(ASSISTANT_PUBLISHER_SUPPORT_URL)", "http://publisher.example/support",
                               "https:///support", "https://user:secret@publisher.example/support",
                               "https://publisher.example:0/support", "https://publisher.example:65536/support",
                               " https://publisher.example", "https://publisher.example/invalid path",
                               "https://publisher.example/\n", String(repeating: "x", count: 2_049)] {
            XCTAssertNil(SupportContact.httpsURL(value), value ?? "nil")
        }
    }

    func testSessionIdentityAndExpiryAreVerifiedFromTheServer() throws {
        let current = session()
        let valid: [String: Any] = ["id": user, "device_id": device, "session_expires_at": iso(current.expires.addingTimeInterval(1))]
        XCTAssertNoThrow(try SessionVerifier.validate(JSONSerialization.data(withJSONObject: valid), reported: current))
        var wrong = valid; wrong["id"] = "different-user"
        XCTAssertThrowsError(try SessionVerifier.validate(JSONSerialization.data(withJSONObject: wrong), reported: current))
        wrong = valid; wrong["session_expires_at"] = iso(Date().addingTimeInterval(-5))
        XCTAssertThrowsError(try SessionVerifier.validate(JSONSerialization.data(withJSONObject: wrong), reported: current))
        let connection = AssistantConnection(id: UUID().uuidString, address: "https://assistant.example", configuration: configuration())
        let payload: [String: Any] = ["instance_id": instance, "user_id": user, "device_id": device,
                                      "expires_at": iso(current.expires)]
        XCTAssertFalse(try NativeSession.parse(payload, connection: connection).previews)
        var invalid = payload; invalid["instance_id"] = UUID().uuidString
        XCTAssertThrowsError(try NativeSession.parse(invalid, connection: connection))
    }

    func testCompleteUnicodeSnapshotFitsOnlyTheScheduleException() throws {
        let payload = snapshot(count: 60)
        let bytes = try JSONSerialization.data(withJSONObject: ["id": "1", "type": "notifications.schedule", "payload": payload])
        XCTAssertGreaterThan(bytes.count, 16_384)
        XCTAssertLessThan(bytes.count, 131_072)
        let text = try XCTUnwrap(String(data: bytes, encoding: .utf8))
        let request = try XCTUnwrap(BridgeProtocol.parse(text))
        XCTAssertEqual(try NativeSchedule.parse(request.payload, session: session()).items.count, 60)
        XCTAssertNil(BridgeProtocol.parse(text.replacingOccurrences(of: "notifications.schedule", with: "notifications.register")))
        let oversized = #"{"id":"1","type":"notifications.schedule","payload":{"text":""# + String(repeating: "😀", count: 40_000) + #""}}"#
        XCTAssertNil(BridgeProtocol.parse(oversized))
    }

    func testInvalidSnapshotIsRejectedBeforeNativeMutation() throws {
        XCTAssertThrowsError(try NativeSchedule.parse(snapshot(count: 61), session: session()))
        var payload = snapshot(count: 2)
        var items = payload["items"] as! [[String: Any]]
        items[1]["key"] = items[0]["key"]
        payload["items"] = items
        XCTAssertThrowsError(try NativeSchedule.parse(payload, session: session()))
        for badRevision: Any in [true, 0, -1, 1.5] {
            payload = snapshot(); items = payload["items"] as! [[String: Any]]
            items[0]["revision"] = badRevision; payload["items"] = items
            XCTAssertThrowsError(try NativeSchedule.parse(payload, session: session()))
        }
        payload = snapshot(); items = payload["items"] as! [[String: Any]]
        items[0]["link"] = "https://evil.example/tasks"; payload["items"] = items
        XCTAssertThrowsError(try NativeSchedule.parse(payload, session: session()))
        payload = snapshot(); payload["expires_at"] = iso(Date().addingTimeInterval(7200))
        XCTAssertThrowsError(try NativeSchedule.parse(payload, session: session()))
        XCTAssertEqual(BridgeProtocol.integer(NSNumber(value: 1)), 1)
        XCTAssertNil(BridgeProtocol.integer(true))
    }

    func testRFC8291ReceiverAndAuthenticatedCiphertext() throws {
        // Public synthetic interoperability vector from RFC 8291 §5, also used by Python core.
        let keys = try ReceiverKeys(privateKey: XCTUnwrap(PushCrypto.decodeBase64URL("q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94")),
                                    authSecret: XCTUnwrap(PushCrypto.decodeBase64URL("BTBZMqHH6r4Tts7J_aSIgg")))
        XCTAssertEqual(keys.publicKey, "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4")
        let body = try XCTUnwrap(PushCrypto.decodeBase64URL(
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml" +
            "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT" +
            "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"))
        XCTAssertEqual(String(data: try PushCrypto.decrypt(body, keys: keys), encoding: .utf8),
                       "When I grow up, I want to be a watermelon")
        var corrupt = body; corrupt[corrupt.count - 1] ^= 1
        XCTAssertThrowsError(try PushCrypto.decrypt(corrupt, keys: keys))
        XCTAssertThrowsError(try PushCrypto.decrypt(Data(body.prefix(102)), keys: keys))
    }

    func testNativePushRejectsStaleSessionsAndUnsafeLinks() throws {
        let binding = ConnectionBinding(bindingID: UUID().uuidString, instanceID: instance, origin: "https://assistant.example",
                                        userID: user, deviceID: device, previews: false, expiresAt: Date().addingTimeInterval(60))
        XCTAssertTrue(binding.hasActiveSession())
        XCTAssertFalse(ConnectionBinding(bindingID: binding.bindingID, instanceID: instance, origin: binding.origin).hasActiveSession())
        for value in ["https://evil.example", "//evil.example", "/tasks/../settings", "/tasks/%2fsecret", "/tasks/%5csecret"] {
            XCTAssertNil(PushCrypto.safeRelativeLink(value), value)
        }
        XCTAssertEqual(PushCrypto.safeRelativeLink("/tasks/" + user), "/tasks/" + user)
        XCTAssertNil(NativePushEnvelope(userInfo: ["assistant": ["v": true, "binding_id": binding.bindingID, "ciphertext": "AA"]]))
    }
}
