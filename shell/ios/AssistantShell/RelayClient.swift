import CryptoKit
import DeviceCheck
import Foundation
import Security

enum NativeError: LocalizedError {
    case code(String)
    var code: String { if case .code(let value) = self { return value }; return "unavailable" }
    var errorDescription: String? { code }
}

enum NativeSecretStore {
    static func pushAccount(_ account: String, transport: NativePushTransport? = nil) -> String {
        let selected = transport ?? NativePushTransport(rawValue:
            Bundle.main.object(forInfoDictionaryKey: "AssistantPushTransport") as? String ?? "apns") ?? .apns
        // Existing APNs releases retain their records. FCM is a separate relay authority,
        // whose App Attest key has never been attested to that transport's schema.
        return selected == .fcm ? "fcm." + account : account
    }
    static func query(_ account: String) -> [String: Any] {
        var result: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                   kSecAttrService as String: "assistant.relay.v1", kSecAttrAccount as String: account]
        if let group = Bundle.main.object(forInfoDictionaryKey: "AssistantKeychainGroup") as? String,
           !group.isEmpty, !group.contains("$(") { result[kSecAttrAccessGroup as String] = group }
        return result
    }
    static func read(_ account: String) throws -> Data? {
        var q = query(account)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var value: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &value)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = value as? Data else { throw NativeError.code("keychain_unavailable") }
        return data
    }
    static func write(_ data: Data, _ account: String) throws {
        let q = query(account)
        let changes: [String: Any] = [kSecValueData as String: data,
                                      kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        var status = SecItemUpdate(q as CFDictionary, changes as CFDictionary)
        if status == errSecItemNotFound {
            status = SecItemAdd(q.merging(changes) { _, value in value } as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw NativeError.code("keychain_unavailable") }
    }
    static func delete(_ account: String) { SecItemDelete(query(account) as CFDictionary) }
}

struct RelayBinding: Codable {
    let binding_id: String
    let send_capability: String
    let management_capability: String
    let expires_at: String
    var publicFields: [String: Any] {
        ["kind": "apns", "address": binding_id, "send_capability": send_capability]
    }
}

/// Keep proof generation and its HTTP request together. App Attest assertion counters are
/// monotonic, so asynchronous delete/update/register requests must not arrive in reverse order.
actor RelayOperationGate {
    private var busy = false
    private var waiters: [CheckedContinuation<Void, Never>] = []
    func acquire() async {
        if !busy { busy = true; return }
        await withCheckedContinuation { waiters.append($0) }
    }
    func release() {
        if waiters.isEmpty { busy = false }
        else { waiters.removeFirst().resume() }
    }
}

/// The publisher's relay is a fixed build setting. A server page cannot choose its address.
@MainActor final class RelayClient: NSObject, URLSessionTaskDelegate {
    private(set) var origin: ShellOrigin?
    private var keyID: String?
    private var attested = false
    private let attest = DCAppAttestService.shared
    private var session: URLSession?
    private let gate = RelayOperationGate()
    static func b64(_ value: Data) -> String {
        value.base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
    static func unb64(_ value: String) -> Data? {
        let raw = value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        return Data(base64Encoded: raw + String(repeating: "=", count: (4 - raw.count % 4) % 4))
    }
    override init() {
        let address = Bundle.main.object(forInfoDictionaryKey: "AssistantPushRelay") as? String ?? ""
        origin = ShellOrigin(address, allowLoopbackHTTP: false)
        super.init()
        if let bytes = try? NativeSecretStore.read(NativeSecretStore.pushAccount("attest-key")),
           let saved = try? JSONDecoder().decode(AttestKey.self, from: bytes) {
            keyID = saved.id; attested = saved.attested
        }
    }
    var available: Bool { origin != nil && attest.isSupported }
    private struct AttestKey: Codable { let id: String; let attested: Bool }
    private struct Challenge: Decodable {
        let challenge_id: String
        let challenge: String
        let expires_at: String
        let key_known: Bool
    }

    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask,
                               willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                               completionHandler: @escaping (URLRequest?) -> Void) {
        // Relay endpoints are not redirected, even to a different path on the fixed host.
        completionHandler(nil)
    }

    private func request(_ path: String, method: String, body: [String: Any], bearer: String? = nil) async throws -> Data {
        guard let origin else { throw NativeError.code("push_unavailable") }
        if session == nil {
            let options = URLSessionConfiguration.ephemeral
            options.httpCookieStorage = nil; options.httpShouldSetCookies = false; options.urlCache = nil
            options.timeoutIntervalForRequest = 15; options.timeoutIntervalForResource = 20
            session = URLSession(configuration: options, delegate: self, delegateQueue: nil)
        }
        var request = URLRequest(url: origin.url.appendingPathComponent(path))
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])
        if let bearer { request.setValue("Bearer " + bearer, forHTTPHeaderField: "Authorization") }
        let (bytes, response) = try await NativeResponse.data(session: session!, request: request)
        guard let http = response as? HTTPURLResponse, origin.owns(http.url), (200..<300).contains(http.statusCode),
              bytes.count <= 16_384 else { throw NativeError.code("relay_refused") }
        return bytes
    }

    private func key() async throws -> String {
        guard available else { throw NativeError.code("push_unavailable") }
        if let keyID { return keyID }
        let id: String = try await withCheckedThrowingContinuation { continuation in
            attest.generateKey { key, error in
                if let key { continuation.resume(returning: key) }
                else { continuation.resume(throwing: error ?? NativeError.code("attestation_failed")) }
            }
        }
        keyID = id; attested = false
        try NativeSecretStore.write(JSONEncoder().encode(AttestKey(id: id, attested: false)), NativeSecretStore.pushAccount("attest-key"))
        return id
    }

    private func proof(purpose: String, binding: String? = nil, client: [String: Any]) async throws -> [String: Any] {
        var id = try await key()
        var body: [String: Any] = ["purpose": purpose, "key_id": id]
        if let binding { body["binding_id"] = binding }
        var challenge = try JSONDecoder().decode(Challenge.self, from: await request("v1/challenges", method: "POST", body: body))
        if purpose == "register", !challenge.key_known, attested {
            // Apple attestation is one-shot. If a prior first registration never reached the
            // relay, start a new key; a known relay key instead recovers through an assertion.
            keyID = nil; attested = false
            NativeSecretStore.delete(NativeSecretStore.pushAccount("attest-key"))
            id = try await key()
            body["key_id"] = id
            challenge = try JSONDecoder().decode(Challenge.self, from: await request("v1/challenges", method: "POST", body: body))
        }
        guard let nonce = Self.unb64(challenge.challenge), nonce.count == 32,
              let expires = NativeSchedule.date(challenge.expires_at), expires > Date() else {
            throw NativeError.code("invalid_challenge")
        }
        let clientData = try JSONSerialization.data(withJSONObject: client, options: [.sortedKeys])
        let hash = Data(SHA256.hash(data: nonce + clientData))
        let first = !challenge.key_known
        guard purpose == "register" || !first else { throw NativeError.code("attestation_required") }
        if first {
            // Persist before invoking Apple's one-shot operation, including uncertain errors.
            attested = true
            try NativeSecretStore.write(JSONEncoder().encode(AttestKey(id: id, attested: true)), NativeSecretStore.pushAccount("attest-key"))
        }
        let signed: Data = try await withCheckedThrowingContinuation { continuation in
            let done: @Sendable (Data?, Error?) -> Void = { data, error in
                if let data { continuation.resume(returning: data) }
                else { continuation.resume(throwing: error ?? NativeError.code("attestation_failed")) }
            }
            if first { attest.attestKey(id, clientDataHash: hash, completionHandler: done) }
            else { attest.generateAssertion(id, clientDataHash: hash, completionHandler: done) }
        }
        return ["challenge_id": challenge.challenge_id, "key_id": id, "client_data": Self.b64(clientData),
                first ? "attestation" : "assertion": Self.b64(signed)]
    }

    private func client(token: String, expires: Date) throws -> [String: Any] {
        try PushTransportConfiguration.load().clientData(token: token, expires: expires)
    }

    func register(token: String, expires: Date) async throws -> RelayBinding {
        await gate.acquire()
        defer { Task { await gate.release() } }
        try Task.checkCancellation()
        let result = try JSONDecoder().decode(RelayBinding.self,
            from: await request("v1/bindings", method: "POST", body: proof(purpose: "register", client: client(token: token, expires: expires))))
        guard UUID(uuidString: result.binding_id) != nil, !result.send_capability.isEmpty,
              !result.management_capability.isEmpty else { throw NativeError.code("invalid_binding") }
        attested = true
        try NativeSecretStore.write(JSONEncoder().encode(AttestKey(id: keyID!, attested: true)), NativeSecretStore.pushAccount("attest-key"))
        return result
    }

    func update(_ binding: RelayBinding, token: String, expires: Date) async throws -> RelayBinding {
        await gate.acquire()
        defer { Task { await gate.release() } }
        try Task.checkCancellation()
        return try JSONDecoder().decode(RelayBinding.self, from: await request("v1/bindings/" + binding.binding_id,
            method: "PATCH", body: proof(purpose: "update", binding: binding.binding_id,
                client: client(token: token, expires: expires)), bearer: binding.management_capability))
    }

    func revoke(_ binding: RelayBinding) async throws {
        await gate.acquire()
        defer { Task { await gate.release() } }
        try Task.checkCancellation()
        _ = try await request("v1/bindings/" + binding.binding_id, method: "DELETE",
                              body: proof(purpose: "delete", binding: binding.binding_id, client: [:]),
                              bearer: binding.management_capability)
    }
}
