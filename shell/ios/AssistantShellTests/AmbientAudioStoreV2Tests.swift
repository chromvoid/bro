import CryptoKit
import XCTest
@testable import AssistantShell

final class AmbientAudioStoreV2Tests: XCTestCase {
    private var roots: [URL] = []
    private let session = "40000000-0000-0000-0000-000000000001"
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    override func tearDownWithError() throws {
        for root in roots where FileManager.default.fileExists(atPath: root.path) {
            try FileManager.default.removeItem(at: root)
        }
        roots.removeAll()
    }

    private func store() throws -> (AmbientAudioStore, URL) {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("ambient-v2-" + UUID().uuidString)
        roots.append(root)
        let store = try AmbientAudioStore(root: root)
        try store.bind(AmbientBinding(origin: "https://assistant.example",
                                     instanceID: "10000000-0000-0000-0000-000000000001",
                                     userID: "20000000-0000-0000-0000-000000000001",
                                     deviceID: "30000000-0000-0000-0000-000000000001", expires: now))
        return (store, root)
    }

    private func wav(_ store: AmbientAudioStore, sequence: Int = 0, pcm: Data = Data([0, 0])) throws -> AmbientBatch {
        try store.enqueue(pcm: pcm, sessionID: session, sequence: sequence, startedAt: now)
    }

    private func opus(_ store: AmbientAudioStore, sequence: Int = 1) throws -> AmbientBatch {
        try store.enqueue(encoded: ogg(), format: .oggOpus, sessionID: session, sequence: sequence,
                          startedAt: now.addingTimeInterval(Double(sequence)), frames: 320)
    }

    private func json(_ batch: AmbientBatch) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(batch)) as? [String: Any])
    }

    private func write(_ value: [String: Any], batch: AmbientBatch, root: URL, suffix: String = "json") throws {
        try JSONSerialization.data(withJSONObject: value).write(to: root.appendingPathComponent(batch.id + "." + suffix))
    }

    func testLegacyThirtySecondWAVRestoresWithoutChangingItsIdentityOrBytes() throws {
        let (store, root) = try store()
        let batch = try wav(store, pcm: Data(repeating: 0, count: 960_000))
        let audio = try Data(contentsOf: store.file(batch))
        var legacy = try json(batch)
        legacy.removeValue(forKey: "format")
        legacy.removeValue(forKey: "frames")
        try write(legacy, batch: batch, root: root)
        let originalManifest = try Data(contentsOf: root.appendingPathComponent(batch.id + ".json"))

        let restored = try AmbientAudioStore(root: root)
        XCTAssertEqual(restored.batches, [batch])
        XCTAssertEqual(restored.batches.first?.format, .wav)
        XCTAssertEqual(restored.batches.first?.frames, 480_000)
        XCTAssertEqual(restored.batches.first?.bytes, 960_044)
        XCTAssertEqual(try Data(contentsOf: restored.file(batch)), audio)
        XCTAssertEqual(try Data(contentsOf: root.appendingPathComponent(batch.id + ".json")), originalManifest)
        XCTAssertEqual(restored.batches.first?.sha256, batch.sha256)
    }

    func testMixedQueueRestoresOddLengthOpusAndLegacyWAV() throws {
        let (store, root) = try store()
        let legacyBatch = try wav(store)
        var legacy = try json(legacyBatch)
        legacy.removeValue(forKey: "format")
        legacy.removeValue(forKey: "frames")
        try write(legacy, batch: legacyBatch, root: root)
        let compressed = try opus(store)
        let encoded = ogg()
        XCTAssertFalse(encoded.count.isMultiple(of: 2))
        XCTAssertEqual(compressed.bytes, encoded.count)
        XCTAssertEqual(compressed.sha256, SHA256.hash(data: encoded).map { String(format: "%02x", $0) }.joined())
        XCTAssertEqual(store.file(legacyBatch).pathExtension, "wav")
        XCTAssertEqual(store.file(compressed).pathExtension, "ogg")
        XCTAssertEqual(AmbientAudioFormat.wav.mimeType, "audio/wav")
        XCTAssertEqual(AmbientAudioFormat.oggOpus.mimeType, "audio/ogg")
        XCTAssertEqual(AmbientAudioFormat.aacADTS.mimeType, "audio/aac")
        XCTAssertEqual(AmbientAudioFormat.aacADTS.fileExtension, "aac")

        let restored = try AmbientAudioStore(root: root)
        XCTAssertEqual(restored.batches, [legacyBatch, compressed])
        XCTAssertEqual(try Data(contentsOf: restored.file(compressed)), encoded)
        XCTAssertGreaterThan(restored.capacityBytes, restored.queuedBytes)
    }

    func testExplicitNullAndUnknownFormatNeverFallBackToWAV() throws {
        let (store, root) = try store()
        let batch = try wav(store)
        let original = try json(batch)
        for invalid: Any in [NSNull(), "unknown", "ogg", 1] {
            var object = original
            object["format"] = invalid
            try write(object, batch: batch, root: root)
            XCTAssertThrowsError(try AmbientAudioStore(root: root))
            XCTAssertTrue(FileManager.default.fileExists(atPath: store.file(batch).path))
        }
    }

    func testAACManifestRestoresWithItsOwnExtensionAndCapturedFrames() throws {
        let (store, root) = try store()
        // Synthetic ADTS structural fixture: AAC-LC, mono 16 kHz, one block, no CRC.
        let encoded = Data([0xFF, 0xF1, 0x60, 0x40, 0x01, 0x1F, 0xFC, 0x00])
        let batch = try store.enqueue(encoded: encoded, format: .aacADTS, sessionID: session,
                                      sequence: 0, startedAt: now, frames: 1_024)
        XCTAssertEqual(batch.format.rawValue, "aac-adts")
        XCTAssertEqual(store.file(batch).pathExtension, "aac")
        let restored = try AmbientAudioStore(root: root)
        XCTAssertEqual(restored.batches, [batch])
        XCTAssertEqual(try Data(contentsOf: restored.file(batch)), encoded)
    }

    func testCompressedManifestRequiresCapturedFramesWithinThirtySeconds() throws {
        let (store, root) = try store()
        let batch = try opus(store)
        var missing = try json(batch)
        missing.removeValue(forKey: "frames")
        try write(missing, batch: batch, root: root)
        XCTAssertThrowsError(try AmbientAudioStore(root: root))
        for invalid: Any in [NSNull(), 0, -1, 480_001] {
            var object = try json(batch)
            object["frames"] = invalid
            try write(object, batch: batch, root: root)
            XCTAssertThrowsError(try AmbientAudioStore(root: root))
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: store.file(batch).path))
    }

    func testOversizedCompressedManifestPreservesItsFinalizedFile() throws {
        let (store, root) = try store()
        let batch = try opus(store)
        var object = try json(batch)
        object["bytes"] = 256 * 1024 + 1
        try write(object, batch: batch, root: root)
        XCTAssertThrowsError(try AmbientAudioStore(root: root))
        XCTAssertEqual(try Data(contentsOf: store.file(batch)), ogg())
    }

    func testDuplicateAudioExtensionsAndExtensionDisagreementPreserveAllFiles() throws {
        let (store, root) = try store()
        let batch = try wav(store)
        let extra = root.appendingPathComponent(batch.id + ".ogg")
        try ogg().write(to: extra)
        XCTAssertThrowsError(try AmbientAudioStore(root: root))
        XCTAssertTrue(FileManager.default.fileExists(atPath: extra.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: store.file(batch).path))
        try FileManager.default.removeItem(at: extra)
        try FileManager.default.moveItem(at: store.file(batch), to: extra)
        XCTAssertThrowsError(try AmbientAudioStore(root: root))
        XCTAssertTrue(FileManager.default.fileExists(atPath: extra.path))
    }

    func testOrphanCompressedAudioIsReportedAndPreserved() throws {
        let (_, root) = try store()
        let orphan = root.appendingPathComponent(UUID().uuidString.lowercased() + ".ogg")
        let data = ogg()
        try data.write(to: orphan)
        XCTAssertThrowsError(try AmbientAudioStore(root: root))
        XCTAssertEqual(try Data(contentsOf: orphan), data)
    }

    func testCompressedBoundsRejectWithoutReplacingFinalizedAudio() throws {
        let (store, root) = try store()
        let previous = try wav(store)
        let before = store.capacityBytes
        XCTAssertThrowsError(try store.enqueue(encoded: Data(repeating: 0, count: 256 * 1024 + 1), format: .oggOpus,
                                              sessionID: session, sequence: 1, startedAt: now, frames: 320))
        XCTAssertThrowsError(try store.enqueue(encoded: ogg(), format: .oggOpus,
                                              sessionID: session, sequence: 1, startedAt: now, frames: 480_001))
        XCTAssertEqual(store.batches, [previous])
        XCTAssertEqual(store.capacityBytes, before)
        XCTAssertEqual(try AmbientAudioStore(root: root).batches, [previous])

        let bounded = try AmbientAudioStore(root: root, maximumBytes: before)
        XCTAssertThrowsError(try opus(bounded)) { error in
            guard case AmbientStoreError.full = error else { return XCTFail("Expected full queue, got \(error)") }
        }
        XCTAssertEqual(bounded.capacityBytes, before)
        XCTAssertTrue(FileManager.default.fileExists(atPath: bounded.file(previous).path))
    }

    func testLegacyAndCompressedAcknowledgementsDeleteOnlyExactMatchingFiles() throws {
        let (store, root) = try store()
        let previous = try wav(store)
        let compressed = try opus(store)
        XCTAssertFalse(try store.acknowledge(id: compressed.id, sha256: previous.sha256))
        let numericStored: [String: Any] = ["id": compressed.id, "sha256": compressed.sha256, "stored": 1]
        XCTAssertFalse(AmbientAudioStore.receipt(try JSONSerialization.data(withJSONObject: numericStored), batch: compressed))
        let receipt: [String: Any] = ["id": compressed.id, "sha256": compressed.sha256, "stored": true]
        XCTAssertTrue(AmbientAudioStore.receipt(try JSONSerialization.data(withJSONObject: receipt), batch: compressed))
        XCTAssertTrue(try store.acknowledge(id: previous.id, sha256: previous.sha256))
        XCTAssertFalse(FileManager.default.fileExists(atPath: store.file(previous).path))
        XCTAssertEqual(try AmbientAudioStore(root: root).batches, [compressed])

        try write(try json(compressed), batch: compressed, root: root, suffix: "ack")
        try FileManager.default.removeItem(at: store.file(compressed))
        XCTAssertTrue(try AmbientAudioStore(root: root).batches.isEmpty)
    }

    func testInterruptedLegacyAcknowledgementRestoresWithNoFormatOrFrames() throws {
        let (store, root) = try store()
        let batch = try wav(store)
        var legacy = try json(batch)
        legacy.removeValue(forKey: "format")
        legacy.removeValue(forKey: "frames")
        try write(legacy, batch: batch, root: root)
        try write(legacy, batch: batch, root: root, suffix: "ack")
        try FileManager.default.removeItem(at: store.file(batch))
        let restored = try AmbientAudioStore(root: root)
        XCTAssertTrue(restored.batches.isEmpty)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.path), ["binding.json"])
    }

    // Synthetic mono silence: each fixture is a complete independently headed logical Ogg stream.
    private func ogg() -> Data {
        var head = Data("OpusHead".utf8)
        head.append(contentsOf: [1, 1])
        append(UInt16(0), to: &head)
        append(UInt32(16_000), to: &head)
        append(UInt16(0), to: &head)
        head.append(0)
        var tags = Data("OpusTags".utf8)
        append(UInt32(1), to: &tags)
        tags.append(Data("t".utf8))
        append(UInt32(0), to: &tags)
        var result = page(head, flags: 2, sequence: 0, granule: 0)
        result.append(page(tags, flags: 0, sequence: 1, granule: 0))
        result.append(page(Data([0xF8, 0xFF, 0xFE]), flags: 4, sequence: 2, granule: 960))
        return result
    }

    private func page(_ packet: Data, flags: UInt8, sequence: UInt32, granule: UInt64) -> Data {
        var result = Data("OggS".utf8)
        result.append(contentsOf: [0, flags])
        append(granule, to: &result)
        append(UInt32(17), to: &result)
        append(sequence, to: &result)
        append(UInt32(0), to: &result)
        result.append(contentsOf: [1, UInt8(packet.count)])
        result.append(packet)
        var checksum: UInt32 = 0
        for byte in result {
            checksum ^= UInt32(byte) << 24
            for _ in 0..<8 { checksum = checksum & 0x80000000 == 0 ? checksum << 1 : (checksum << 1) ^ 0x04C11DB7 }
        }
        for index in 0..<4 { result[22 + index] = UInt8(truncatingIfNeeded: checksum >> (8 * index)) }
        return result
    }

    private func append<T: FixedWidthInteger>(_ value: T, to data: inout Data) {
        var littleEndian = value.littleEndian
        withUnsafeBytes(of: &littleEndian) { data.append(contentsOf: $0) }
    }
}
