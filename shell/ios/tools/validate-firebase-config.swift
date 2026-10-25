import Foundation

// Public mobile app configuration, never the relay's service-account JSON or Apple key.
// APNs builds stay independent of Firebase; an explicitly selected FCM build must be valid.
let environment = ProcessInfo.processInfo.environment
let transport = environment["ASSISTANT_PUSH_TRANSPORT"] ?? "apns"
func fail(_ reason: String) -> Never {
    FileHandle.standardError.write(Data(("error: Firebase publisher configuration: " + reason + "\n").utf8))
    exit(1)
}
guard transport == "apns" || transport == "fcm" else { fail("unknown push transport") }
let destination = URL(fileURLWithPath: environment["TARGET_BUILD_DIR"] ?? "")
    .appendingPathComponent(environment["UNLOCALIZED_RESOURCES_FOLDER_PATH"] ?? "")
    .appendingPathComponent("GoogleService-Info.plist")
if transport == "apns" {
    // A reused build directory must not accidentally retain a prior FCM configuration.
    if environment["TARGET_BUILD_DIR"] != nil, environment["UNLOCALIZED_RESOURCES_FOLDER_PATH"] != nil,
       FileManager.default.fileExists(atPath: destination.path) { try? FileManager.default.removeItem(at: destination) }
    exit(0)
}
guard let root = environment["SRCROOT"], let bundle = environment["PRODUCT_BUNDLE_IDENTIFIER"],
      let project = environment["ASSISTANT_FCM_PROJECT_ID"], !project.isEmpty, !project.contains("$("),
      environment["TARGET_BUILD_DIR"] != nil, environment["UNLOCALIZED_RESOURCES_FOLDER_PATH"] != nil
else { fail("FCM requires a project ID and app build settings") }
let source = environment["ASSISTANT_FIREBASE_CONFIG_PATH"].map { URL(fileURLWithPath: $0) }
    ?? URL(fileURLWithPath: root).appendingPathComponent("Firebase/GoogleService-Info.plist")
guard let bytes = try? Data(contentsOf: source),
      let raw = try? PropertyListSerialization.propertyList(from: bytes, options: [], format: nil),
      let plist = raw as? [String: Any]
else { fail("GoogleService-Info.plist is missing or invalid") }
guard plist["BUNDLE_ID"] as? String == bundle else { fail("Bundle ID does not match the app") }
guard plist["PROJECT_ID"] as? String == project else { fail("project ID does not match the publisher") }
for key in ["GOOGLE_APP_ID", "GCM_SENDER_ID", "API_KEY"] {
    guard let value = plist[key] as? String, !value.isEmpty, !value.contains("$(")
    else { fail("required public Firebase app configuration is missing") }
}
do {
    try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
    try bytes.write(to: destination, options: .atomic)
} catch { fail("could not bundle public Firebase configuration") }
