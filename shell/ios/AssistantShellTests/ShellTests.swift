import XCTest
@testable import AssistantShell

final class ShellOriginTests: XCTestCase {
    func testOnlyHTTPSOrDebugLoopbackOriginsAreAccepted() {
        XCTAssertNotNil(ShellOrigin("https://assistant.example", allowLoopbackHTTP: false))
        XCTAssertNotNil(ShellOrigin("https://assistant.example/", allowLoopbackHTTP: false))
        XCTAssertNotNil(ShellOrigin("http://127.0.0.1:8766", allowLoopbackHTTP: true))
        XCTAssertNil(ShellOrigin("http://127.0.0.1:8766", allowLoopbackHTTP: false))
        XCTAssertNil(ShellOrigin("http://assistant.example", allowLoopbackHTTP: true))
        for value in ["", "assistant.example", "https://user:secret@assistant.example",
                      "https://assistant.example/app", "https://assistant.example?x=1", "https://assistant.example#x"] {
            XCTAssertNil(ShellOrigin(value, allowLoopbackHTTP: true), value)
        }
    }

    func testOwnershipComparesSchemeHostAndEffectivePort() throws {
        let origin = try XCTUnwrap(ShellOrigin("https://assistant.example", allowLoopbackHTTP: false))
        XCTAssertEqual(origin.url.absoluteString, "https://assistant.example/")
        XCTAssertTrue(origin.owns(URL(string: "https://ASSISTANT.example/food?day=1")))
        XCTAssertTrue(origin.owns(URL(string: "https://assistant.example:443/")))
        XCTAssertTrue(origin.owns(scheme: "https", host: "assistant.example", port: 0))
        XCTAssertFalse(origin.owns(URL(string: "http://assistant.example/")))
        XCTAssertFalse(origin.owns(URL(string: "https://assistant.example:8443/")))
        XCTAssertFalse(origin.owns(URL(string: "https://assistant.example.evil.example/")))
        XCTAssertFalse(origin.owns(URL(string: "blob:https://assistant.example/1")))
        XCTAssertFalse(origin.owns(nil))
        let local = try XCTUnwrap(ShellOrigin("http://127.0.0.1:8766", allowLoopbackHTTP: true))
        XCTAssertTrue(local.owns(scheme: "http", host: "127.0.0.1", port: 8766))
        XCTAssertFalse(local.owns(scheme: "http", host: "127.0.0.1", port: 0))
    }

    func testOnlyAssistantRoutesOnTheOwnOriginArePages() throws {
        let origin = try XCTUnwrap(ShellOrigin("https://assistant.example", allowLoopbackHTTP: false))
        for path in [
            "/assistant", "/today", "/food", "/tasks/42", "/timers", "/notifications", "/circle",
            "/personal", "/settings/voice", "/admin", "/accounts", "/workers", "/plugins/trading",
            "/welcome", "/no-access", "/login?next=%2Fassistant", "/_ui/food", "/app/chat",
            "/chat/42#message", "/community", "/modules/trading", "/recordings", "/activity/42",
            "/account-deletion",
        ] { XCTAssertTrue(origin.ownsPage(URL(string: "https://assistant.example\(path)")), path) }

        for path in [
            "", "/", "/?next=/assistant", "/git", "/git/assistant", "/grafana/", "/assistant-other",
            "/assistant.html", "//assistant", "/v1/nutrition/export?format=csv", "/assets/app.js",
            "/assistant/../git", "/assistant/%2e%2e/git", "/assistant%2fgit", "/assistant%5c../git",
        ] { XCTAssertFalse(origin.ownsPage(URL(string: "https://assistant.example\(path)")), path) }

        for value in [
            "http://assistant.example/assistant", "https://evil.example/assistant",
            "https://assistant.example.evil.example/assistant", "https://user@assistant.example/assistant",
            "https://assistant.example:8443/assistant", "blob:https://assistant.example/assistant",
        ] { XCTAssertFalse(origin.ownsPage(URL(string: value)), value) }
        XCTAssertFalse(origin.ownsPage(nil))
        XCTAssertTrue(origin.ownsPage(URL(string: "https://ASSISTANT.example:443/assistant")))
    }
}

final class BridgeProtocolTests: XCTestCase {
    func testRequestsAreParsedStrictly() {
        XCTAssertEqual(BridgeProtocol.parse(#"{"id":"1","type":"shell.capabilities","payload":{}}"#),
                       BridgeProtocol.Request(id: "1", type: "shell.capabilities"))
        XCTAssertNotNil(BridgeProtocol.parse(#"{"id":"1","type":"shell.capabilities"}"#))
        for body: Any in ["not json", #"{"id":"","type":"shell.capabilities"}"#, #"{"id":"1","type":"Shell"}"#,
                          #"{"id":"1","type":"shell.capabilities","payload":[]}"#, String(repeating: "x", count: 20_000), 7] {
            XCTAssertNil(BridgeProtocol.parse(body), "\(body)")
        }
    }

    func testRepliesMatchTheAndroidFormat() throws {
        let reply = BridgeProtocol.handle(.init(id: "7", type: "shell.capabilities"))
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(reply.utf8)) as? [String: Any])
        XCTAssertEqual(object["id"] as? String, "7")
        XCTAssertEqual(object["ok"] as? Bool, true)
        let result = try XCTUnwrap(object["result"] as? [String: Any])
        XCTAssertEqual(result["shell_api"] as? Int, 1)
        XCTAssertEqual(result["platform"] as? String, "ios")
        XCTAssertEqual(result["glasses"] as? Bool, false)
        XCTAssertEqual(result["screen_wake"] as? Bool, true)
        XCTAssertEqual(result["system_bars"] as? Bool, true)
        XCTAssertEqual(result["location"] as? [String: Bool], ["background": true])
        XCTAssertEqual(BridgeProtocol.handle(.init(id: "8", type: "glasses.open")),
                       #"{"error":"unknown_type","id":"8","ok":false}"#)
    }

    func testAPositionIsAnsweredInWholeMetresAndAnISOMoment() {
        let fix = BridgeProtocol.fix(latitude: 55.73812, longitude: 37.58004, accuracy: 12.2,
                                     timestamp: Date(timeIntervalSince1970: 1_791_569_400))
        XCTAssertEqual(fix["latitude"] as? Double, 55.73812)
        XCTAssertEqual(fix["longitude"] as? Double, 37.58004)
        XCTAssertEqual(fix["accuracy_m"] as? Int, 13)
        XCTAssertEqual(fix["captured_at"] as? String, "2026-10-09T18:10:00.000Z")
        XCTAssertEqual(BridgeProtocol.fix(latitude: 0, longitude: 0, accuracy: 0.2, timestamp: Date())["accuracy_m"] as? Int, 1)
    }

    func testABackgroundPointIsWhatTheCollectorRouteTakes() throws {
        let moment = Date(timeIntervalSince1970: 1_791_569_400)
        let point = LocationCollector.point(latitude: 55.73812, longitude: 37.58004, accuracy: 0.2,
                                            at: moment, source: "visit_arrival")
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(point)) as? [String: Any])
        XCTAssertEqual(Set(object.keys), ["captured_at", "latitude", "longitude", "accuracy_m", "source"])
        XCTAssertEqual(object["captured_at"] as? String, "2026-10-09T18:10:00.000Z")
        XCTAssertEqual(object["accuracy_m"] as? Int, 1)
        XCTAssertEqual(object["source"] as? String, "visit_arrival")
        // The server takes the last 30 days; the phone drops what it could no longer send.
        XCTAssertTrue(LocationCollector.fresh(point, now: moment.addingTimeInterval(28 * 86_400)))
        XCTAssertFalse(LocationCollector.fresh(point, now: moment.addingTimeInterval(30 * 86_400)))
    }

    func testTheThemeIsAnActualBoolean() throws {
        let dark = try XCTUnwrap(BridgeProtocol.parse(#"{"id":"1","type":"theme.set","payload":{"dark":true}}"#))
        XCTAssertEqual(BridgeProtocol.darkTheme(dark), true)
        XCTAssertEqual(BridgeProtocol.handle(dark), #"{"id":"1","ok":true,"result":{"dark":true}}"#)
        for body in [#"{"id":"2","type":"theme.set","payload":{"dark":1}}"#, #"{"id":"2","type":"theme.set","payload":{"dark":"true"}}"#,
                     #"{"id":"2","type":"theme.set","payload":{}}"#] {
            let request = try XCTUnwrap(BridgeProtocol.parse(body))
            XCTAssertNil(BridgeProtocol.darkTheme(request), body)
            XCTAssertEqual(BridgeProtocol.handle(request), #"{"error":"bad_payload","id":"2","ok":false}"#)
        }
        XCTAssertNil(BridgeProtocol.darkTheme(.init(id: "3", type: "shell.capabilities")))
    }

    func testTheScreenStaysOnOnlyWhenAsked() {
        XCTAssertEqual(BridgeProtocol.awake(.init(id: "1", type: "screen.wake")), true)
        XCTAssertEqual(BridgeProtocol.awake(.init(id: "1", type: "screen.release")), false)
        XCTAssertNil(BridgeProtocol.awake(.init(id: "1", type: "shell.capabilities")))
        XCTAssertEqual(BridgeProtocol.handle(.init(id: "9", type: "screen.wake")),
                       #"{"id":"9","ok":true,"result":{"awake":true}}"#)
        XCTAssertEqual(BridgeProtocol.handle(.init(id: "10", type: "screen.release")),
                       #"{"id":"10","ok":true,"result":{"awake":false}}"#)
        XCTAssertEqual(BridgeProtocol.handle(.init(id: "11", type: "screen.dim")),
                       #"{"error":"unknown_type","id":"11","ok":false}"#)
    }

    func testTheSystemTextSizeReachesThePageAsAScale() {
        XCTAssertEqual(PageText.scale(bodyPointSize: 17), 1)
        XCTAssertEqual(PageText.scale(bodyPointSize: 34), 2)
        XCTAssertEqual(PageText.source(21.0 / 17),
                       "document.documentElement.style.setProperty('--system-text-scale', '1.235')")
    }
}
