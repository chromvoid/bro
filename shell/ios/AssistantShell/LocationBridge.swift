import CoreLocation
import Foundation

/// The page's position through the shell (docs/location-weather-spec.md §3.1): one system dialog,
/// never the second prompt WKWebView would show for `navigator.geolocation`. Only `location.request`
/// may ask; `location.status` and `location.current` never do.
@MainActor
final class LocationBridge: NSObject, CLLocationManagerDelegate {
    static let shared = LocationBridge()

    /// What the delegate hands to the main actor: plain values, not the system's objects.
    struct Point: Sendable {
        let latitude: Double, longitude: Double, accuracy: Double, timestamp: Date
        init(_ location: CLLocation) {
            latitude = location.coordinate.latitude
            longitude = location.coordinate.longitude
            accuracy = location.horizontalAccuracy
            timestamp = location.timestamp
        }
    }

    private let manager = CLLocationManager()
    private var authorization: [CheckedContinuation<Void, Never>] = []
    private var fixes: [UUID: CheckedContinuation<Point, Error>] = [:]

    override init() {
        super.init()
        manager.delegate = self
        // Ordinary accuracy is enough for the weather and the district.
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    func handle(_ request: BridgeProtocol.Request) async throws -> [String: Any] {
        switch request.type {
        case "location.status": return ["permission": permission]
        case "location.request":
            if manager.authorizationStatus == .notDetermined {
                await withCheckedContinuation { authorization.append($0); manager.requestWhenInUseAuthorization() }
            }
            return ["permission": permission]
        case "location.current":
            guard permission == "granted" else { throw NativeError.code("denied") }
            let point: Point
            if let recent = manager.location, -recent.timestamp.timeIntervalSinceNow < 600 {
                point = Point(recent)
            } else {
                point = try await current()
            }
            return BridgeProtocol.fix(latitude: point.latitude, longitude: point.longitude,
                                      accuracy: point.accuracy, timestamp: point.timestamp)
        default: throw NativeError.code("unknown_type")
        }
    }

    var permission: String {
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways: return "granted"
        case .denied, .restricted: return "denied"
        default: return "prompt"
        }
    }

    /// One fix within 15 s; a request that times out is answered alone, others keep waiting.
    private func current() async throws -> Point {
        let id = UUID()
        return try await withCheckedThrowingContinuation { continuation in
            fixes[id] = continuation
            manager.requestLocation()
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                self?.fixes.removeValue(forKey: id)?.resume(throwing: NativeError.code("timeout"))
            }
        }
    }

    private func answer(_ result: Result<Point, Error>) {
        let waiting = fixes.values
        fixes = [:]
        waiting.forEach { $0.resume(with: result) }
    }

    private func authorizationChanged() {
        guard manager.authorizationStatus != .notDetermined else { return }
        let waiting = authorization
        authorization = []
        waiting.forEach { $0.resume() }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in self.authorizationChanged() }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        let point = Point(location)
        Task { @MainActor in self.answer(.success(point)) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in self.answer(.failure(NativeError.code("unavailable"))) }
    }
}
