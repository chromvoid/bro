import XCTest
@testable import AssistantShell

final class AmbientAudioTests: XCTestCase {
    private var roots: [URL] = []
    private let instance = "10000000-0000-0000-0000-000000000001"
    private let user = "20000000-0000-0000-0000-000000000001"
    private let device = "30000000-0000-0000-0000-000000000001"
    private let session = "40000000-0000-0000-0000-000000000001"
    private let pcm = Data([0x00, 0x00, 0x01, 0x80])

    override func tearDownWithError() throws {
        for root in roots where FileManager.default.fileExists(atPath: root.path) {
            try FileManager.default.removeItem(at: root)
        }
        roots.removeAll()
    }

    private func root() -> URL {
        let value = FileManager.default.temporaryDirectory.appendingPathComponent("ambient-audio-tests-" + UUID().uuidString)
        roots.append(value)
        return value
    }

    private func binding(userID: String? = nil, expires: Date = Date().addingTimeInterval(3_600)) -> AmbientBinding {
        AmbientBinding(origin: "https://assistant.example", instanceID: instance,
                       userID: userID ?? user, deviceID: device, expires: expires)
    }

    private func enqueue(_ store: AmbientAudioStore, sequence: Int = 0) throws -> AmbientBatch {
        try store.enqueue(pcm: pcm, sessionID: session, sequence: sequence,
                          startedAt: Date(timeIntervalSince1970: 1_800_000_000 + Double(sequence)))
    }

    private func number(_ data: Data, at offset: Int, bytes: Int = 4) -> UInt32 {
        data[offset..<(offset + bytes)].enumerated().reduce(0) { $0 | (UInt32($1.element) << (8 * $1.offset)) }
    }

    func testWAVHeaderAndThirtySecondBoundary() throws {
        let wave = try AmbientAudioStore.wave(pcm)
        XCTAssertEqual(wave.count, pcm.count + 44)
        XCTAssertEqual(String(decoding: wave.prefix(4), as: UTF8.self), "RIFF")
        XCTAssertEqual(number(wave, at: 4), UInt32(wave.count - 8))
        XCTAssertEqual(String(decoding: wave[8..<16], as: UTF8.self), "WAVEfmt ")
        XCTAssertEqual(number(wave, at: 16), 16)
        XCTAssertEqual(number(wave, at: 20, bytes: 2), 1)
        XCTAssertEqual(number(wave, at: 22, bytes: 2), 1)
        XCTAssertEqual(number(wave, at: 24), 16_000)
        XCTAssertEqual(number(wave, at: 28), 32_000)
        XCTAssertEqual(number(wave, at: 32, bytes: 2), 2)
        XCTAssertEqual(number(wave, at: 34, bytes: 2), 16)
        XCTAssertEqual(String(decoding: wave[36..<40], as: UTF8.self), "data")
        XCTAssertEqual(number(wave, at: 40), UInt32(pcm.count))
        XCTAssertEqual(wave.dropFirst(44), pcm)

        let maximum = try AmbientAudioStore.wave(Data(repeating: 0, count: 30 * 16_000 * 2))
        XCTAssertEqual(maximum.count, AmbientAudioStore.maximumBatchBytes)
        XCTAssertEqual(number(maximum, at: 40), 960_000)
        for invalid in [Data(), Data([0]), Data(repeating: 0, count: 960_002)] {
            XCTAssertThrowsError(try AmbientAudioStore.wave(invalid))
        }
    }

    func testQueueRestoresIdentityFilesAndOrderAfterRestart() throws {
        let directory = root()
        let store = try AmbientAudioStore(root: directory)
        let owner = binding()
        try store.bind(owner)
        let second = try enqueue(store, sequence: 1)
        let first = try enqueue(store)
        XCTAssertEqual(store.batches, [first, second])
        XCTAssertEqual(store.queuedBytes, first.bytes + second.bytes)
        XCTAssertGreaterThan(store.capacityBytes, store.queuedBytes)
        let restored = try AmbientAudioStore(root: directory)
        XCTAssertEqual(restored.binding, owner)
        XCTAssertEqual(restored.batches, [first, second])
        XCTAssertEqual(try Data(contentsOf: restored.file(first)), try AmbientAudioStore.wave(pcm))
    }

    func testSessionExpiryDoesNotDiscardPendingAudio() throws {
        let directory = root()
        let store = try AmbientAudioStore(root: directory)
        let expired = binding(expires: Date(timeIntervalSince1970: 1))
        try store.bind(expired)
        let batch = try enqueue(store)
        let restored = try AmbientAudioStore(root: directory)
        XCTAssertEqual(restored.binding, expired)
        XCTAssertEqual(restored.batches, [batch])
        XCTAssertTrue(FileManager.default.fileExists(atPath: restored.file(batch).path))

        let refreshed = binding()
        XCTAssertTrue(expired.sameAccount(refreshed))
        try restored.bind(refreshed)
        let refreshedStore = try AmbientAudioStore(root: directory)
        XCTAssertEqual(refreshedStore.binding, refreshed)
        XCTAssertEqual(refreshedStore.batches, [batch])
    }

    func testSwitchingAccountPurgesAudioBeforeBindingNewOwner() throws {
        let directory = root()
        let store = try AmbientAudioStore(root: directory)
        try store.bind(binding())
        let previous = try enqueue(store)
        let next = binding(userID: "20000000-0000-0000-0000-000000000002")
        try store.bind(next)
        XCTAssertEqual(store.binding, next)
        XCTAssertTrue(store.batches.isEmpty)
        XCTAssertEqual(store.queuedBytes, 0)
        XCTAssertFalse(FileManager.default.fileExists(atPath: store.file(previous).path))
        let restored = try AmbientAudioStore(root: directory)
        XCTAssertEqual(restored.binding, next)
        XCTAssertTrue(restored.batches.isEmpty)
        try restored.clear()
        XCTAssertNil(restored.binding)
        XCTAssertNil(try AmbientAudioStore(root: directory).binding)
    }

    func testOnlyExactAcknowledgementDeletesAudio() throws {
        let directory = root()
        let store = try AmbientAudioStore(root: directory)
        try store.bind(binding())
        let batch = try enqueue(store)
        XCTAssertFalse(try store.acknowledge(id: UUID().uuidString, sha256: batch.sha256))
        XCTAssertFalse(try store.acknowledge(id: batch.id, sha256: String(repeating: "0", count: 64)))
        XCTAssertTrue(FileManager.default.fileExists(atPath: store.file(batch).path))
        XCTAssertEqual(try AmbientAudioStore(root: directory).batches, [batch])
        XCTAssertTrue(try store.acknowledge(id: batch.id, sha256: batch.sha256))
        XCTAssertFalse(FileManager.default.fileExists(atPath: store.file(batch).path))
        XCTAssertTrue(store.batches.isEmpty)
        XCTAssertFalse(try store.acknowledge(id: batch.id, sha256: batch.sha256))
        XCTAssertTrue(try AmbientAudioStore(root: directory).batches.isEmpty)
    }

    func testCapacityDoesNotEvictPreviouslyFinalizedAudio() throws {
        let directory = root()
        let original = try AmbientAudioStore(root: directory)
        try original.bind(binding())
        let first = try enqueue(original)
        let capacity = original.capacityBytes
        let store = try AmbientAudioStore(root: directory, maximumBytes: capacity)
        XCTAssertThrowsError(try enqueue(store, sequence: 1)) { error in
            guard case AmbientStoreError.full = error else { return XCTFail("Expected a full queue, got \(error)") }
        }
        XCTAssertEqual(store.queuedBytes, first.bytes)
        XCTAssertEqual(store.capacityBytes, capacity)
        XCTAssertEqual(store.batches, [first])
        XCTAssertEqual(try Data(contentsOf: store.file(first)), try AmbientAudioStore.wave(pcm))
        XCTAssertEqual(try AmbientAudioStore(root: directory, maximumBytes: capacity).batches, [first])
    }

    func testReceiptRequiresBooleanStoredAndMatchingIDAndHash() throws {
        let store = try AmbientAudioStore(root: root())
        try store.bind(binding())
        let batch = try enqueue(store)
        let valid: [String: Any] = ["stored": true, "id": batch.id, "sha256": batch.sha256]
        XCTAssertTrue(AmbientAudioStore.receipt(try JSONSerialization.data(withJSONObject: valid), batch: batch))
        for stored: Any in [false, 1, "true", NSNull()] {
            var invalid = valid
            invalid["stored"] = stored
            XCTAssertFalse(AmbientAudioStore.receipt(try JSONSerialization.data(withJSONObject: invalid), batch: batch))
        }
        for key in ["stored", "id", "sha256"] {
            var invalid = valid
            invalid.removeValue(forKey: key)
            XCTAssertFalse(AmbientAudioStore.receipt(try JSONSerialization.data(withJSONObject: invalid), batch: batch))
        }
        var invalid = valid
        invalid["id"] = UUID().uuidString
        XCTAssertFalse(AmbientAudioStore.receipt(try JSONSerialization.data(withJSONObject: invalid), batch: batch))
        invalid = valid
        invalid["sha256"] = String(repeating: "0", count: 64)
        XCTAssertFalse(AmbientAudioStore.receipt(try JSONSerialization.data(withJSONObject: invalid), batch: batch))
        XCTAssertFalse(AmbientAudioStore.receipt(Data("invalid".utf8), batch: batch))
    }

    func testCorruptAudioBlocksRestoreAndPreservesFiles() throws {
        let directory = root()
        let store = try AmbientAudioStore(root: directory)
        try store.bind(binding())
        let batch = try enqueue(store)
        var bytes = try Data(contentsOf: store.file(batch))
        bytes[bytes.count - 1] ^= 1
        try bytes.write(to: store.file(batch))
        XCTAssertThrowsError(try AmbientAudioStore(root: directory))
        XCTAssertEqual(try Data(contentsOf: store.file(batch)), bytes)
        XCTAssertTrue(FileManager.default.fileExists(atPath: directory.appendingPathComponent(batch.id + ".json").path))
    }

    func testUnmanifestedFinalizedAudioIsPreservedAsCorruption() throws {
        let directory = root()
        let store = try AmbientAudioStore(root: directory)
        try store.bind(binding())
        let orphan = directory.appendingPathComponent(UUID().uuidString + ".wav")
        let bytes = try AmbientAudioStore.wave(pcm)
        try bytes.write(to: orphan)
        XCTAssertThrowsError(try AmbientAudioStore(root: directory))
        XCTAssertEqual(try Data(contentsOf: orphan), bytes)
    }

    func testUnsafeIDsAndOversizedManifestBlockRestoreWithoutDeletion() throws {
        let directory = root()
        let store = try AmbientAudioStore(root: directory)
        try store.bind(binding())
        let batch = try enqueue(store)
        let manifest = directory.appendingPathComponent(batch.id + ".json")
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: manifest)) as? [String: Any])
        object["id"] = "../outside"
        try JSONSerialization.data(withJSONObject: object).write(to: manifest)
        XCTAssertThrowsError(try AmbientAudioStore(root: directory))
        XCTAssertTrue(FileManager.default.fileExists(atPath: store.file(batch).path))
        try Data(repeating: 0x20, count: 16_385).write(to: manifest)
        XCTAssertThrowsError(try AmbientAudioStore(root: directory))
        XCTAssertTrue(FileManager.default.fileExists(atPath: store.file(batch).path))
    }

    func testDurableAcknowledgementFinishesInterruptedDeletion() throws {
        let directory = root()
        let store = try AmbientAudioStore(root: directory)
        try store.bind(binding())
        let batch = try enqueue(store)
        try JSONEncoder().encode(batch).write(to: directory.appendingPathComponent(batch.id + ".ack"))
        try FileManager.default.removeItem(at: store.file(batch))
        let restored = try AmbientAudioStore(root: directory)
        XCTAssertTrue(restored.batches.isEmpty)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: directory.path), ["binding.json"])
    }

    func testEnqueueRequiresBoundIdentityAndUniqueSequence() throws {
        let store = try AmbientAudioStore(root: root())
        XCTAssertThrowsError(try enqueue(store))
        XCTAssertThrowsError(try store.bind(AmbientBinding(origin: "https://assistant.example/path", instanceID: instance,
                                                         userID: user, deviceID: device, expires: Date())))
        try store.bind(binding())
        _ = try enqueue(store)
        XCTAssertThrowsError(try enqueue(store))
        XCTAssertThrowsError(try store.enqueue(pcm: pcm, sessionID: "../session", sequence: 1, startedAt: Date()))
        XCTAssertThrowsError(try store.enqueue(pcm: pcm, sessionID: session, sequence: -1, startedAt: Date()))
        XCTAssertEqual(store.batches.count, 1)
    }
}

@MainActor final class AmbientLifecycleTests: XCTestCase {
    private let instance = "10000000-0000-0000-0000-000000000001"
    private let user = "20000000-0000-0000-0000-000000000001"
    private let device = "30000000-0000-0000-0000-000000000001"
    private var root: URL!
    private var binding: AmbientBinding!
    private var connection: AssistantConnection!

    override func setUp() async throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("ambient-lifecycle-" + UUID().uuidString)
        binding = AmbientBinding(origin: "https://assistant.example", instanceID: instance, userID: user,
                                 deviceID: device, expires: Date().addingTimeInterval(3_600))
        let store = try AmbientAudioStore(root: root)
        try store.bind(binding)
        let configuration = ClientConfiguration(instance_id: instance, name: "Synthetic assistant", client_api: 1, shell_api: 1,
                                               entry_path: "/assistant", capabilities: .init(notifications: true, calls: true, account_deletion: true))
        connection = AssistantConnection(id: UUID().uuidString, address: binding.origin, configuration: configuration)
    }

    override func tearDown() async throws { try? FileManager.default.removeItem(at: root) }

    func testStopCancelsStartBeforePermissionReply() async {
        let requested = expectation(description: "Permission requested")
        var permission: CheckedContinuation<Bool, Never>?
        var verifications = 0
        let audio = AmbientAudio(root: root, activateTimers: false, automaticallyUpload: false, permissionLoader: {
            await withCheckedContinuation { permission = $0; requested.fulfill() }
        }, credentialsLoader: { _, _, _ in
            verifications += 1
            return .init(cookie: "synthetic", csrf: "synthetic")
        })
        audio.attach(connection)
        let start = Task { await audio.start() }
        await fulfillment(of: [requested], timeout: 2)
        audio.stop()
        permission?.resume(returning: true)
        await start.value
        XCTAssertFalse(audio.recording)
        XCTAssertFalse(audio.starting)
        XCTAssertEqual(verifications, 0)
    }

    func testStopCancelsStartBeforeVerifiedIdentityReply() async {
        let requested = expectation(description: "Identity requested")
        var verified: CheckedContinuation<AmbientTransport.Credentials, Error>?
        let audio = AmbientAudio(root: root, activateTimers: false, automaticallyUpload: false,
                                 permissionLoader: { true }, credentialsLoader: { _, _, ready in
            XCTAssertTrue(ready)
            return try await withCheckedThrowingContinuation { verified = $0; requested.fulfill() }
        })
        audio.attach(connection)
        let start = Task { await audio.start() }
        await fulfillment(of: [requested], timeout: 2)
        audio.stop()
        verified?.resume(returning: .init(cookie: "synthetic", csrf: "synthetic"))
        await start.value
        XCTAssertFalse(audio.recording)
        XCTAssertFalse(audio.starting)
    }

    func testStopCancelsStartBeforeLocalDetectorIsReady() async throws {
        let requested = expectation(description: "Local detector requested")
        let detector = try AmbientVAD()
        var ready: CheckedContinuation<AmbientVAD, Error>?
        let audio = AmbientAudio(root: root, activateTimers: false, automaticallyUpload: false,
                                 permissionLoader: { true }, credentialsLoader: { _, _, _ in
            .init(cookie: "synthetic", csrf: "synthetic", acceptedFormats: ["aac-adts"])
        }, vadLoader: {
            try await withCheckedThrowingContinuation { ready = $0; requested.fulfill() }
        })
        audio.attach(connection)
        let start = Task { await audio.start() }
        await fulfillment(of: [requested], timeout: 2)
        XCTAssertTrue(audio.starting)
        audio.stop()
        ready?.resume(returning: detector)
        await start.value
        XCTAssertFalse(audio.recording)
        XCTAssertFalse(audio.starting)
        XCTAssertNil(audio.error)
    }

    func testUnavailableDetectorPreventsCaptureWithoutFallback() async {
        let audio = AmbientAudio(root: root, activateTimers: false, automaticallyUpload: false,
                                 permissionLoader: { true }, credentialsLoader: { _, _, _ in
            .init(cookie: "synthetic", csrf: "synthetic", acceptedFormats: ["aac-adts"])
        }, vadLoader: { throw NativeError.code("vad_unavailable") })
        audio.attach(connection)
        await audio.start()
        XCTAssertFalse(audio.recording)
        XCTAssertFalse(audio.starting)
        XCTAssertEqual(audio.error, "vad_unavailable")
    }

    func testClearCancelsPendingStartAndPurgesQueue() async throws {
        let requested = expectation(description: "Permission requested")
        var permission: CheckedContinuation<Bool, Never>?
        let audio = AmbientAudio(root: root, activateTimers: false, automaticallyUpload: false, permissionLoader: {
            await withCheckedContinuation { permission = $0; requested.fulfill() }
        }, credentialsLoader: { _, _, _ in .init(cookie: "synthetic", csrf: "synthetic") })
        audio.attach(connection)
        let start = Task { await audio.start() }
        await fulfillment(of: [requested], timeout: 2)
        _ = try await audio.handle(.init(id: "1", type: "ambient.clear"), connectionID: connection.id)
        permission?.resume(returning: true)
        await start.value
        XCTAssertFalse(audio.recording)
        XCTAssertFalse(audio.starting)
        XCTAssertNil(try AmbientAudioStore(root: root).binding)
        await audio.start()
        XCTAssertEqual(audio.error, "not_authenticated")
    }

    func testAuthenticationLossPreservesFinalizedQueueAndVerifiedSameAccountCanResumeDelivery() async throws {
        let store = try AmbientAudioStore(root: root)
        _ = try store.enqueue(pcm: Data([0, 0]), sessionID: UUID().uuidString, sequence: 0, startedAt: Date())
        let audio = AmbientAudio(root: root, activateTimers: false, automaticallyUpload: false,
                                 credentialsLoader: { _, _, _ in .init(cookie: "synthetic", csrf: "synthetic") })
        audio.attach(connection)
        _ = try await audio.handle(.init(id: "1", type: "ambient.account", payload: ["user_id": NSNull(), "preserve_queue": true]),
                                   connectionID: connection.id)
        XCTAssertEqual(audio.queuedBatches, 1)
        XCTAssertEqual(audio.error, "not_authenticated")
        _ = try await audio.handle(.init(id: "2", type: "ambient.account", payload: [
            "user_id": user, "device_id": device, "expires_at": AmbientAudio.iso(binding.expires),
        ]), connectionID: connection.id)
        XCTAssertEqual(audio.queuedBatches, 1)
        XCTAssertNil(audio.error)
        XCTAssertFalse(audio.recording)
    }

    func testServerCapacityAndConflictsAreTerminalWhileTransientFailuresRetry() {
        XCTAssertEqual(AmbientAudio.uploadFailure(507), "server_rejected")
        XCTAssertEqual(AmbientAudio.uploadFailure(409), "server_rejected")
        XCTAssertEqual(AmbientAudio.uploadFailure(410), "batch_expired")
        XCTAssertEqual(AmbientAudio.uploadFailure(401), "not_authenticated")
        XCTAssertEqual(AmbientAudio.uploadFailure(403), "not_authenticated")
        XCTAssertEqual(AmbientAudio.uploadFailure(408), "network_unavailable")
        XCTAssertEqual(AmbientAudio.uploadFailure(429), "network_unavailable")
        XCTAssertEqual(AmbientAudio.uploadFailure(503), "network_unavailable")
        XCTAssertNil(AmbientAudio.uploadFailure(201))
    }

    func testExplicitClearRepairsCorruptQueueWithoutKeepingItsFiles() throws {
        let orphan = root.appendingPathComponent(UUID().uuidString.lowercased() + ".wav")
        try AmbientAudioStore.wave(Data([0, 0])).write(to: orphan)
        let audio = AmbientAudio(root: root, activateTimers: false, automaticallyUpload: false)
        XCTAssertEqual(audio.error, "queue_corrupt")
        audio.clear()
        XCTAssertNil(audio.error)
        XCTAssertEqual(audio.queuedBatches, 0)
        XCTAssertFalse(FileManager.default.fileExists(atPath: orphan.path))
        XCTAssertNil(try AmbientAudioStore(root: root).binding)
    }

    func testUploadRequestMatchesOwnOriginCookieContractAndAcceptsExtendedServerReceipt() throws {
        let store = try AmbientAudioStore(root: root)
        let batch = try store.enqueue(pcm: Data([0, 0]), sessionID: UUID().uuidString, sequence: 7, startedAt: Date())
        let request = AmbientTransport.uploadRequest(batch: batch, origin: connection.origin,
                                                    credentials: .init(cookie: "synthetic-session", csrf: "synthetic-csrf"))
        XCTAssertEqual(request.url?.absoluteString, "https://assistant.example/v1/ambient-audio/batches")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Origin"), "https://assistant.example")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Expected-User"), user)
        XCTAssertEqual(request.value(forHTTPHeaderField: "Cookie"), "synthetic-session")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-CSRF-Token"), "synthetic-csrf")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "audio/wav")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Audio-Batch-ID"), batch.id)
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Audio-Session-ID"), batch.sessionID)
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Audio-Sequence"), "7")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Audio-SHA256"), batch.sha256)
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Audio-Started-At"), AmbientAudio.iso(batch.startedAt))
        let receipt: [String: Any] = ["id": batch.id, "sha256": batch.sha256, "stored": true,
                                     "session_id": batch.sessionID, "sequence": 7, "size_bytes": batch.bytes,
                                     "duration_ms": 1, "expires_at": NSNull()]
        XCTAssertTrue(AmbientAudioStore.receipt(try JSONSerialization.data(withJSONObject: receipt), batch: batch))
    }
}
