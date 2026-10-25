import AVFoundation
import Darwin
import Foundation

/// Native capture remains independent of the WebView. A single running engine feeds the
/// converter across file boundaries; rotating a batch never stops or reopens the microphone.
final class AmbientRecorder {
    static let sampleRate = 16_000.0
    private let engine = AVAudioEngine()
    private let worker = DispatchQueue(label: "assistant.ambient.encode")
    private let lock = NSLock()
    private var accepting = false
    private var pendingBuffers = 0
    private var converter: AVAudioConverter?
    private let output = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: sampleRate,
                                       channels: 1, interleaved: true)!
    private var pendingPCM = Data()
    private var failed = false
    private let vad: AmbientVAD
    private lazy var gate = AmbientSpeechGate { [unowned self] clip in
        do { try emit(clip) } catch { fail("capture_failed") }
    }
    private var sequence = 0
    private let sessionID = UUID().uuidString.lowercased()
    private var startedAt: Date?
    private let format: AmbientAudioFormat
    private let segment: (AmbientCodec.Encoded, String, Int, Date) -> Void
    private let failure: (String) -> Void

    init(format: AmbientAudioFormat, vad: AmbientVAD,
         segment: @escaping (AmbientCodec.Encoded, String, Int, Date) -> Void,
         failure: @escaping (String) -> Void) {
        self.format = format
        self.vad = vad
        self.segment = segment
        self.failure = failure
    }

    func start() throws {
        let audio = AVAudioSession.sharedInstance()
        try audio.setCategory(.playAndRecord, mode: .measurement, options: [.mixWithOthers])
        try audio.setActive(true)
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0,
              let conversion = AVAudioConverter(from: format, to: output) else {
            try? audio.setActive(false, options: [.notifyOthersOnDeactivation])
            throw NativeError.code("capture_failed")
        }
        converter = conversion
        lock.lock(); accepting = true; lock.unlock()
        input.installTap(onBus: 0, bufferSize: 2_048, format: format) { [weak self] buffer, time in
            self?.receive(buffer, time: time)
        }
        do { try engine.start() } catch {
            lock.lock(); accepting = false; lock.unlock()
            input.removeTap(onBus: 0)
            try? audio.setActive(false, options: [.notifyOthersOnDeactivation])
            throw error
        }
    }

    private func receive(_ buffer: AVAudioPCMBuffer, time: AVAudioTime) {
        // The tap owns its buffer only for this callback. Copy it before dispatching and bound
        // queued work, so a stalled writer never turns continuous capture into unbounded RAM.
        lock.lock()
        guard accepting else { lock.unlock(); return }
        guard pendingBuffers < 32 else {
            accepting = false; lock.unlock(); failure("capture_failed"); return
        }
        pendingBuffers += 1
        lock.unlock()
        guard let copy = AVAudioPCMBuffer(pcmFormat: buffer.format, frameCapacity: buffer.frameLength) else {
            releaseBuffer(); failure("capture_failed"); return
        }
        copy.frameLength = buffer.frameLength
        let sources = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: buffer.audioBufferList))
        let destinations = UnsafeMutableAudioBufferListPointer(copy.mutableAudioBufferList)
        for (source, target) in zip(sources, destinations) {
            if let from = source.mData, let to = target.mData { memcpy(to, from, Int(source.mDataByteSize)) }
        }
        // Anchor the source clock to the first actual tap, compensating callback delivery delay.
        // Encoding, VAD decisions and discarded silence never change this capture timeline.
        let capturedAt = time.isHostTimeValid
            ? Date().addingTimeInterval(AVAudioTime.seconds(forHostTime: time.hostTime)
                                        - AVAudioTime.seconds(forHostTime: mach_absolute_time())) : Date()
        worker.async { [weak self] in
            guard let self else { return }
            defer { releaseBuffer() }
            guard !failed else { return }
            if startedAt == nil { startedAt = capturedAt }
            do { try convert(copy) }
            catch { fail((error as? NativeError)?.code ?? "capture_failed") }
        }
    }

    private func releaseBuffer() { lock.lock(); pendingBuffers -= 1; lock.unlock() }

    private func convert(_ input: AVAudioPCMBuffer) throws {
        guard let converter else { return }
        let capacity = AVAudioFrameCount(ceil(Double(input.frameLength) * Self.sampleRate / input.format.sampleRate) + 1_024)
        guard let converted = AVAudioPCMBuffer(pcmFormat: output, frameCapacity: capacity) else {
            throw NativeError.code("capture_failed")
        }
        var supplied = false
        var error: NSError?
        let status = converter.convert(to: converted, error: &error) { _, state in
            if supplied { state.pointee = .noDataNow; return nil }
            supplied = true; state.pointee = .haveData; return input
        }
        guard status != .error, error == nil else { throw error ?? NativeError.code("capture_failed") }
        if converted.frameLength > 0, let samples = converted.int16ChannelData?[0] {
            try append(Data(bytes: samples, count: Int(converted.frameLength) * 2))
        }
    }

    private func append(_ data: Data) throws {
        pendingPCM.append(data)
        let bytes = AmbientVAD.frameSamples * 2
        while pendingPCM.count >= bytes, !failed {
            let frame = Data(pendingPCM.prefix(bytes))
            pendingPCM.removeFirst(bytes)
            try gate.consume(pcm: frame, probability: vad.probability(pcm: frame))
        }
    }

    private func emit(_ clip: AmbientSpeechGate.Clip) throws {
        guard let startedAt, !clip.pcm.isEmpty else { return }
        let date = startedAt.addingTimeInterval(Double(clip.startFrame) / Self.sampleRate)
        let current = sequence
        sequence += 1
        segment(try AmbientCodec.encode(clip.pcm, format: format), sessionID, current, date)
    }

    private func fail(_ reason: String) {
        guard !failed else { return }
        failed = true
        lock.lock(); accepting = false; lock.unlock()
        failure(reason)
    }

    /// Wait for copied tap buffers asynchronously, then emit the final partial segment. Account
    /// invalidation uses discard: the caller's generation guard also rejects already queued events.
    func stop(discard: Bool = false, completion: (() -> Void)? = nil) {
        lock.lock(); let wasAccepting = accepting; accepting = false; lock.unlock()
        if wasAccepting || engine.isRunning {
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
        }
        try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
        worker.async { [self] in
            if !discard, !failed, !pendingPCM.isEmpty {
                do { try gate.consume(pcm: pendingPCM, probability: vad.probability(pcm: pendingPCM)) }
                catch { fail((error as? NativeError)?.code ?? "capture_failed") }
            }
            gate.finish(discard: discard)
            pendingPCM = Data(); converter = nil
            completion?()
        }
    }
}
