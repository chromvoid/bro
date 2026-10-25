import XCTest
@testable import AssistantShell

final class AmbientSpeechGateTests: XCTestCase {
    private struct Specification: Decodable {
        let version: Int
        let sample_rate: Int
        let frame_samples: Int
        let start_threshold: Float
        let continue_threshold: Float
        let min_speech_samples: Int
        let pre_roll_samples: Int
        let end_silence_samples: Int
        let post_roll_samples: Int
        let max_clip_samples: Int
        let cases: [Scenario]
    }

    private struct Scenario: Decodable {
        let name: String
        let runs: [Run]
        let expected_clips: [Expected]
        let tail_samples: Int?
        let tail_probability: Float?
        let discard: Bool?
    }

    private struct Run: Decodable { let probability: Float; let frames: Int }
    private struct Expected: Decodable { let start_sample: Int64; let sample_count: Int }
    private struct Result { let clips: [AmbientSpeechGate.Clip]; let source: Data; let captured: Int64 }

    private func specification() throws -> Specification {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "ambient-vad", withExtension: "json"))
        return try JSONDecoder().decode(Specification.self, from: Data(contentsOf: url))
    }

    func testSharedContractMatchesPolicyAndExactSourceSlices() throws {
        let specification = try specification()
        XCTAssertEqual(specification.version, 1)
        XCTAssertEqual(specification.sample_rate, 16_000)
        XCTAssertEqual(specification.frame_samples, AmbientSpeechGate.frameSamples)
        XCTAssertEqual(specification.start_threshold, AmbientSpeechGate.startThreshold)
        XCTAssertEqual(specification.continue_threshold, AmbientSpeechGate.continueThreshold)
        XCTAssertEqual(specification.min_speech_samples, AmbientSpeechGate.startSamples)
        XCTAssertEqual(specification.pre_roll_samples, AmbientSpeechGate.preRollSamples)
        XCTAssertEqual(specification.end_silence_samples, AmbientSpeechGate.quietTimeoutSamples)
        XCTAssertEqual(specification.post_roll_samples, AmbientSpeechGate.postRollSamples)
        XCTAssertEqual(specification.max_clip_samples, AmbientSpeechGate.maximumClipSamples)
        XCTAssertFalse(specification.cases.isEmpty)
        for scenario in specification.cases {
            let result = try run(scenario)
            XCTAssertEqual(result.clips.count, scenario.expected_clips.count, scenario.name)
            XCTAssertEqual(result.captured, Int64(result.source.count / 2), scenario.name)
            for (clip, expected) in zip(result.clips, scenario.expected_clips) {
                XCTAssertEqual(clip.startFrame, expected.start_sample, scenario.name)
                XCTAssertEqual(clip.pcm.count / 2, expected.sample_count, scenario.name)
                XCTAssertEqual(clip.pcm, slice(result.source, start: expected.start_sample, frames: expected.sample_count), scenario.name)
            }
        }
    }

    func testChangingPCMChunkBoundariesDoesNotChangeSelection() throws {
        for scenario in try specification().cases {
            let original = try run(scenario)
            let fragmented = try run(scenario, chunkSamples: 137)
            XCTAssertEqual(fragmented.clips, original.clips, scenario.name)
            XCTAssertEqual(fragmented.source, original.source, scenario.name)
            XCTAssertEqual(fragmented.captured, original.captured, scenario.name)
        }
    }

    func testIdleAndRejectedCandidatesKeepOnlyBoundedHistory() throws {
        var clips: [AmbientSpeechGate.Clip] = []
        let gate = AmbientSpeechGate { clips.append($0) }
        var source = Data()
        for _ in 0..<100 {
            try feed(gate, frames: 20 * 512, probability: 0, source: &source)
            XCTAssertLessThanOrEqual(gate.bufferedFrames, AmbientSpeechGate.preRollSamples)
            try feed(gate, frames: 2 * 512, probability: 0.9, source: &source)
            XCTAssertLessThanOrEqual(gate.bufferedFrames, AmbientSpeechGate.preRollSamples + AmbientSpeechGate.startSamples)
            try feed(gate, frames: 512, probability: 0.49, source: &source)
            XCTAssertLessThanOrEqual(gate.bufferedFrames, AmbientSpeechGate.preRollSamples)
        }
        gate.finish()
        XCTAssertTrue(clips.isEmpty)
        XCTAssertEqual(gate.bufferedFrames, 0)
        XCTAssertEqual(gate.capturedFrames, Int64(source.count / 2))
    }

    func testPartialThirdHighWindowCannotFabricateMinimumSpeechDuration() throws {
        var clips: [AmbientSpeechGate.Clip] = []
        let gate = AmbientSpeechGate { clips.append($0) }
        var source = Data()
        try feed(gate, frames: 20 * 512, probability: 0, source: &source)
        try feed(gate, frames: 1_535, probability: 1, source: &source)
        gate.finish()
        XCTAssertTrue(clips.isEmpty)
        XCTAssertEqual(gate.capturedFrames, 20 * 512 + 1_535)
    }

    func testInclusiveThresholdsAndPauseOneSampleBelowTimeoutPreserveAllPCM() throws {
        var clips: [AmbientSpeechGate.Clip] = []
        let gate = AmbientSpeechGate { clips.append($0) }
        var source = Data()
        try feed(gate, frames: 1_536, probability: 0.5, source: &source)
        try feed(gate, frames: 12_799, probability: 0.349, source: &source)
        XCTAssertTrue(clips.isEmpty)
        try feed(gate, frames: 1, probability: 0.35, source: &source)
        try feed(gate, frames: 12_800, probability: 0, source: &source)
        gate.finish()
        XCTAssertEqual(clips.count, 1)
        let clip = try XCTUnwrap(clips.first)
        XCTAssertEqual(clip.startFrame, 0)
        XCTAssertEqual(clip.pcm.count / 2, 18_432)
        XCTAssertEqual(clip.pcm, slice(source, start: 0, frames: 18_432))
    }

    func testThirtySecondSplitDuringPostRollKeepsContextWithoutOverlap() throws {
        var clips: [AmbientSpeechGate.Clip] = []
        let gate = AmbientSpeechGate { clips.append($0) }
        var source = Data()
        try feed(gate, frames: 479_000, probability: 0.9, source: &source)
        try feed(gate, frames: 12_800, probability: 0, source: &source)
        gate.finish()
        XCTAssertEqual(clips.count, 2)
        XCTAssertEqual(clips.map(\.startFrame), [0, 480_000])
        XCTAssertEqual(clips.map { $0.pcm.count / 2 }, [480_000, 3_096])
        for clip in clips {
            XCTAssertEqual(clip.pcm, slice(source, start: clip.startFrame, frames: clip.pcm.count / 2))
        }
        var joined = Data()
        for clip in clips { joined.append(clip.pcm) }
        XCTAssertEqual(joined, slice(source, start: 0, frames: 483_096))
    }

    func testExactThirtySecondSpeechProducesNoEmptyFinalClip() throws {
        var clips: [AmbientSpeechGate.Clip] = []
        let gate = AmbientSpeechGate { clips.append($0) }
        var source = Data()
        try feed(gate, frames: 480_000, probability: 0.9, source: &source)
        gate.finish()
        gate.finish()
        XCTAssertEqual(clips.count, 1)
        XCTAssertEqual(clips.first?.pcm, source)
        XCTAssertEqual(gate.bufferedFrames, 0)
    }

    func testDiscardDropsPendingSpeechAndFinishCannotEmitTwice() throws {
        var clips: [AmbientSpeechGate.Clip] = []
        let gate = AmbientSpeechGate { clips.append($0) }
        var source = Data()
        try feed(gate, frames: 1_536, probability: 0.9, source: &source)
        try feed(gate, frames: 137, probability: 0, source: &source)
        gate.finish(discard: true)
        gate.finish()
        XCTAssertTrue(clips.isEmpty)
        XCTAssertEqual(gate.bufferedFrames, 0)
        XCTAssertThrowsError(try gate.consume(pcm: Data([0, 0]), probability: 0.9)) { error in
            XCTAssertEqual(error as? AmbientSpeechGate.GateError, .finished)
        }
    }

    func testInvalidInputDoesNotAdvanceClockOrMutateBuffers() throws {
        let gate = AmbientSpeechGate { _ in XCTFail("Invalid input must not emit a clip") }
        for data in [Data(), Data([0]), Data(repeating: 0, count: 1_026)] {
            XCTAssertThrowsError(try gate.consume(pcm: data, probability: 0.9)) { error in
                XCTAssertEqual(error as? AmbientSpeechGate.GateError, .invalidPCM)
            }
        }
        for probability: Float in [.nan, .infinity, -.infinity, -0.01, 1.01] {
            XCTAssertThrowsError(try gate.consume(pcm: Data([0, 0]), probability: probability)) { error in
                XCTAssertEqual(error as? AmbientSpeechGate.GateError, .invalidProbability)
            }
        }
        XCTAssertEqual(gate.capturedFrames, 0)
        XCTAssertEqual(gate.bufferedFrames, 0)
    }

    private func run(_ scenario: Scenario, chunkSamples: Int = 512) throws -> Result {
        var clips: [AmbientSpeechGate.Clip] = []
        let gate = AmbientSpeechGate { clips.append($0) }
        var source = Data()
        for run in scenario.runs {
            try feed(gate, frames: run.frames * 512, probability: run.probability,
                     source: &source, chunkSamples: chunkSamples)
        }
        if let frames = scenario.tail_samples {
            try feed(gate, frames: frames, probability: scenario.tail_probability ?? 0,
                     source: &source, chunkSamples: chunkSamples)
        }
        gate.finish(discard: scenario.discard ?? false)
        XCTAssertEqual(gate.bufferedFrames, 0, scenario.name)
        return Result(clips: clips, source: source, captured: gate.capturedFrames)
    }

    private func feed(_ gate: AmbientSpeechGate, frames: Int, probability: Float,
                      source: inout Data, chunkSamples: Int = 512) throws {
        var remaining = frames
        while remaining > 0 {
            let count = min(remaining, chunkSamples)
            let pcm = markers(start: source.count / 2, frames: count)
            source.append(pcm)
            try gate.consume(pcm: pcm, probability: probability)
            XCTAssertLessThanOrEqual(gate.bufferedFrames,
                                     AmbientSpeechGate.maximumClipSamples + AmbientSpeechGate.quietTimeoutSamples)
            remaining -= count
        }
    }

    private func markers(start: Int, frames: Int) -> Data {
        var bytes: [UInt8] = []
        bytes.reserveCapacity(frames * 2)
        for index in start..<(start + frames) {
            let value = UInt16(truncatingIfNeeded: (index &* 7_919) ^ (index >> 9))
            bytes.append(UInt8(truncatingIfNeeded: value))
            bytes.append(UInt8(truncatingIfNeeded: value >> 8))
        }
        return Data(bytes)
    }

    private func slice(_ source: Data, start: Int64, frames: Int) -> Data {
        Data(source.dropFirst(Int(start) * 2).prefix(frames * 2))
    }
}
