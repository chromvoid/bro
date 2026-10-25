import Foundation

/// Serialized PCM selection only. The detector, codec and persistent queue are owned by the caller.
final class AmbientSpeechGate {
    struct Clip: Equatable {
        let pcm: Data
        let startFrame: Int64
    }

    enum GateError: Error, Equatable {
        case invalidPCM
        case invalidProbability
        case finished
    }

    static let frameSamples = 512
    static let preRollSamples = 5_120
    static let startSamples = 1_536
    static let postRollSamples = 4_096
    static let quietTimeoutSamples = 12_800
    static let maximumClipSamples = 480_000
    static let startThreshold: Float = 0.5
    static let continueThreshold: Float = 0.35

    private(set) var capturedFrames: Int64 = 0
    var bufferedFrames: Int { (history.count + candidate.count + phrase.count + quiet.count) / 2 }

    private let emit: (Clip) -> Void
    private var history = Data()
    private var candidate = Data()
    private var candidateStart: Int64 = 0
    private var active = false
    private var phrase = Data()
    private var phraseStart: Int64 = 0
    private var quiet = Data()
    private var quietStart: Int64 = 0
    private var finished = false

    init(emit: @escaping (Clip) -> Void) { self.emit = emit }

    /// One classified window, or real samples from the final partial window. Detector padding
    /// must stay outside this method: it must never advance the clock or reach a retained clip.
    func consume(pcm: Data, probability: Float) throws {
        guard !finished else { throw GateError.finished }
        guard !pcm.isEmpty, pcm.count.isMultiple(of: 2), pcm.count <= Self.frameSamples * 2,
              capturedFrames <= Int64.max - Int64(pcm.count / 2) else { throw GateError.invalidPCM }
        guard probability.isFinite, (0...1).contains(probability) else { throw GateError.invalidProbability }
        let start = capturedFrames
        capturedFrames += Int64(pcm.count / 2)
        if active { consumeActive(pcm, probability: probability, start: start) }
        else { consumeIdle(pcm, probability: probability, start: start) }
    }

    /// Keep only confirmed speech plus available real post-roll. Repeated finish calls are harmless.
    /// Discard drops pending PCM; already emitted clips remain under the caller's generation guard.
    func finish(discard: Bool = false) {
        guard !finished else { return }
        finished = true
        if active, !discard {
            appendActive(Data(quiet.prefix(Self.postRollSamples * 2)), start: quietStart)
            emitPhrase()
        }
        history = Data(); candidate = Data(); phrase = Data(); quiet = Data()
        active = false
    }

    private func consumeIdle(_ pcm: Data, probability: Float, start: Int64) {
        guard probability >= Self.startThreshold else {
            remember(candidate)
            candidate = Data()
            remember(pcm)
            return
        }
        if candidate.isEmpty { candidateStart = start }
        let count = min(Self.startSamples - candidate.count / 2, pcm.count / 2)
        candidate.append(pcm.prefix(count * 2))
        guard candidate.count / 2 == Self.startSamples else { return }

        // Preserve the context before the first high window, rather than before confirmation.
        active = true
        appendActive(history, start: candidateStart - Int64(history.count / 2))
        appendActive(candidate, start: candidateStart)
        history = Data(); candidate = Data()
        appendActive(Data(pcm.dropFirst(count * 2)), start: start + Int64(count))
    }

    private func consumeActive(_ pcm: Data, probability: Float, start: Int64) {
        if probability >= Self.continueThreshold {
            appendActive(quiet, start: quietStart)
            quiet = Data()
            appendActive(pcm, start: start)
            return
        }
        if quiet.isEmpty { quietStart = start }
        let count = min(Self.quietTimeoutSamples - quiet.count / 2, pcm.count / 2)
        quiet.append(pcm.prefix(count * 2))
        guard quiet.count / 2 == Self.quietTimeoutSamples else { return }

        appendActive(Data(quiet.prefix(Self.postRollSamples * 2)), start: quietStart)
        emitPhrase()
        active = false
        // Discard the middle silence but retain bounded context immediately before any next onset.
        remember(Data(quiet.dropFirst(Self.postRollSamples * 2)))
        quiet = Data()
        remember(Data(pcm.dropFirst(count * 2)))
    }

    private func remember(_ pcm: Data) {
        history.append(pcm)
        let maximum = Self.preRollSamples * 2
        if history.count > maximum { history.removeFirst(history.count - maximum) }
    }

    private func appendActive(_ pcm: Data, start: Int64) {
        var offset = 0
        while offset < pcm.count / 2 {
            if phrase.isEmpty { phraseStart = start + Int64(offset) }
            let count = min(Self.maximumClipSamples - phrase.count / 2, pcm.count / 2 - offset)
            phrase.append(Data(pcm.dropFirst(offset * 2).prefix(count * 2)))
            offset += count
            if phrase.count / 2 == Self.maximumClipSamples { emitPhrase() }
        }
    }

    private func emitPhrase() {
        guard !phrase.isEmpty else { return }
        let clip = Clip(pcm: phrase, startFrame: phraseStart)
        phrase = Data()
        emit(clip)
    }
}
