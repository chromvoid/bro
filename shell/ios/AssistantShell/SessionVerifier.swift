import Foundation
import WebKit

/// Confirm web-reported identity against the selected server's authenticated API. Cookies stay
/// within this origin; they are never forwarded to the publisher relay or a redirected URL.
final class SessionVerifier: NSObject, URLSessionTaskDelegate {
    static func validate(_ bytes: Data, reported: NativeSession) throws {
        guard bytes.count <= 16_384,
              let object = try JSONSerialization.jsonObject(with: bytes) as? [String: Any],
              object["id"] as? String == reported.userID, object["device_id"] as? String == reported.deviceID,
              let value = object["session_expires_at"] as? String, let expiry = NativeSchedule.date(value),
              reported.expires <= expiry, expiry > Date() else { throw NativeError.code("invalid_session") }
    }

    static func matches(_ cookie: HTTPCookie, path: String) -> Bool {
        cookie.path == "/" || path == cookie.path || path.hasPrefix(cookie.path.hasSuffix("/") ? cookie.path : cookie.path + "/")
    }

    @MainActor static func cookies(origin: ShellOrigin) async -> [HTTPCookie] {
        let cookies: [HTTPCookie] = await withCheckedContinuation { continuation in
            WKWebsiteDataStore.default().httpCookieStore.getAllCookies { continuation.resume(returning: $0) }
        }
        return cookies.filter { cookie in
            let domain = cookie.domain.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
            return (origin.host == domain || origin.host.hasSuffix("." + domain))
                && (!cookie.isSecure || origin.scheme == "https")
                && (cookie.expiresDate == nil || cookie.expiresDate! > Date())
        }
    }

    @MainActor func verify(_ reported: NativeSession, origin: ShellOrigin) async throws {
        let allowed = await Self.cookies(origin: origin).filter { Self.matches($0, path: "/v1/me") }
        let options = URLSessionConfiguration.ephemeral
        options.httpCookieStorage = nil; options.httpShouldSetCookies = false
        options.urlCredentialStorage = nil; options.urlCache = nil
        options.timeoutIntervalForRequest = 15; options.timeoutIntervalForResource = 20
        let session = URLSession(configuration: options, delegate: self, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        var request = URLRequest(url: origin.url.appendingPathComponent("v1/me"), cachePolicy: .reloadIgnoringLocalCacheData)
        for (name, value) in HTTPCookie.requestHeaderFields(with: allowed) { request.setValue(value, forHTTPHeaderField: name) }
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (bytes, response) = try await NativeResponse.data(session: session, request: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200, origin.owns(http.url) else {
            throw NativeError.code("not_authenticated")
        }
        try Self.validate(bytes, reported: reported)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
