import Foundation
import WebKit

/// Keep authentication and microphone ownership separate. Only this selected origin receives
/// its own WebView cookies; no redirect can carry either a credential or an audio body elsewhere.
final class AmbientTransport: NSObject, URLSessionDataDelegate {
    struct Credentials {
        let cookie: String
        let csrf: String
        let acceptedFormats: [String]
        init(cookie: String, csrf: String, acceptedFormats: [String] = []) {
            self.cookie = cookie; self.csrf = csrf; self.acceptedFormats = acceptedFormats
        }
    }
    private struct Pending {
        let continuation: CheckedContinuation<(Data, HTTPURLResponse), Error>
        var data = Data()
        var tooLarge = false
    }
    private let lock = NSLock()
    private var pending: [Int: Pending] = [:]
    private lazy var session: URLSession = {
        // Apple's background URLSession always follows redirects and ignores the redirect delegate.
        // An ephemeral session deliberately preserves the origin boundary. The audio background
        // mode permits uploads during capture; durable files resume on a later foreground after
        // process termination rather than transferring to an unchecked redirect in a daemon.
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.urlCredentialStorage = nil
        configuration.urlCache = nil
        configuration.httpMaximumConnectionsPerHost = 1
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 60
        return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }()

    @MainActor static func credentials(_ reported: NativeSession, origin: ShellOrigin, requireReady: Bool = false) async throws -> Credentials {
        let cookies = await SessionVerifier.cookies(origin: origin)
        let client = AmbientTransport()
        defer { client.cancel() }
        func request(_ path: String) -> URLRequest {
            var request = URLRequest(url: origin.url.appendingPathComponent(path), cachePolicy: .reloadIgnoringLocalCacheData)
            request.setValue("application/json", forHTTPHeaderField: "Accept")
            request.setValue(reported.userID, forHTTPHeaderField: "X-Expected-User")
            for (name, value) in HTTPCookie.requestHeaderFields(with: cookies.filter { SessionVerifier.matches($0, path: "/" + path) }) {
                request.setValue(value, forHTTPHeaderField: name)
            }
            return request
        }
        let (identity, response) = try await NativeResponse.data(session: client.session, request: request("v1/me"))
        guard let http = response as? HTTPURLResponse, origin.owns(http.url), http.statusCode == 200 else {
            if (response as? HTTPURLResponse)?.statusCode == 401 || (response as? HTTPURLResponse)?.statusCode == 403 {
                throw NativeError.code("not_authenticated")
            }
            throw NativeError.code("network_unavailable")
        }
        try SessionVerifier.validate(identity, reported: reported)
        var acceptedFormats: [String] = []
        if requireReady {
            let (bytes, readiness) = try await NativeResponse.data(session: client.session, request: request("v1/ambient-audio"), maximumBytes: 131_072)
            guard let ready = readiness as? HTTPURLResponse, origin.owns(ready.url), ready.statusCode == 200 else {
                if (readiness as? HTTPURLResponse)?.statusCode == 401 || (readiness as? HTTPURLResponse)?.statusCode == 403 {
                    throw NativeError.code("not_authenticated")
                }
                throw NativeError.code("server_rejected")
            }
            acceptedFormats = try formats(bytes)
        }
        let (bytes, csrfResponse) = try await NativeResponse.data(session: client.session, request: request("v1/auth/csrf"))
        guard let csrfHTTP = csrfResponse as? HTTPURLResponse, origin.owns(csrfHTTP.url), csrfHTTP.statusCode == 200,
              let object = try JSONSerialization.jsonObject(with: bytes) as? [String: Any],
              let token = object["token"] as? String, !token.isEmpty, token.utf8.count <= 1_024,
              !token.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else {
            if (csrfResponse as? HTTPURLResponse)?.statusCode == 401 || (csrfResponse as? HTTPURLResponse)?.statusCode == 403 {
                throw NativeError.code("not_authenticated")
            }
            throw NativeError.code("network_unavailable")
        }
        let uploadCookies = cookies.filter { SessionVerifier.matches($0, path: "/v1/ambient-audio/batches") }
        guard let cookie = HTTPCookie.requestHeaderFields(with: uploadCookies)["Cookie"], !cookie.isEmpty else {
            throw NativeError.code("not_authenticated")
        }
        return Credentials(cookie: cookie, csrf: token, acceptedFormats: acceptedFormats)
    }

    static func formats(_ data: Data) throws -> [String] {
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let formats = object["accepted_formats"] as? [String], formats.count <= 32,
              formats.allSatisfy({ !$0.isEmpty && $0.utf8.count <= 64 }) else {
            throw NativeError.code("codec_unavailable")
        }
        return formats
    }

    func upload(_ request: URLRequest, file: URL) async throws -> (Data, HTTPURLResponse) {
        try await withCheckedThrowingContinuation { continuation in
            let task = session.uploadTask(with: request, fromFile: file)
            lock.lock(); pending[task.taskIdentifier] = Pending(continuation: continuation); lock.unlock()
            task.resume()
        }
    }

    static func uploadRequest(batch: AmbientBatch, origin: ShellOrigin, credentials: Credentials) -> URLRequest {
        var request = URLRequest(url: origin.url.appendingPathComponent("v1/ambient-audio/batches"))
        request.httpMethod = "POST"
        request.setValue(batch.format.mimeType, forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(credentials.cookie, forHTTPHeaderField: "Cookie")
        request.setValue(credentials.csrf, forHTTPHeaderField: "X-CSRF-Token")
        request.setValue(origin.canonical, forHTTPHeaderField: "Origin")
        request.setValue(batch.binding.userID, forHTTPHeaderField: "X-Expected-User")
        request.setValue(batch.id, forHTTPHeaderField: "X-Audio-Batch-ID")
        request.setValue(batch.sessionID, forHTTPHeaderField: "X-Audio-Session-ID")
        request.setValue(String(batch.sequence), forHTTPHeaderField: "X-Audio-Sequence")
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        request.setValue(formatter.string(from: batch.startedAt), forHTTPHeaderField: "X-Audio-Started-At")
        request.setValue(batch.sha256, forHTTPHeaderField: "X-Audio-SHA256")
        return request
    }

    func cancel() { session.invalidateAndCancel() }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        if var item = pending[dataTask.taskIdentifier] {
            if item.data.count + data.count > 16_384 { item.tooLarge = true } else { item.data.append(data) }
            pending[dataTask.taskIdentifier] = item
            let cancel = item.tooLarge
            lock.unlock()
            if cancel { dataTask.cancel() }
        } else { lock.unlock() }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock(); let item = pending.removeValue(forKey: task.taskIdentifier); lock.unlock()
        guard let item else { return }
        if item.tooLarge { item.continuation.resume(throwing: NativeError.code("invalid_receipt")) }
        else if let error { item.continuation.resume(throwing: error) }
        else if let response = task.response as? HTTPURLResponse { item.continuation.resume(returning: (item.data, response)) }
        else { item.continuation.resume(throwing: NativeError.code("network_unavailable")) }
    }
}
