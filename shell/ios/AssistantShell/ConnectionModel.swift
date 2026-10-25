import Foundation
import SwiftUI
import WebKit

enum SupportContact {
    static func httpsURL(_ value: String?) -> URL? {
        guard let value, !value.isEmpty, value.utf8.count <= 2_048,
              value.rangeOfCharacter(from: .whitespacesAndNewlines.union(.controlCharacters)) == nil,
              let parts = URLComponents(string: value), parts.scheme?.lowercased() == "https",
              let host = parts.host, !host.isEmpty, parts.user == nil, parts.password == nil,
              parts.port.map({ (1...65_535).contains($0) }) ?? true else { return nil }
        return parts.url
    }

    /// Bundled publisher contact remains available without an installation or network response.
    static var publisherURL: URL? {
        httpsURL(Bundle.main.object(forInfoDictionaryKey: "AssistantPublisherSupport") as? String)
    }
}

enum NativeResponse {
    /// Configuration and identity responses come from a user-selected server. Enforce the
    /// limit while reading, including chunked responses, rather than after allocation.
    static func data(session: URLSession, request: URLRequest, maximumBytes: Int = 16_384) async throws -> (Data, URLResponse) {
        let (stream, response) = try await session.bytes(for: request)
        defer { stream.task.cancel() }
        guard response.expectedContentLength <= Int64(maximumBytes) else { throw URLError(.dataLengthExceedsMaximum) }
        var data = Data()
        data.reserveCapacity(min(maximumBytes, max(0, Int(response.expectedContentLength))))
        for try await byte in stream {
            guard data.count < maximumBytes else { throw URLError(.dataLengthExceedsMaximum) }
            data.append(byte)
        }
        return (data, response)
    }
}

struct ClientConfiguration: Codable, Equatable {
    struct Capabilities: Codable, Equatable {
        let notifications: Bool
        let calls: Bool
        let account_deletion: Bool
    }
    let instance_id: String
    let name: String
    let client_api: Int
    let shell_api: Int
    let entry_path: String
    let capabilities: Capabilities
    var support_url: String? = nil
    var privacy_url: String? = nil
    var providers: [String]? = nil

    var isCompatible: Bool {
        UUID(uuidString: instance_id) != nil && !name.isEmpty && name.count <= 200
            && client_api == 1 && shell_api == 1 && entry_path == "/assistant"
            && capabilities.notifications && capabilities.calls && capabilities.account_deletion
            && (providers ?? []).count <= 30 && (providers ?? []).allSatisfy { !$0.isEmpty && $0.count <= 100 }
    }
}

struct AssistantConnection: Codable, Equatable {
    let id: String
    let address: String
    let configuration: ClientConfiguration
    var origin: ShellOrigin { ShellOrigin(address, allowLoopbackHTTP: Self.debugHTTP)! }

    static var debugHTTP: Bool {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }
}

enum ConnectionFailure: LocalizedError {
    case invalidAddress, incompatible, redirect, changedInstance, response
    var errorDescription: String? {
        switch self {
        case .invalidAddress: return "Укажите HTTPS-адрес сервера без пути, логина и пароля."
        case .incompatible: return "Сервер не поддерживает эту версию приложения. Обновите сервер ассистента."
        case .redirect: return "Сервер перенаправил запрос на другой адрес. Укажите конечный адрес сервера."
        case .changedInstance: return "По этому адресу находится другая установка. Подключите её заново."
        case .response: return "Сервер не ответил как установка ассистента. Проверьте адрес."
        }
    }
}

final class ConfigurationLoader: NSObject, URLSessionTaskDelegate {
    private let origin: ShellOrigin
    init(origin: ShellOrigin) { self.origin = origin }
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        // A connection is a deliberate choice of exactly one origin, including its port.
        completionHandler(origin.owns(request.url) ? request : nil)
    }

    func load() async throws -> ClientConfiguration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.urlCache = nil
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 20
        let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let url = origin.url.appendingPathComponent("v1/client/config")
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await NativeResponse.data(session: session, request: request)
        guard let http = response as? HTTPURLResponse else { throw ConnectionFailure.response }
        if (300..<400).contains(http.statusCode) { throw ConnectionFailure.redirect }
        guard http.statusCode == 200, origin.owns(http.url), data.count <= 16_384 else {
            throw ConnectionFailure.response
        }
        let result = try JSONDecoder().decode(ClientConfiguration.self, from: data)
        guard result.isCompatible else { throw ConnectionFailure.incompatible }
        return result
    }
}

@MainActor final class ConnectionModel: ObservableObject {
    @Published private(set) var active: AssistantConnection?
    @Published private(set) var busy = false
    @Published var error: String?
    @Published var showSettings = false
    @Published private(set) var candidate: AssistantConnection?
    private let defaults: UserDefaults
    private var generation = UUID()
    private var busyOwner: UUID?
    private static let savedKey = "assistant.connection.v1"

    init(defaults: UserDefaults = .standard) { self.defaults = defaults }
    var savedAddress: String {
        saved()?.address ?? (Bundle.main.object(forInfoDictionaryKey: "AssistantOrigin") as? String ?? "")
    }
    private func saved() -> AssistantConnection? {
        guard let data = defaults.data(forKey: Self.savedKey),
              let value = try? JSONDecoder().decode(AssistantConnection.self, from: data),
              ShellOrigin(value.address, allowLoopbackHTTP: AssistantConnection.debugHTTP) != nil,
              value.configuration.isCompatible else { return nil }
        return value
    }

    func restore() async {
        guard active == nil, !busy, let value = saved() else { return }
        await connect(value.address, restoring: value)
    }

    func clearCandidate() { candidate = nil }
    func checkAddress(_ value: String) async {
        guard !busy else { return }
        guard let origin = ShellOrigin(value.trimmingCharacters(in: .whitespacesAndNewlines),
                                       allowLoopbackHTTP: AssistantConnection.debugHTTP) else {
            error = ConnectionFailure.invalidAddress.localizedDescription; return
        }
        busy = true; error = nil; candidate = nil
        let ticket = generation
        busyOwner = ticket
        defer { finishBusy(ticket) }
        do {
            let configuration = try await ConfigurationLoader(origin: origin).load()
            guard ticket == generation else { return }
            candidate = AssistantConnection(id: UUID().uuidString, address: origin.canonical, configuration: configuration)
        } catch { if ticket == generation { self.error = Self.describe(error) } }
    }

    func confirmConnection() async {
        guard let candidate else { return }
        await connect(candidate.address, expected: candidate.configuration)
    }

    func connect(_ value: String, restoring: AssistantConnection? = nil, expected: ClientConfiguration? = nil) async {
        guard !busy else { return }
        guard let origin = ShellOrigin(value.trimmingCharacters(in: .whitespacesAndNewlines),
                                       allowLoopbackHTTP: AssistantConnection.debugHTTP) else {
            error = ConnectionFailure.invalidAddress.localizedDescription
            return
        }
        busy = true
        error = nil
        let ticket = generation
        busyOwner = ticket
        defer { finishBusy(ticket) }
        do {
            let configuration = try await ConfigurationLoader(origin: origin).load()
            guard generation == ticket else { return }
            if let expected, expected != configuration {
                candidate = AssistantConnection(id: UUID().uuidString, address: origin.canonical, configuration: configuration)
                error = "Настройки сервера изменились. Проверьте сведения и подтвердите подключение снова."
                return
            }
            if let restoring, restoring.configuration.instance_id != configuration.instance_id {
                let reset = UUID()
                generation = reset; busyOwner = reset
                await clearConnection()
                guard generation == reset else { return }
                error = ConnectionFailure.changedInstance.localizedDescription
                finishBusy(reset)
                return
            }
            if restoring == nil {
                active = nil // Dismantle the old bridge before erasing its cookies or keys.
                AmbientAudio.shared.detach()
                await NativeNotifications.shared.detach()
                await Self.clearWebsiteData()
            }
            guard generation == ticket else { return }
            let connection = AssistantConnection(id: restoring?.id ?? UUID().uuidString,
                                                 address: origin.canonical, configuration: configuration)
            defaults.set(try JSONEncoder().encode(connection), forKey: Self.savedKey)
            NativeNotifications.shared.attach(connection)
            AmbientAudio.shared.attach(connection)
            active = connection
            candidate = nil
            showSettings = false
        } catch {
            if generation == ticket { self.error = Self.describe(error) }
        }
    }

    func disconnect() async {
        let ticket = UUID()
        generation = ticket; busyOwner = ticket; busy = true
        defer { finishBusy(ticket) }
        await clearConnection()
    }

    private func finishBusy(_ ticket: UUID) {
        if busyOwner == ticket { busyOwner = nil; busy = false }
    }

    private func clearConnection() async {
        active = nil
        candidate = nil
        defaults.removeObject(forKey: Self.savedKey)
        AmbientAudio.shared.detach()
        await NativeNotifications.shared.detach()
        await Self.clearWebsiteData()
        UIApplication.shared.isIdleTimerDisabled = false
    }

    static func clearWebsiteData() async {
        await withCheckedContinuation { continuation in
            WKWebsiteDataStore.default().removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(),
                                                    modifiedSince: .distantPast) { continuation.resume() }
        }
    }

    static func describe(_ error: Error) -> String {
        guard let urlError = error as? URLError else { return error.localizedDescription }
        switch urlError.code {
        case .serverCertificateUntrusted, .serverCertificateHasBadDate, .serverCertificateHasUnknownRoot,
             .serverCertificateNotYetValid, .secureConnectionFailed:
            return "Не удалось проверить HTTPS-сертификат сервера. Проверьте сертификат и адрес."
        case .cannotFindHost, .dnsLookupFailed:
            return "Адрес сервера не найден. Для частного адреса проверьте подключение к VPN."
        case .notConnectedToInternet, .cannotConnectToHost, .timedOut:
            return "Сервер недоступен. Проверьте интернет и, для частного сервера, VPN."
        default: return "Не удалось подключиться к серверу. Попробуйте снова."
        }
    }
}

struct ConnectionScreen: View {
    @ObservedObject var model: ConnectionModel
    @State private var address = ""
    @State private var confirmingDisconnect = false
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Подключите свою установку ассистента. Сообщения и личные данные хранятся на выбранном вами сервере.")
                    TextField("https://assistant.example.com", text: $address)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .keyboardType(.URL).textContentType(.URL)
                    Button("Проверить сервер") {
                        Task { await model.checkAddress(address) }
                    }.disabled(model.busy || address.isEmpty)
                    if model.busy { ProgressView("Проверяем сервер…") }
                    if let error = model.error { Text(error).foregroundStyle(KaiColors.destructive) }
                } header: { Text("Адрес сервера") }
                .listRowBackground(KaiColors.surface)
                if let candidate = model.candidate {
                    Section {
                        Text(candidate.configuration.name).font(.headline)
                        Text(candidate.address).font(.footnote)
                        Text("Сообщения, файлы и аудио отправляются на выбранный сервер. Его оператор определяет настройки и подключённых провайдеров.")
                        let providers = candidate.configuration.providers ?? []
                        if !providers.isEmpty {
                            Text("Настроенные провайдеры: " + providers.joined(separator: ", ") + ". При их использовании сервер передаёт им нужные данные.")
                        }
                        if let url = SupportContact.httpsURL(candidate.configuration.privacy_url) { Link("Политика приватности сервера", destination: url) }
                        if let url = SupportContact.httpsURL(candidate.configuration.support_url) { Link("Администратор этой установки", destination: url) }
                        Button(model.active == nil ? "Подключиться" : "Подключить другой сервер") {
                            Task { await model.confirmConnection() }
                        }.disabled(model.busy)
                    } header: { Text("Подтвердите подключение") }
                    .listRowBackground(KaiColors.surface)
                }
                Section {
                    Text("Обычный HTTPS-сервер работает через интернет. Для сервера в домашней сети или Tailscale сначала подключитесь к нужной сети или VPN. Приложение не настраивает VPN.")
                }
                .listRowBackground(KaiColors.surface)
                if let active = model.active {
                    Section {
                        Text(active.configuration.name)
                        Text(active.address).font(.footnote)
                        if let url = SupportContact.httpsURL(active.configuration.support_url) { Link("Администратор этой установки", destination: url) }
                        if let url = SupportContact.httpsURL(active.configuration.privacy_url) { Link("Политика приватности сервера", destination: url) }
                        Button("Отключить сервер", role: .destructive) { confirmingDisconnect = true }
                    } footer: { Text("Смена сервера завершит вход и удалит уведомления этой установки с телефона.") }
                    .listRowBackground(KaiColors.surface)
                }
                if let url = SupportContact.publisherURL {
                    Section {
                        Link("Поддержка издателя приложения", destination: url)
                    } footer: {
                        Text("Обратитесь по вопросам приложения или если администратор установки недоступен. Издатель не имеет доступа к данным вашего сервера.")
                    }
                    .listRowBackground(KaiColors.surface)
                }
            }
            .scrollContentBackground(.hidden)
            .background(KaiColors.background)
            .navigationTitle("Сервер ассистента")
            .toolbarBackground(KaiColors.background, for: .navigationBar)
            .toolbar {
                if model.active != nil {
                    ToolbarItem(placement: .cancellationAction) { Button("Закрыть") { model.showSettings = false } }
                }
            }
            .confirmationDialog("Отключить сервер и удалить его данные с телефона?", isPresented: $confirmingDisconnect,
                                titleVisibility: .visible) {
                Button("Отключить", role: .destructive) { Task { await model.disconnect(); model.showSettings = false } }
            }
            .onAppear { address = model.savedAddress }
            .onChange(of: address) { _ in model.clearCandidate() }
        }
        .tint(KaiColors.accent)
    }
}
