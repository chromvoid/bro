import CryptoKit
import Darwin
import Foundation

enum AmbientAudioFormat: String, Codable, Equatable {
    case wav = "wav"
    case oggOpus = "ogg-opus"
    case aacADTS = "aac-adts"

    var mimeType: String {
        switch self {
        case .wav: return "audio/wav"
        case .oggOpus: return "audio/ogg"
        case .aacADTS: return "audio/aac"
        }
    }

    var fileExtension: String {
        switch self {
        case .wav: return "wav"
        case .oggOpus: return "ogg"
        case .aacADTS: return "aac"
        }
    }

    var maximumBytes: Int { self == .wav ? 960_044 : 256 * 1024 }
}

struct AmbientBinding: Codable, Equatable {
    let origin: String
    let instanceID: String
    let userID: String
    let deviceID: String
    let expires: Date

    func sameAccount(_ other: AmbientBinding) -> Bool {
        origin == other.origin && instanceID == other.instanceID
            && userID == other.userID && deviceID == other.deviceID
    }
}

struct AmbientBatch: Codable, Equatable {
    let id: String
    let sessionID: String
    let sequence: Int
    let startedAt: Date
    let sha256: String
    let bytes: Int
    let binding: AmbientBinding
    let format: AmbientAudioFormat
    /// Captured PCM frames at 16 kHz, excluding codec priming and end padding.
    let frames: Int

    private enum CodingKeys: String, CodingKey {
        case id, sessionID, sequence, startedAt, sha256, bytes, binding, format, frames
    }

    init(id: String, sessionID: String, sequence: Int, startedAt: Date, sha256: String, bytes: Int,
         binding: AmbientBinding, format: AmbientAudioFormat = .wav, frames: Int? = nil) {
        self.id = id; self.sessionID = sessionID; self.sequence = sequence; self.startedAt = startedAt
        self.sha256 = sha256; self.bytes = bytes; self.binding = binding; self.format = format
        self.frames = frames ?? (format == .wav && bytes >= 44 ? (bytes - 44) / 2 : 0)
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        sessionID = try values.decode(String.self, forKey: .sessionID)
        sequence = try values.decode(Int.self, forKey: .sequence)
        startedAt = try values.decode(Date.self, forKey: .startedAt)
        sha256 = try values.decode(String.self, forKey: .sha256)
        bytes = try values.decode(Int.self, forKey: .bytes)
        binding = try values.decode(AmbientBinding.self, forKey: .binding)
        // Only truly absent fields denote the legacy schema. Null and unknown formats are invalid.
        if values.contains(.format) { format = try values.decode(AmbientAudioFormat.self, forKey: .format) }
        else { format = .wav }
        if values.contains(.frames) {
            frames = try values.decode(Int.self, forKey: .frames)
        } else {
            guard format == .wav, bytes >= 46 else {
                throw DecodingError.dataCorruptedError(forKey: .frames, in: values,
                                                       debugDescription: "Compressed batches require captured frames")
            }
            frames = (bytes - 44) / 2
        }
    }
}

enum AmbientStoreError: Error, LocalizedError {
    case full
    case unbound
    case invalidBinding
    case invalidPCM
    case invalidSegment
    case corruptQueue

    var errorDescription: String? {
        switch self {
        case .full: return "Локальная очередь аудио заполнена. Запись приостановлена."
        case .unbound: return "Для записи требуется проверенная сессия."
        case .invalidBinding: return "Не удалось проверить владельца очереди аудио."
        case .invalidPCM: return "Некорректный аудиосегмент."
        case .invalidSegment: return "Некорректные данные аудиосегмента."
        case .corruptQueue: return "Не удалось восстановить очередь аудио. Файлы сохранены; очередь можно очистить вручную."
        }
    }
}

/// A single serialized caller owns this store. Recording and uploads use the app's main actor.
/// Finalized files survive session expiry; only account changes, explicit clearing and exact ACKs delete them.
final class AmbientAudioStore {
    static let maximumBatchBytes = AmbientAudioFormat.wav.maximumBytes // Keep finalized legacy 30-second WAVs readable.
    static let compressedMaximumBatchBytes = AmbientAudioFormat.oggOpus.maximumBytes
    let maximumBytes: Int
    private(set) var binding: AmbientBinding?
    private(set) var batches: [AmbientBatch] = []
    var queuedBytes: Int { batches.reduce(0) { $0 + $1.bytes } }
    /// Capacity includes finalized audio, manifests and the account binding, including interrupted writes.
    var capacityBytes: Int {
        do {
            var total = 0
            for url in try manager.contentsOfDirectory(at: root, includingPropertiesForKeys: [.fileSizeKey]) {
                let values = try url.resourceValues(forKeys: [.fileSizeKey])
                guard let size = values.fileSize, size >= 0, total <= Int.max - size else { return Int.max }
                total += size
            }
            return total
        } catch { return Int.max }
    }

    private let root: URL
    private let manager = FileManager.default
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(root: URL, maximumBytes: Int = 512 * 1024 * 1024) throws {
        guard root.isFileURL, maximumBytes > 0 else { throw AmbientStoreError.invalidSegment }
        self.root = root.standardizedFileURL
        self.maximumBytes = maximumBytes
        encoder.outputFormatting = [.sortedKeys]
        try manager.createDirectory(at: self.root, withIntermediateDirectories: true,
                                    attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        let properties = try self.root.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
        guard properties.isDirectory == true, properties.isSymbolicLink != true else {
            throw AmbientStoreError.corruptQueue
        }
        try manager.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                                  ofItemAtPath: self.root.path)
        var directory = self.root
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try directory.setResourceValues(values)
        try restore()
    }

    func bind(_ value: AmbientBinding) throws {
        guard Self.valid(value) else { throw AmbientStoreError.invalidBinding }
        let data = try encoder.encode(value)
        guard data.count <= 16_384 else { throw AmbientStoreError.invalidBinding }
        guard data.count <= maximumBytes else { throw AmbientStoreError.full }
        if let binding, !binding.sameAccount(value) { try clear() }
        let url = root.appendingPathComponent("binding.json")
        let previousSize = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        let occupied = capacityBytes
        guard occupied >= previousSize, occupied - previousSize <= maximumBytes - data.count else {
            throw AmbientStoreError.full
        }
        try write(data, to: url)
        binding = value
    }

    func file(_ batch: AmbientBatch) -> URL {
        // Loaded and newly created IDs are UUIDs; callers never supply an upload path.
        root.appendingPathComponent(batch.id + "." + batch.format.fileExtension)
    }

    func enqueue(pcm: Data, sessionID: String, sequence: Int, startedAt: Date) throws -> AmbientBatch {
        try enqueue(encoded: Self.wave(pcm), format: .wav, sessionID: sessionID, sequence: sequence,
                    startedAt: startedAt, frames: pcm.count / 2)
    }

    func enqueue(encoded audio: Data, format: AmbientAudioFormat, sessionID: String,
                 sequence: Int, startedAt: Date, frames: Int) throws -> AmbientBatch {
        guard let binding else { throw AmbientStoreError.unbound }
        guard Self.uuid(sessionID), sequence >= 0, startedAt.timeIntervalSince1970.isFinite,
              !batches.contains(where: { $0.sessionID.lowercased() == sessionID.lowercased() && $0.sequence == sequence }) else {
            throw AmbientStoreError.invalidSegment
        }
        try Self.validatePayload(audio, format: format, frames: frames)
        let batch = AmbientBatch(id: UUID().uuidString.lowercased(), sessionID: sessionID,
                                 sequence: sequence, startedAt: startedAt, sha256: Self.hash(audio),
                                 bytes: audio.count, binding: binding, format: format, frames: frames)
        let metadata = try encoder.encode(batch)
        guard metadata.count <= 16_384 else { throw AmbientStoreError.invalidSegment }
        let required = audio.count + metadata.count
        guard required <= maximumBytes, capacityBytes <= maximumBytes - required else {
            throw AmbientStoreError.full
        }
        // Audio first: a crash between these commits leaves an orphan which restore reports, never erases.
        try write(audio, to: file(batch))
        try write(metadata, to: manifest(batch.id))
        batches.append(batch)
        sortBatches()
        return batch
    }

    @discardableResult
    func acknowledge(id: String, sha256: String) throws -> Bool {
        guard let index = batches.firstIndex(where: { $0.id == id && $0.sha256 == sha256 }) else { return false }
        let batch = batches[index]
        // The durable ACK marker lets restore finish a deletion interrupted by process termination.
        try write(encoder.encode(batch), to: acknowledgement(id))
        try finishAcknowledgement(batch)
        batches.remove(at: index)
        return true
    }

    func clear() throws {
        for url in try manager.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) {
            try manager.removeItem(at: url)
        }
        try synchronizeDirectory()
        batches.removeAll()
        binding = nil
    }

    static func receipt(_ data: Data, batch: AmbientBatch) -> Bool {
        guard data.count <= 16_384,
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              BridgeProtocol.boolean(object["stored"]) == true,
              object["id"] as? String == batch.id,
              object["sha256"] as? String == batch.sha256 else { return false }
        return true
    }

    /// Canonical little-endian RIFF/WAVE, including silence; no ASR or speaker inference is performed here.
    static func wave(_ pcm: Data) throws -> Data {
        guard !pcm.isEmpty, pcm.count.isMultiple(of: 2), pcm.count <= maximumBatchBytes - 44 else {
            throw AmbientStoreError.invalidPCM
        }
        var result = Data("RIFF".utf8)
        append(UInt32(36 + pcm.count), to: &result)
        result.append(Data("WAVEfmt ".utf8))
        append(UInt32(16), to: &result)
        append(UInt16(1), to: &result) // PCM.
        append(UInt16(1), to: &result) // Mono.
        append(UInt32(16_000), to: &result)
        append(UInt32(32_000), to: &result)
        append(UInt16(2), to: &result)
        append(UInt16(16), to: &result)
        result.append(Data("data".utf8))
        append(UInt32(pcm.count), to: &result)
        result.append(pcm)
        return result
    }

    private func restore() throws {
        let urls = try manager.contentsOfDirectory(at: root,
                                                  includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
        var manifests: [String: URL] = [:]
        var audio: [String: URL] = [:]
        var acknowledgements: [String: URL] = [:]
        var sizes: [String: Int] = [:]
        var spellings: [String: String] = [:]
        for url in urls {
            let properties = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
            guard properties.isRegularFile == true, properties.isSymbolicLink != true,
                  let size = properties.fileSize, size >= 0 else {
                throw AmbientStoreError.corruptQueue
            }
            sizes[url.lastPathComponent] = size
            if url.lastPathComponent == "binding.json" {
                let value = try decode(AmbientBinding.self, at: url)
                guard Self.valid(value) else { throw AmbientStoreError.corruptQueue }
                binding = value
                continue
            }
            let id = url.deletingPathExtension().lastPathComponent
            guard Self.uuid(id) else { throw AmbientStoreError.corruptQueue }
            if let spelling = spellings[id.lowercased()], spelling != id { throw AmbientStoreError.corruptQueue }
            spellings[id.lowercased()] = id
            switch url.pathExtension {
            case "json": manifests[id] = url
            case "wav", "ogg", "aac":
                guard audio[id] == nil else { throw AmbientStoreError.corruptQueue }
                audio[id] = url
            case "ack": acknowledgements[id] = url
            default: throw AmbientStoreError.corruptQueue
            }
        }
        guard urls.isEmpty || binding != nil else { throw AmbientStoreError.corruptQueue }

        // Validate every file before modifying anything, so corruption cannot partially erase the queue.
        var loaded: [String: AmbientBatch] = [:]
        for (id, url) in manifests {
            let batch = try decode(AmbientBatch.self, at: url)
            guard batch.id == id, valid(batch) else { throw AmbientStoreError.corruptQueue }
            loaded[id] = batch
        }
        var received: [String: AmbientBatch] = [:]
        for (id, url) in acknowledgements {
            let batch = try decode(AmbientBatch.self, at: url)
            guard batch.id == id, valid(batch), loaded[id].map({ $0 == batch }) ?? true else {
                throw AmbientStoreError.corruptQueue
            }
            received[id] = batch
        }
        for (id, url) in audio {
            guard let batch = loaded[id] ?? received[id] else { throw AmbientStoreError.corruptQueue }
            try validateAudio(url, batch: batch)
        }
        for (id, _) in loaded where audio[id] == nil && received[id] == nil {
            throw AmbientStoreError.corruptQueue
        }
        var total = sizes["binding.json"] ?? 0
        guard total <= maximumBytes else { throw AmbientStoreError.corruptQueue }
        var segments = Set<String>()
        for (id, batch) in loaded where received[id] == nil {
            let segment = batch.sessionID.lowercased() + ":" + String(batch.sequence)
            let required = batch.bytes + (sizes[id + ".json"] ?? 0)
            guard segments.insert(segment).inserted, required <= maximumBytes,
                  total <= maximumBytes - required else { throw AmbientStoreError.corruptQueue }
            total += required
        }
        for batch in received.values { try finishAcknowledgement(batch) }
        batches = loaded.filter { received[$0.key] == nil }.map(\.value)
        sortBatches()
    }

    private func valid(_ batch: AmbientBatch) -> Bool {
        let validWAV = batch.format != .wav || (batch.bytes >= 46 && batch.bytes.isMultiple(of: 2)
                                               && batch.frames == (batch.bytes - 44) / 2)
        return Self.uuid(batch.id) && Self.uuid(batch.sessionID) && batch.sequence >= 0
            && batch.startedAt.timeIntervalSince1970.isFinite
            && (1...batch.format.maximumBytes).contains(batch.bytes) && (1...480_000).contains(batch.frames) && validWAV
            && batch.sha256.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil
            && Self.valid(batch.binding) && (binding?.sameAccount(batch.binding) == true)
    }

    private static func valid(_ value: AmbientBinding) -> Bool {
        guard let origin = ShellOrigin(value.origin, allowLoopbackHTTP: true) else { return false }
        return origin.canonical == value.origin && uuid(value.instanceID) && uuid(value.userID)
            && uuid(value.deviceID) && value.expires.timeIntervalSince1970.isFinite
    }

    private static func uuid(_ value: String) -> Bool {
        guard value.count == 36, let parsed = UUID(uuidString: value) else { return false }
        return parsed.uuidString.lowercased() == value.lowercased()
    }

    private func validateAudio(_ url: URL, batch: AmbientBatch) throws {
        let properties = try url.resourceValues(forKeys: [.fileSizeKey])
        guard url.lastPathComponent == file(batch).lastPathComponent,
              properties.fileSize == batch.bytes else { throw AmbientStoreError.corruptQueue }
        let bytes = try Data(contentsOf: url)
        guard Self.hash(bytes) == batch.sha256 else { throw AmbientStoreError.corruptQueue }
        do { try Self.validatePayload(bytes, format: batch.format, frames: batch.frames) }
        catch { throw AmbientStoreError.corruptQueue }
    }

    private static func validatePayload(_ data: Data, format: AmbientAudioFormat, frames: Int) throws {
        guard (1...format.maximumBytes).contains(data.count), (1...480_000).contains(frames) else {
            throw AmbientStoreError.invalidSegment
        }
        if format == .wav {
            guard frames == (data.count - 44) / 2, (try? wave(Data(data.dropFirst(44)))) == data else {
                throw AmbientStoreError.invalidPCM
            }
        } else {
            try AmbientCodec.validate(data, format: format, frames: frames)
        }
    }

    private func decode<T: Decodable>(_ type: T.Type, at url: URL) throws -> T {
        let properties = try url.resourceValues(forKeys: [.fileSizeKey])
        guard let size = properties.fileSize, (1...16_384).contains(size),
              let value = try? decoder.decode(type, from: Data(contentsOf: url)) else {
            throw AmbientStoreError.corruptQueue
        }
        return value
    }

    private func finishAcknowledgement(_ batch: AmbientBatch) throws {
        for url in [file(batch), manifest(batch.id)] where manager.fileExists(atPath: url.path) {
            try manager.removeItem(at: url)
            try synchronizeDirectory()
        }
        let marker = acknowledgement(batch.id)
        if manager.fileExists(atPath: marker.path) { try manager.removeItem(at: marker) }
        try synchronizeDirectory()
    }

    private func sortBatches() {
        batches.sort {
            if $0.startedAt != $1.startedAt { return $0.startedAt < $1.startedAt }
            if $0.sessionID != $1.sessionID { return $0.sessionID < $1.sessionID }
            if $0.sequence != $1.sequence { return $0.sequence < $1.sequence }
            return $0.id < $1.id
        }
    }

    private func manifest(_ id: String) -> URL { root.appendingPathComponent(id + ".json") }
    private func acknowledgement(_ id: String) -> URL { root.appendingPathComponent(id + ".ack") }

    private func write(_ data: Data, to url: URL) throws {
        try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        let handle = try FileHandle(forWritingTo: url)
        defer { try? handle.close() }
        try handle.synchronize()
        try synchronizeDirectory()
    }

    private func synchronizeDirectory() throws {
        let descriptor = Darwin.open(root.path, O_RDONLY)
        guard descriptor >= 0 else { throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno)) }
        defer { Darwin.close(descriptor) }
        guard Darwin.fsync(descriptor) == 0 else { throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno)) }
    }

    private static func hash(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private static func append<T: FixedWidthInteger>(_ value: T, to data: inout Data) {
        var littleEndian = value.littleEndian
        withUnsafeBytes(of: &littleEndian) { data.append(contentsOf: $0) }
    }
}
