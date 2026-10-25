import Foundation

/**
 The versioned message bridge between the web client and this shell, the same as on Android.
 Only the main frame of the assistant's own origin may use it; anything else gets no answer.
 */
enum BridgeProtocol {
    static let version = 1
    static let name = "AssistantShell"

    static func integer(_ value: Any?) -> Int? {
        guard let value = value as? NSNumber, CFGetTypeID(value) != CFBooleanGetTypeID(),
              value.doubleValue.isFinite, value.doubleValue == Double(value.intValue) else { return nil }
        return value.intValue
    }

    static func boolean(_ value: Any?) -> Bool? {
        guard let value = value as? NSNumber, CFGetTypeID(value) == CFBooleanGetTypeID() else { return nil }
        return value.boolValue
    }

    struct Request: Equatable {
        let id: String
        let type: String
        var payload: [String: Any] = [:]
        static func == (lhs: Request, rhs: Request) -> Bool {
            lhs.id == rhs.id && lhs.type == rhs.type && NSDictionary(dictionary: lhs.payload).isEqual(to: rhs.payload)
        }
    }

    static func parse(_ body: Any) -> Request? {
        guard let text = body as? String, text.utf8.count <= 131_072,
              let object = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
              let id = object["id"] as? String, (1...64).contains(id.count),
              let type = object["type"] as? String,
              type.range(of: "^[a-z]+(\\.[a-z]+)+$", options: .regularExpression) != nil
        else { return nil }
        guard text.utf8.count <= (type == "notifications.schedule" ? 131_072 : 16_384) else { return nil }
        if let payload = object["payload"], !(payload is [String: Any]) { return nil }
        return Request(id: id, type: type, payload: object["payload"] as? [String: Any] ?? [:])
    }

    /// iOS has no glasses support: the Even G2 link exists only in the Android shell.
    static func handle(_ request: Request) -> String {
        if let on = awake(request) { return reply(request.id, result: ["awake": on]) }
        switch request.type {
        case "shell.capabilities": return reply(request.id, result: capabilities(glasses: false))
        case "theme.set":
            guard let dark = darkTheme(request) else { return failure(request.id, "bad_payload") }
            return reply(request.id, result: ["dark": dark])
        default: return failure(request.id, "unknown_type")
        }
    }

    static func capabilities(glasses: Bool, pushAvailable: Bool = false) -> [String: Any] {
        ["shell_api": version, "platform": "ios", "glasses": glasses, "screen_wake": true, "system_bars": true,
         "ambient_audio": true, "location": ["background": true],
         "connection": true, "notifications": ["push": pushAvailable ? "apns" as Any : NSNull(), "local": true]]
    }

    /// A position as `location.current` answers it: whole metres of accuracy, an ISO 8601 moment.
    static func fix(latitude: Double, longitude: Double, accuracy: Double, timestamp: Date) -> [String: Any] {
        let format = ISO8601DateFormatter()
        format.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return ["latitude": latitude, "longitude": longitude, "accuracy_m": max(1, Int(accuracy.rounded(.up))),
                "captured_at": format.string(from: timestamp)]
    }

    /// The page's resolved theme from `theme.set`: an actual boolean, never a number or a string; nil otherwise.
    /// The page names it once its launch screen is gone (web/src/main.ts), so it also ends the launch colours.
    static func darkTheme(_ request: Request) -> Bool? {
        guard request.type == "theme.set", let value = request.payload["dark"] as? NSNumber,
              CFGetTypeID(value) == CFBooleanGetTypeID() else { return nil }
        return value.boolValue
    }

    /// What `screen.wake` and `screen.release` ask for during a call: the screen kept on, or not; nil for anything else.
    static func awake(_ request: Request) -> Bool? {
        switch request.type {
        case "screen.wake": return true
        case "screen.release": return false
        default: return nil
        }
    }

    static func reply(_ id: String, result: [String: Any]) -> String {
        encode(["id": id, "ok": true, "result": result])
    }

    static func failure(_ id: String, _ code: String) -> String {
        encode(["id": id, "ok": false, "error": code])
    }

    private static func encode(_ value: [String: Any]) -> String {
        // Only strings, numbers and booleans are ever passed here.
        let data = (try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])) ?? Data("{}".utf8)
        return String(decoding: data, as: UTF8.self)
    }
}
