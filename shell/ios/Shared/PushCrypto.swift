import CryptoKit
import Foundation
import Security

/// Only session metadata is shared through the App Group. Receiver secrets stay in Keychain.
struct ConnectionBinding: Codable, Equatable {
    let bindingID: String
    let instanceID: String
    let origin: String
    let userID: String?
    let deviceID: String?
    let previews: Bool
    let expiresAt: Date?

    init(bindingID: String, instanceID: String, origin: String, userID: String? = nil,
         deviceID: String? = nil, previews: Bool = false, expiresAt: Date? = nil) {
        self.bindingID = bindingID
        self.instanceID = instanceID
        self.origin = origin
        self.userID = userID
        self.deviceID = deviceID
        self.previews = previews
        self.expiresAt = expiresAt
    }

    func hasActiveSession(at now: Date = Date()) -> Bool {
        guard let userID, let deviceID, let expiresAt,
              PushCrypto.validIdentifier(userID), PushCrypto.validIdentifier(deviceID)
        else { return false }
        return expiresAt > now
    }
}

/// RFC 8291 receiver material. The public point is X9.63 uncompressed P-256, not DER.
struct ReceiverKeys: Codable {
    let privateKey: Data
    let authSecret: Data
    private let agreementKey: P256.KeyAgreement.PrivateKey

    var publicKey: String { PushCrypto.base64URL(agreementKey.publicKey.x963Representation) }
    var auth: String { PushCrypto.base64URL(authSecret) }

    init(privateKey: Data, authSecret: Data) throws {
        guard privateKey.count == 32, authSecret.count == 16 else { throw PushCrypto.Error.invalidKeys }
        do {
            self.agreementKey = try P256.KeyAgreement.PrivateKey(rawRepresentation: privateKey)
        } catch {
            throw PushCrypto.Error.invalidKeys
        }
        self.privateKey = privateKey
        self.authSecret = authSecret
    }

    static func generate() throws -> ReceiverKeys {
        let key = P256.KeyAgreement.PrivateKey()
        var auth = Data(count: 16)
        let status = auth.withUnsafeMutableBytes { bytes in
            SecRandomCopyBytes(kSecRandomDefault, bytes.count, bytes.baseAddress!)
        }
        guard status == errSecSuccess else { throw PushCrypto.Error.keychain(status) }
        return try ReceiverKeys(privateKey: key.rawRepresentation, authSecret: auth)
    }

    private enum CodingKeys: String, CodingKey { case privateKey, authSecret }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        try self.init(privateKey: container.decode(Data.self, forKey: .privateKey),
                      authSecret: container.decode(Data.self, forKey: .authSecret))
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(privateKey, forKey: .privateKey)
        try container.encode(authSecret, forKey: .authSecret)
    }

    fileprivate func sharedSecret(with sender: P256.KeyAgreement.PublicKey) throws -> SharedSecret {
        try agreementKey.sharedSecretFromKeyAgreement(with: sender)
    }

    fileprivate var publicBytes: Data { agreementKey.publicKey.x963Representation }
}

enum PushCrypto {
    enum Error: Swift.Error {
        case invalidKeys
        case invalidEnvelope
        case invalidCiphertext
        case invalidPadding
        case configuration
        case invalidBinding
        case keychain(OSStatus)
    }

    /// Native push uses exactly the complete RFC 8291 aes128gcm content body.
    static func decrypt(_ body: Data, keys: ReceiverKeys) throws -> Data {
        // Header: salt(16), record size(4, network order), key-id length(1), key-id(65).
        // A record includes at least one delimiter and a 16-byte authentication tag.
        guard (103...4096).contains(body.count) else { throw Error.invalidCiphertext }
        let bytes = [UInt8](body)
        let recordSize = bytes[16...19].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        guard bytes[20] == 65, bytes[21] == 4 else { throw Error.invalidCiphertext }
        let ciphertext = Data(bytes[86...])
        // RFC 8291 permits one record only, with rs strictly larger than its length.
        guard recordSize > UInt32(ciphertext.count) else { throw Error.invalidCiphertext }
        let sender: P256.KeyAgreement.PublicKey
        do {
            sender = try P256.KeyAgreement.PublicKey(x963Representation: Data(bytes[21..<86]))
        } catch {
            throw Error.invalidCiphertext
        }
        let shared: SharedSecret
        do {
            shared = try keys.sharedSecret(with: sender)
        } catch {
            throw Error.invalidCiphertext
        }

        var keyInfo = Data("WebPush: info\0".utf8)
        keyInfo.append(keys.publicBytes)
        keyInfo.append(sender.x963Representation)
        let ikm = shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: keys.authSecret,
                                               sharedInfo: keyInfo, outputByteCount: 32)
        let salt = Data(bytes[..<16])
        let key = HKDF<SHA256>.deriveKey(inputKeyMaterial: ikm, salt: salt,
                                        info: Data("Content-Encoding: aes128gcm\0".utf8), outputByteCount: 16)
        let nonceKey = HKDF<SHA256>.deriveKey(inputKeyMaterial: ikm, salt: salt,
                                             info: Data("Content-Encoding: nonce\0".utf8), outputByteCount: 12)
        let nonceBytes = nonceKey.withUnsafeBytes { Data($0) }
        let plaintext: Data
        do {
            let nonce = try AES.GCM.Nonce(data: nonceBytes)
            let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext.dropLast(16),
                                            tag: ciphertext.suffix(16))
            plaintext = try AES.GCM.open(box, using: key)
        } catch {
            throw Error.invalidCiphertext
        }
        // Zero padding follows the final 0x02 delimiter; searching for any 0x02 is unsafe.
        guard let delimiter = plaintext.lastIndex(where: { $0 != 0 }), plaintext[delimiter] == 2
        else { throw Error.invalidPadding }
        return Data(plaintext[..<delimiter])
    }

    static func base64URL(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    /// Reject malformed and noncanonical encodings before allocating the decoded payload.
    static func decodeBase64URL(_ value: String, maximumBytes: Int = 4096) -> Data? {
        guard maximumBytes > 0, !value.isEmpty, value.utf8.count <= ((maximumBytes + 2) / 3) * 4,
              value.utf8.allSatisfy({ (65...90).contains($0) || (97...122).contains($0)
                  || (48...57).contains($0) || $0 == 45 || $0 == 95 }), value.utf8.count % 4 != 1
        else { return nil }
        let padded = value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
            + String(repeating: "=", count: (4 - value.utf8.count % 4) % 4)
        guard let data = Data(base64Encoded: padded), data.count <= maximumBytes,
              base64URL(data) == value else { return nil }
        return data
    }

    static func validIdentifier(_ value: String) -> Bool {
        (1...128).contains(value.utf8.count)
            && !value.unicodeScalars.contains(where: { CharacterSet.whitespacesAndNewlines.contains($0)
                || CharacterSet.controlCharacters.contains($0) })
    }

    /// A push cannot introduce an origin or escape into a sibling service on a shared host.
    static func safeRelativeLink(_ value: String?) -> String? {
        guard let value, (1...2048).contains(value.utf8.count), value.hasPrefix("/"),
              !value.hasPrefix("//"), !value.contains("\\"),
              !value.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }),
              let parts = URLComponents(string: value), parts.scheme == nil, parts.host == nil,
              parts.user == nil, parts.password == nil, parts.port == nil,
              let decoded = parts.percentEncodedPath.removingPercentEncoding,
              !decoded.contains("\\"), !decoded.contains("%"),
              !decoded.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) })
        else { return nil }
        let lowerPath = parts.percentEncodedPath.lowercased()
        guard !lowerPath.contains("%2f"), !lowerPath.contains("%5c"),
              !decoded.split(separator: "/", omittingEmptySubsequences: false)
                .contains(where: { $0 == "." || $0 == ".." })
        else { return nil }
        let prefix = decoded.dropFirst().split(separator: "/", omittingEmptySubsequences: false).first ?? ""
        let pages: Set<String> = [
            "assistant", "today", "recordings", "food", "tasks", "timers", "notifications", "circle",
            "activity", "personal", "settings", "admin", "accounts", "workers", "plugins", "welcome",
            "no-access", "login", "account-deletion", "_ui", "app", "chat", "community", "modules", "places",
        ]
        return pages.contains(String(prefix)) ? value : nil
    }
}

/// One current connection binding; per-binding receiver keys survive ordinary app restarts.
/// Both targets must supply identical AssistantAppGroup and AssistantKeychainGroup plist values.
enum SharedPushStore {
    private static let bindingAccount = "assistant.active-push-binding.v1"
    private static let receiverService = "dev.assistant.shell.push-receiver.v1"

    static func activeBinding() throws -> ConnectionBinding? {
        guard let data = try defaults().data(forKey: bindingAccount) else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        let binding: ConnectionBinding
        do { binding = try decoder.decode(ConnectionBinding.self, from: data) }
        catch { throw PushCrypto.Error.invalidBinding }
        guard valid(binding) else { throw PushCrypto.Error.invalidBinding }
        return binding
    }

    static func saveBinding(_ binding: ConnectionBinding) throws {
        guard valid(binding) else { throw PushCrypto.Error.invalidBinding }
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .millisecondsSince1970
        let data = try encoder.encode(binding)
        let store = try defaults()
        store.set(data, forKey: bindingAccount)
        // Publish connection/logout changes to the independently launched extension promptly.
        store.synchronize()
    }

    static func deleteBinding() throws {
        let store = try defaults()
        store.removeObject(forKey: bindingAccount)
        store.synchronize()
    }

    static func load(bindingID: String) throws -> ReceiverKeys? {
        var query = try keychainQuery(bindingID: bindingID)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw PushCrypto.Error.keychain(status) }
        guard let data = result as? Data else { throw PushCrypto.Error.invalidKeys }
        do { return try JSONDecoder().decode(ReceiverKeys.self, from: data) }
        catch { throw PushCrypto.Error.invalidKeys }
    }

    static func loadOrCreate(bindingID: String) throws -> ReceiverKeys {
        if let keys = try load(bindingID: bindingID) { return keys }
        let keys = try ReceiverKeys.generate()
        var query = try keychainQuery(bindingID: bindingID)
        query[kSecValueData as String] = try JSONEncoder().encode(keys)
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(query as CFDictionary, nil)
        // If another process won the creation race, use its exact material, never rotate it.
        if status == errSecDuplicateItem, let existing = try load(bindingID: bindingID) { return existing }
        guard status == errSecSuccess else { throw PushCrypto.Error.keychain(status) }
        return keys
    }

    static func delete(bindingID: String) throws {
        let status = SecItemDelete(try keychainQuery(bindingID: bindingID) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw PushCrypto.Error.keychain(status)
        }
    }

    private static func configuration(_ key: String) throws -> String {
        guard let value = Bundle.main.object(forInfoDictionaryKey: key) as? String,
              !value.isEmpty, !value.contains("$("),
              value == value.trimmingCharacters(in: .whitespacesAndNewlines)
        else { throw PushCrypto.Error.configuration }
        return value
    }

    private static func defaults() throws -> UserDefaults {
        let group = try configuration("AssistantAppGroup")
        guard group.hasPrefix("group."), let store = UserDefaults(suiteName: group) else {
            throw PushCrypto.Error.configuration
        }
        return store
    }

    private static func keychainQuery(bindingID: String) throws -> [String: Any] {
        guard PushCrypto.validIdentifier(bindingID) else { throw PushCrypto.Error.invalidBinding }
        return [kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: receiverService,
                kSecAttrAccount as String: bindingID,
                kSecAttrAccessGroup as String: try configuration("AssistantKeychainGroup"),
                kSecAttrSynchronizable as String: false]
    }

    private static func valid(_ binding: ConnectionBinding) -> Bool {
        guard PushCrypto.validIdentifier(binding.bindingID), PushCrypto.validIdentifier(binding.instanceID),
              binding.userID.map(PushCrypto.validIdentifier) ?? true,
              binding.deviceID.map(PushCrypto.validIdentifier) ?? true,
              let parts = URLComponents(string: binding.origin),
              let scheme = parts.scheme?.lowercased(), let host = parts.host?.lowercased(), !host.isEmpty,
              parts.user == nil, parts.password == nil, parts.query == nil, parts.fragment == nil,
              parts.path.isEmpty || parts.path == "/"
        else { return false }
        if scheme == "https" { return true }
        #if DEBUG
        return scheme == "http" && ["127.0.0.1", "localhost", "::1"].contains(host)
        #else
        return false
        #endif
    }
}

/// Keep transport parsing separate from RFC 8291 so the APNs contract has a single boundary.
struct NativePushEnvelope {
    let bindingID: String
    let ciphertext: Data

    init?(userInfo: [AnyHashable: Any]) {
        guard let assistant = userInfo["assistant"] as? [String: Any],
              let version = assistant["v"] as? NSNumber,
              CFGetTypeID(version) != CFBooleanGetTypeID(), version == 1,
              let bindingID = assistant["binding_id"] as? String, UUID(uuidString: bindingID) != nil,
              let encoded = assistant["ciphertext"] as? String,
              let ciphertext = PushCrypto.decodeBase64URL(encoded)
        else { return nil }
        self.bindingID = bindingID
        self.ciphertext = ciphertext
    }
}

struct PushNotificationMessage: Decodable {
    let id: String
    let title: String
    let body: String
    let link: String?

    var valid: Bool {
        PushCrypto.validIdentifier(id) && (1...120).contains(title.count) && body.count <= 1000
            && !title.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) })
            && !body.unicodeScalars.contains(where: {
                CharacterSet.controlCharacters.contains($0) && $0 != "\n" && $0 != "\t"
            })
    }
}
