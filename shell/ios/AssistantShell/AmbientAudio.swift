import AVFoundation
import Foundation
import SwiftUI
import UIKit

/// Capture, bounded local persistence and delivery belong to the app, not to a page or its agent.
/// Identity messages revoke authority immediately, but only /v1/me can grant it again.
@MainActor final class AmbientAudio: ObservableObject {
    static let shared = AmbientAudio()
    @Published private(set) var recording = false
    @Published private(set) var queuedBatches = 0
    @Published private(set) var queuedBytes = 0
    @Published private(set) var lastUploadedAt: Date?
    @Published private(set) var error: String?
    @Published private(set) var starting = false
    @Published var showScreen = false
    var eventSink: ((String, [String: Any]) -> Void)?
    private var connection: AssistantConnection?
    private var reported: NativeSession?
    private var generation = UUID()
    private var pendingStart: UUID?
    private var accountAttempt = UUID()
    private var uploadID: UUID?
    private var finalizations: Set<UUID> = []
    private var recorder: AmbientRecorder?
    private var store: AmbientAudioStore?
    private var transport = AmbientTransport()
    private var uploading: Task<Void, Never>?
    private var retryTimer: Timer?
    private var expiryTimer: Timer?
    private var observations: [NSObjectProtocol] = []
    private var webMicrophoneInUse = false
    private var needsLogin = false
    private var terminalFailure = false
    private var nextRetry = Date.distantPast
    private var retryDelay = 5.0
    private var backgroundTask = UIBackgroundTaskIdentifier.invalid
    private let root: URL
    typealias PermissionLoader = @MainActor () async -> Bool
    typealias CredentialsLoader = @MainActor (NativeSession, ShellOrigin, Bool) async throws -> AmbientTransport.Credentials
    typealias VADLoader = @MainActor () async throws -> AmbientVAD
    private let permissionLoader: PermissionLoader
    private let credentialsLoader: CredentialsLoader
    private let vadLoader: VADLoader
    private let automaticallyUpload: Bool

    init(root: URL? = nil, activateTimers: Bool = true, automaticallyUpload: Bool = true,
         permissionLoader: @escaping PermissionLoader = AmbientAudio.microphonePermission,
         credentialsLoader: @escaping CredentialsLoader = { reported, origin, ready in
             try await AmbientTransport.credentials(reported, origin: origin, requireReady: ready)
         }, vadLoader: @escaping VADLoader = {
             try await Task.detached(priority: .userInitiated) { try AmbientVAD() }.value
         }) {
        self.root = root ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("AmbientAudio", isDirectory: true)
        self.permissionLoader = permissionLoader
        self.credentialsLoader = credentialsLoader
        self.vadLoader = vadLoader
        self.automaticallyUpload = automaticallyUpload
        do { store = try AmbientAudioStore(root: self.root) } catch { self.error = "queue_corrupt" }
        refresh()
        guard activateTimers else { return }
        retryTimer = Timer.scheduledTimer(withTimeInterval: 15, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.checkExpiry(); self?.pump() }
        }
        observations.append(NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] notification in
                guard let raw = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                      raw == AVAudioSession.InterruptionType.began.rawValue else { return }
                Task { @MainActor in self?.stop(reason: "interrupted") }
            })
        observations.append(NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] notification in
                guard let raw = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                      raw == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue
                        || raw == AVAudioSession.RouteChangeReason.newDeviceAvailable.rawValue else { return }
                Task { @MainActor in self?.stop(reason: "interrupted") }
            })
        observations.append(NotificationCenter.default.addObserver(
            forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.stop(reason: "interrupted") }
            })
    }

    private static func microphonePermission() async -> Bool {
        if #available(iOS 17.0, *) { return await AVAudioApplication.requestRecordPermission() }
        return await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { continuation.resume(returning: $0) }
        }
    }

    static func iso(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: date)
    }

    var state: [String: Any] {
        ["recording": recording, "queued_batches": queuedBatches, "queued_bytes": queuedBytes,
         "last_uploaded_at": lastUploadedAt.map(Self.iso) as Any? ?? NSNull(), "error": error as Any? ?? NSNull()]
    }

    private func refresh() {
        queuedBatches = store?.batches.count ?? 0
        queuedBytes = store?.queuedBytes ?? 0
        eventSink?("ambient.state", state)
    }

    func accepts(connectionID: String) -> Bool { connection?.id == connectionID }

    func attach(_ value: AssistantConnection) {
        if let current = connection,
           current.address != value.address || current.configuration.instance_id != value.configuration.instance_id {
            invalidate(purge: true)
        }
        if let saved = store?.binding,
           saved.origin != value.address || saved.instanceID != value.configuration.instance_id {
            invalidate(purge: true)
        }
        connection = value
        if reported == nil, let saved = store?.binding, saved.expires > Date() {
            reported = NativeSession(instanceID: saved.instanceID, userID: saved.userID, deviceID: saved.deviceID,
                                     expires: saved.expires, previews: false)
            scheduleExpiry(saved.expires)
        }
        refresh()
        pump()
    }

    /// Revocation is synchronous before WebView cookie removal or any network suspension.
    func detach() {
        connection = nil; reported = nil; eventSink = nil; webMicrophoneInUse = false
        invalidate(purge: true)
    }

    private func invalidate(purge: Bool) {
        generation = UUID()
        pendingStart = nil; accountAttempt = UUID(); uploadID = nil
        finalizations.removeAll()
        starting = false
        recorder?.stop(discard: true); recorder = nil; recording = false
        uploading?.cancel(); uploading = nil
        transport.cancel(); transport = AmbientTransport()
        expiryTimer?.invalidate(); expiryTimer = nil
        finishBackgroundTask()
        if purge {
            do {
                if let store { try store.clear() }
                else {
                    if FileManager.default.fileExists(atPath: root.path) { try FileManager.default.removeItem(at: root) }
                    store = try AmbientAudioStore(root: root)
                }
            } catch { store = nil; self.error = "queue_corrupt" }
            lastUploadedAt = nil
        }
        needsLogin = false; terminalFailure = false; nextRetry = .distantPast; retryDelay = 5
        refresh()
    }

    func handle(_ request: BridgeProtocol.Request, connectionID: String) async throws -> [String: Any] {
        guard accepts(connectionID: connectionID) else { throw NativeError.code("not_connected") }
        switch request.type {
        case "ambient.open": showScreen = true; return state
        case "ambient.state": checkExpiry(); return state
        case "ambient.stop": stop(); return state
        case "ambient.clear": reported = nil; clear(); return state
        case "ambient.account":
            if request.payload["user_id"] is NSNull || BridgeProtocol.boolean(request.payload["clear"]) == true {
                let preserve = BridgeProtocol.boolean(request.payload["preserve_queue"]) == true
                reported = nil; invalidate(purge: !preserve)
                needsLogin = preserve; error = preserve ? "not_authenticated" : nil
                refresh(); return state
            }
            guard let connection else { throw NativeError.code("not_connected") }
            var payload = request.payload
            payload["instance_id"] = connection.configuration.instance_id
            let next: NativeSession
            do { next = try NativeSession.parse(payload, connection: connection) }
            catch { reported = nil; invalidate(purge: true); self.error = "not_authenticated"; refresh(); throw error }
            let current = reported
            let old = store?.binding
            if current.map({ $0.userID != next.userID || $0.deviceID != next.deviceID }) == true
                || old.map({ $0.userID != next.userID || $0.deviceID != next.deviceID }) == true {
                reported = nil; invalidate(purge: true)
            }
            let ticket = generation
            let attempt = UUID()
            accountAttempt = attempt
            do {
                _ = try await credentialsLoader(next, connection.origin, false)
                guard ticket == generation, attempt == accountAttempt, accepts(connectionID: connectionID) else { throw NativeError.code("session_changed") }
                let binding = AmbientBinding(origin: connection.address, instanceID: next.instanceID,
                                             userID: next.userID, deviceID: next.deviceID, expires: next.expires)
                guard let store else { throw NativeError.code("queue_corrupt") }
                try store.bind(binding)
                reported = next; needsLogin = false
                scheduleExpiry(next.expires)
                if error == "not_authenticated" { error = nil }
                refresh(); pump(); return state
            } catch {
                guard ticket == generation, attempt == accountAttempt else { throw NativeError.code("session_changed") }
                let code = Self.code(error)
                if code == "not_authenticated" { requireLogin() }
                throw NativeError.code(code)
            }
        default: throw NativeError.code("unknown_type")
        }
    }

    func start() async {
        guard !recording, !starting else { return }
        guard let connection, let reported, reported.expires > Date(), !needsLogin else {
            error = self.connection == nil ? "not_connected" : "not_authenticated"; refresh(); return
        }
        guard !webMicrophoneInUse else { error = "microphone_busy"; refresh(); return }
        guard let store else { error = "queue_corrupt"; refresh(); return }
        // Reserve a full segment plus the final partial segment before opening the microphone.
        guard store.capacityBytes <= store.maximumBytes - 2 * (AmbientAudioStore.compressedMaximumBatchBytes + 16_384) else {
            error = "queue_full"; refresh(); return
        }
        let ticket = generation
        let startID = UUID()
        pendingStart = startID
        starting = true; error = nil; refresh()
        defer { if ticket == generation, pendingStart == startID { pendingStart = nil; starting = false; refresh() } }
        do {
            let granted = await permissionLoader()
            guard ticket == generation, pendingStart == startID else { return }
            guard granted else { throw NativeError.code("permission_denied") }
            let credentials = try await credentialsLoader(reported, connection.origin, true)
            guard ticket == generation, pendingStart == startID else { return }
            guard accepts(connectionID: connection.id), self.reported == reported, reported.expires > Date(), !needsLogin else {
                throw NativeError.code("not_authenticated")
            }
            guard !webMicrophoneInUse else { throw NativeError.code("microphone_busy") }
            let format = try AmbientCodec.preferredFormat(accepted: credentials.acceptedFormats)
            // Model validation and session initialization can be expensive; keep native Stop and
            // account revocation responsive, then check their generation before opening the mic.
            let vad = try await vadLoader()
            guard ticket == generation, pendingStart == startID else { return }
            guard self.reported == reported, reported.expires > Date(), !needsLogin else {
                throw NativeError.code("not_authenticated")
            }
            guard !webMicrophoneInUse else { throw NativeError.code("microphone_busy") }
            try store.bind(AmbientBinding(origin: connection.address, instanceID: reported.instanceID,
                                          userID: reported.userID, deviceID: reported.deviceID, expires: reported.expires))
            terminalFailure = false
            let capture = AmbientRecorder(format: format, vad: vad, segment: { [weak self] encoded, sessionID, sequence, date in
                Task { @MainActor in
                    guard let self, ticket == self.generation else { return }
                    self.save(encoded, sessionID: sessionID, sequence: sequence, startedAt: date)
                }
            }, failure: { [weak self] reason in
                Task { @MainActor in
                    guard let self, ticket == self.generation else { return }
                    self.stop(reason: reason)
                }
            })
            do { try capture.start() } catch { throw NativeError.code("capture_failed") }
            recorder = capture; recording = true
            scheduleExpiry(reported.expires)
            refresh(); pump()
        } catch {
            guard ticket == generation, pendingStart == startID else { return }
            self.error = Self.code(error)
            if self.error == "not_authenticated" { requireLogin() }
            refresh()
        }
    }

    private func save(_ encoded: AmbientCodec.Encoded, sessionID: String, sequence: Int, startedAt: Date) {
        guard let store, store.binding != nil else { return }
        do {
            _ = try store.enqueue(encoded: encoded.data, format: encoded.format, sessionID: sessionID,
                                  sequence: sequence, startedAt: startedAt, frames: encoded.frames)
            refresh()
            if store.capacityBytes > store.maximumBytes - 2 * (AmbientAudioStore.compressedMaximumBatchBytes + 16_384) {
                stop(reason: "queue_full")
            }
            checkExpiry(); pump()
        } catch {
            if case AmbientStoreError.full = error { stop(reason: "queue_full") }
            else { stop(reason: "queue_corrupt") }
        }
    }

    func stop(reason: String? = nil) {
        let wasStarting = starting
        pendingStart = nil; starting = false
        let capture = recorder
        recorder = nil; recording = false
        if let reason, capture != nil || wasStarting { error = reason }
        if capture != nil {
            let ticket = generation
            let finalization = UUID()
            finalizations.insert(finalization)
            beginBackgroundTask()
            capture?.stop(completion: { [weak self] in
                Task { @MainActor in
                    guard let self, ticket == self.generation else { return }
                    self.finalizations.remove(finalization)
                    self.pump()
                }
            })
        }
        refresh(); pump()
    }

    func clear() {
        invalidate(purge: true)
        if store == nil {
            do {
                if FileManager.default.fileExists(atPath: root.path) { try FileManager.default.removeItem(at: root) }
                store = try AmbientAudioStore(root: root)
            } catch { self.error = "queue_corrupt"; refresh(); return }
        }
        error = nil
        refresh()
    }

    func setWebMicrophoneInUse(_ value: Bool) {
        webMicrophoneInUse = value
        if value { stop(reason: "microphone_busy") }
    }

    func foreground() { checkExpiry(); nextRetry = .distantPast; pump(); refresh() }

    private func scheduleExpiry(_ expires: Date) {
        expiryTimer?.invalidate()
        expiryTimer = Timer.scheduledTimer(withTimeInterval: max(0.01, expires.timeIntervalSinceNow), repeats: false) { [weak self] _ in
            Task { @MainActor in self?.checkExpiry() }
        }
    }

    private func checkExpiry() {
        if let reported, reported.expires <= Date(), !needsLogin { requireLogin() }
    }

    private func requireLogin() {
        needsLogin = true
        accountAttempt = UUID(); uploadID = nil
        stop(reason: "not_authenticated")
        error = "not_authenticated"
        uploading?.cancel(); uploading = nil
        transport.cancel(); transport = AmbientTransport()
        refresh()
    }

    private func pump() {
        guard automaticallyUpload, uploading == nil, !needsLogin, !terminalFailure, nextRetry <= Date(),
              let connection, let reported, reported.expires > Date(), let store,
              let binding = store.binding, binding.origin == connection.address,
              binding.instanceID == connection.configuration.instance_id,
              binding.userID == reported.userID, binding.deviceID == reported.deviceID,
              let batch = store.batches.first else {
            if uploading == nil, finalizations.isEmpty, queuedBatches == 0 || needsLogin || terminalFailure { finishBackgroundTask() }
            return
        }
        let ticket = generation
        let identifier = UUID()
        uploadID = identifier
        let transport = self.transport
        uploading = Task { [weak self] in
            guard let self else { return }
            defer {
                if ticket == generation, uploadID == identifier {
                    uploading = nil; uploadID = nil; refresh()
                    if nextRetry <= Date(), !terminalFailure, !needsLogin { pump() }
                }
            }
            do {
                let credentials = try await credentialsLoader(reported, connection.origin, false)
                guard ticket == generation, uploadID == identifier, !needsLogin, !Task.isCancelled else { return }
                guard reported.expires > Date() else { requireLogin(); return }
                let request = AmbientTransport.uploadRequest(batch: batch, origin: connection.origin, credentials: credentials)
                let (bytes, response) = try await transport.upload(request, file: store.file(batch))
                guard ticket == generation, uploadID == identifier, !needsLogin, !Task.isCancelled else { return }
                guard connection.origin.owns(response.url) else { throw NativeError.code("invalid_receipt") }
                if let code = Self.uploadFailure(response.statusCode) { throw NativeError.code(code) }
                guard AmbientAudioStore.receipt(bytes, batch: batch), try store.acknowledge(id: batch.id, sha256: batch.sha256) else {
                    throw NativeError.code("invalid_receipt")
                }
                lastUploadedAt = Date(); retryDelay = 5; nextRetry = .distantPast
                if error == "network_unavailable" { error = nil }
                refresh()
            } catch {
                guard ticket == generation, uploadID == identifier, !Task.isCancelled else { return }
                let code = Self.code(error)
                self.error = code
                if code == "not_authenticated" { requireLogin() }
                else if ["batch_expired", "invalid_receipt", "server_rejected", "queue_corrupt"].contains(code) {
                    terminalFailure = true; stop(reason: code)
                }
                else { nextRetry = Date().addingTimeInterval(retryDelay); retryDelay = min(120, retryDelay * 2) }
                refresh()
            }
        }
    }

    private func beginBackgroundTask() {
        guard backgroundTask == .invalid else { return }
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Finish ambient audio batch") { [weak self] in
            Task { @MainActor in
                self?.uploading?.cancel(); self?.uploading = nil
                self?.uploadID = nil; self?.nextRetry = Date().addingTimeInterval(15)
                self?.transport.cancel(); self?.transport = AmbientTransport()
                self?.finishBackgroundTask()
            }
        }
    }

    static func uploadFailure(_ status: Int) -> String? {
        if (200..<300).contains(status) { return nil }
        if status == 401 || status == 403 { return "not_authenticated" }
        if status == 410 { return "batch_expired" }
        if status == 409 || status == 507 { return "server_rejected" }
        return status >= 500 || status == 408 || status == 429 ? "network_unavailable" : "server_rejected"
    }

    private func finishBackgroundTask() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask); backgroundTask = .invalid
    }

    private static func code(_ error: Error) -> String {
        if let code = (error as? NativeError)?.code { return code == "invalid_session" ? "not_authenticated" : code }
        if error is AmbientStoreError { return "queue_corrupt" }
        return "network_unavailable"
    }

    var explanation: String? {
        switch error {
        case nil: return nil
        case "not_connected": return "Подключите сервер в настройках приложения."
        case "not_authenticated": return "Запись остановлена. Войдите в аккаунт и включите её снова."
        case "permission_denied": return "Разрешите микрофон в настройках iOS."
        case "microphone_busy": return "Микрофон занят диктовкой или звонком. После завершения включите запись снова."
        case "interrupted": return "iOS прервала запись. Включите её снова, когда микрофон станет доступен."
        case "queue_full": return "Локальная очередь заполнена. Дождитесь отправки или удалите очередь, затем включите запись снова."
        case "queue_corrupt": return "Не удалось открыть локальную очередь. Сохранённые файлы остаются на устройстве. Можно удалить очередь и начать заново."
        case "invalid_receipt": return "Сервер не подтвердил сохранение записи. Очередь сохранена; проверьте сервер."
        case "batch_expired": return "Сервер сообщил, что запись удалена или её срок хранения истёк. Очередь сохранена."
        case "server_rejected": return "Сервер отклонил запись. Очередь сохранена; проверьте сервер."
        case "capture_failed": return "Не удалось продолжить запись микрофона. Включите её снова."
        case "codec_unavailable": return "Телефон и сервер не поддерживают общий формат сжатия записи. Обновите приложение или сервер."
        case "vad_unavailable": return "Локальный детектор речи недоступен. Обновите приложение; запись остановлена."
        default: return "Нет связи с сервером. Записи остаются в очереди и отправятся после восстановления связи."
        }
    }
}

struct AmbientAudioScreen: View {
    @ObservedObject var audio: AmbientAudio
    @Environment(\.dismiss) private var dismiss
    @State private var confirmClear = false
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Label(audio.recording ? "Микрофон слушает" : "Запись выключена",
                          systemImage: audio.recording ? "record.circle.fill" : "mic.slash")
                        .foregroundStyle(audio.recording ? KaiColors.destructive : KaiColors.foreground)
                    Text("Микрофон работает непрерывно. Локальный детектор выделяет речь, включая голоса окружающих, с коротким запасом до и после неё. Тишина без речи не сохраняется. Фрагменты до 30 секунд отправляются на ваш сервер; при отсутствии связи остаются на устройстве. Сервер распознаёт речь через выбранное вами подключение для голоса. Аудио и текст доступны в разделе «Записи».")
                    Text("Включайте запись, когда участники разговора знают о ней и согласны. Запись продолжается с заблокированным экраном. Звонок, смена микрофона или завершение приложения могут её прервать; после этого включите её снова.")
                        .font(.footnote)
                    if let explanation = audio.explanation { Text(explanation).foregroundStyle(.secondary) }
                }
                Section {
                    if audio.recording {
                        Button("Остановить запись", role: .destructive) { audio.stop() }
                    } else if audio.starting {
                        ProgressView("Подготовка записи…")
                        Button("Отменить включение записи", role: .cancel) { audio.stop() }
                    } else {
                        Button(audio.starting ? "Подготовка…" : "Включить запись микрофона") { Task { await audio.start() } }
                            .disabled(audio.starting)
                    }
                }
                Section("Отправка") {
                    LabeledContent("В очереди", value: String(audio.queuedBatches))
                    LabeledContent("На устройстве", value: ByteCountFormatter.string(fromByteCount: Int64(audio.queuedBytes), countStyle: .file))
                    Text("Предел очереди — 512 МиБ. При заполнении запись остановится, сохранив очередь. Файлы удаляются с устройства после подтверждения сервера.")
                        .font(.footnote)
                    Text("Срок хранения отправленных фрагментов задаёт ваш сервер.").font(.footnote)
                    if let date = audio.lastUploadedAt { Text("Последняя отправка: \(date.formatted(date: .abbreviated, time: .standard))") }
                    Button("Остановить и удалить очередь", role: .destructive) { confirmClear = true }
                }
            }
            .navigationTitle("Фоновая запись")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Готово") { dismiss() } } }
            .confirmationDialog("Удалить записи с этого устройства?", isPresented: $confirmClear, titleVisibility: .visible) {
                Button("Остановить и удалить", role: .destructive) { audio.clear() }
            } message: { Text("Запись остановится. Уже отправленные фрагменты останутся на сервере.") }
        }
    }
}
