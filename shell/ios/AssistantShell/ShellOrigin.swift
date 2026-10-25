import Foundation

/// The assistant's configured origin; page ownership also checks its route on a shared host.
struct ShellOrigin: Equatable {
    let scheme: String
    let host: String
    let port: Int?

    /// HTTPS only, except loopback HTTP in a debug build; never credentials, a path, query or fragment.
    init?(_ value: String, allowLoopbackHTTP: Bool) {
        guard let parts = URLComponents(string: value.hasSuffix("/") ? String(value.dropLast()) : value),
              let scheme = parts.scheme?.lowercased(), let host = parts.host?.lowercased(), !host.isEmpty,
              parts.user == nil, parts.password == nil, parts.path.isEmpty,
              parts.query == nil, parts.fragment == nil,
              parts.port.map({ (1...65535).contains($0) }) ?? true
        else { return nil }
        let loopback = host == "127.0.0.1" || host == "localhost"
        guard scheme == "https" || (allowLoopbackHTTP && scheme == "http" && loopback) else { return nil }
        self.scheme = scheme
        self.host = host
        self.port = parts.port == (scheme == "https" ? 443 : 80) ? nil : parts.port
    }

    var url: URL {
        URL(string: "\(scheme)://\(host)\(port.map { ":\($0)" } ?? "")/")!
    }

    var canonical: String { String(url.absoluteString.dropLast()) }

    func owns(_ url: URL?) -> Bool {
        guard let url, let scheme = url.scheme, let host = url.host else { return false }
        return owns(scheme: scheme, host: host, port: url.port ?? 0)
    }

    /// Other services on the same origin stay outside the shell's page and permission boundary.
    func ownsPage(_ url: URL?) -> Bool {
        guard owns(url), let url, let parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              parts.user == nil, parts.password == nil,
              !parts.path.contains("\\"),
              !parts.path.split(separator: "/").contains(where: { $0 == "." || $0 == ".." })
        else { return false }
        let path = parts.percentEncodedPath
        guard path.hasPrefix("/") else { return false }
        let prefix = path.dropFirst().split(separator: "/", omittingEmptySubsequences: false).first ?? ""
        return Self.pages.contains(String(prefix))
    }

    /// A WebKit security origin reports the default port as 0.
    func owns(scheme: String, host: String, port: Int) -> Bool {
        let scheme = scheme.lowercased()
        return scheme == self.scheme && host.lowercased() == self.host
            && Self.effective(port == 0 ? nil : port, scheme) == Self.effective(self.port, self.scheme)
    }

    private static func effective(_ port: Int?, _ scheme: String) -> Int {
        port ?? (scheme == "https" ? 443 : 80)
    }

    private static let pages: Set<String> = [
        "assistant", "today", "recordings", "food", "tasks", "timers", "notifications", "circle",
        "activity", "personal", "settings", "admin", "accounts", "workers", "plugins", "welcome",
        "no-access", "login", "account-deletion", "_ui", "app", "chat", "community", "modules", "places",
    ]
}
