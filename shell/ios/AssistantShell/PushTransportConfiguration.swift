import Foundation

enum NativePushTransport: String { case apns, fcm }

struct PushTransportConfiguration {
    let transport: NativePushTransport
    let environment: String
    let firebasePath: String?

    static func load(bundle: Bundle = .main) throws -> PushTransportConfiguration {
        let path = bundle.path(forResource: "GoogleService-Info", ofType: "plist")
        let plist = path.flatMap { NSDictionary(contentsOfFile: $0) as? [String: Any] }
        return try validate(info: bundle.infoDictionary ?? [:], bundleID: bundle.bundleIdentifier,
                            firebase: plist, firebasePath: path)
    }

    static func validate(info: [String: Any], bundleID: String?, firebase: [String: Any]?,
                         firebasePath: String? = nil) throws -> PushTransportConfiguration {
        let raw = info["AssistantPushTransport"] as? String ?? "apns"
        guard let transport = NativePushTransport(rawValue: raw) else { throw NativeError.code("invalid_push_transport") }
        let value = info["AssistantAPNsEnvironment"] as? String ?? "production"
        let environment = value == "development" ? "sandbox" : value
        guard environment == "sandbox" || environment == "production" else { throw NativeError.code("invalid_push_environment") }
        if transport == .fcm {
            guard let firebase else { throw NativeError.code("firebase_config_missing") }
            for key in ["FirebaseAppDelegateProxyEnabled", "FirebaseMessagingAutoInitEnabled", "FirebaseMessagingInstallationIdEnabled"] {
                guard BridgeProtocol.boolean(info[key]) == false else { throw NativeError.code("firebase_privacy_configuration") }
            }
            guard let project = info["AssistantFirebaseProjectID"] as? String, !project.isEmpty, !project.contains("$("),
                  let bundleID, firebase["BUNDLE_ID"] as? String == bundleID,
                  firebase["PROJECT_ID"] as? String == project else { throw NativeError.code("firebase_config_mismatch") }
            for key in ["GOOGLE_APP_ID", "GCM_SENDER_ID", "API_KEY"] {
                guard let value = firebase[key] as? String, !value.isEmpty, !value.contains("$(")
                else { throw NativeError.code("firebase_config_missing") }
            }
        }
        return PushTransportConfiguration(transport: transport, environment: environment, firebasePath: firebasePath)
    }

    static func validFCMToken(_ value: String) -> Bool {
        (1...2048).contains(value.utf8.count) && value.utf8.allSatisfy { (33...126).contains($0) }
    }

    func clientData(token: String, expires: Date, now: Date = Date()) throws -> [String: Any] {
        let expiry = min(expires, now.addingTimeInterval(30 * 24 * 3600))
        var fields: [String: Any] = ["environment": environment, "expires_at": ISO8601DateFormatter().string(from: expiry)]
        if transport == .fcm {
            guard Self.validFCMToken(token) else { throw NativeError.code("invalid_fcm_token") }
            fields["transport"] = "fcm"; fields["fcm_token"] = token
        } else { fields["apns_token"] = token }
        return fields
    }
}
