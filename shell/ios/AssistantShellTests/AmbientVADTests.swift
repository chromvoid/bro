import Foundation
import CryptoKit
import XCTest
@testable import AssistantShell

final class AmbientVADTests: XCTestCase {
    func testPinnedModelDetectsSyntheticSpeechAndGateDropsLongQuietMargins() throws {
        let fixtureURL = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "ambient-vad-audio", withExtension: "json"))
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: fixtureURL)) as? [String: Any])
        let audio = try XCTUnwrap(Data(base64Encoded: try XCTUnwrap(fixture["pcm16le_base64"] as? String)))
        XCTAssertEqual(audio.count, try XCTUnwrap(fixture["sample_count"] as? Int) * 2)
        XCTAssertEqual(SHA256.hash(data: audio).map { String(format: "%02x", $0) }.joined(), fixture["sha256"] as? String)
        let margin = 32_000
        let captured = Data(repeating: 0, count: margin * 2) + audio + Data(repeating: 0, count: margin * 2)
        let detector = try AmbientVAD()
        var clips: [AmbientSpeechGate.Clip] = []
        let gate = AmbientSpeechGate { clips.append($0) }
        var strong = 0, maximumStrong = 0
        for offset in stride(from: 0, to: captured.count, by: 512 * 2) {
            let frame = captured.subdata(in: offset..<min(captured.count, offset + 512 * 2))
            let probability = try detector.probability(pcm: frame)
            strong = probability >= 0.5 ? strong + 1 : 0
            maximumStrong = max(maximumStrong, strong)
            try gate.consume(pcm: frame, probability: probability)
        }
        gate.finish()
        XCTAssertGreaterThanOrEqual(maximumStrong, 3, "The real model must detect sustained synthetic speech")
        XCTAssertFalse(clips.isEmpty)
        XCTAssertEqual(gate.capturedFrames, Int64(captured.count / 2))
        for clip in clips {
            let start = Int(clip.startFrame)
            XCTAssertGreaterThanOrEqual(start, margin - 5120 - 512)
            XCTAssertLessThanOrEqual(start + clip.pcm.count / 2, margin + audio.count / 2 + 4096 + 512)
            XCTAssertLessThanOrEqual(clip.pcm.count / 2, 480_000)
            XCTAssertEqual(clip.pcm, captured.subdata(in: (start * 2)..<(start * 2 + clip.pcm.count)))
        }
        XCTAssertLessThan(clips.reduce(0) { $0 + $1.pcm.count }, captured.count / 2)
    }

    func testBundledModelRunsContinuousSilenceWithoutAnySavedClip() throws {
        let detector = try AmbientVAD()
        var clips: [AmbientSpeechGate.Clip] = []
        let gate = AmbientSpeechGate { clips.append($0) }
        let silence = Data(repeating: 0, count: 512 * 2)
        for _ in 0..<100 {
            let confidence = try detector.probability(pcm: silence)
            XCTAssertLessThan(confidence, 0.35)
            try gate.consume(pcm: silence, probability: confidence)
        }
        let partial = Data(repeating: 0, count: 137 * 2)
        try gate.consume(pcm: partial, probability: detector.probability(pcm: partial))
        gate.finish()
        XCTAssertEqual(gate.capturedFrames, 100 * 512 + 137)
        XCTAssertTrue(clips.isEmpty)
        XCTAssertLessThanOrEqual(gate.bufferedFrames, 5120 + 1536)
    }

    func testRecurrentStateSurvivesFramesAndExplicitResetMatchesFreshDetector() throws {
        let detector = try AmbientVAD()
        let fresh = try AmbientVAD()
        var samples = [Int16](repeating: 0, count: 512)
        for index in samples.indices {
            let t = Double(index) / 16_000
            samples[index] = Int16(10_000 * sin(2 * .pi * 180 * t) + 2_000 * sin(2 * .pi * 700 * t))
        }
        let frame = samples.withUnsafeBytes { Data($0) }
        let first = try detector.probability(pcm: frame)
        let second = try detector.probability(pcm: frame)
        XCTAssertNotEqual(first, second, "Recurrent state must not reset at each audio frame")
        detector.reset()
        XCTAssertEqual(try detector.probability(pcm: frame), first, accuracy: 1e-6)
        XCTAssertEqual(try fresh.probability(pcm: frame), first, accuracy: 1e-6)
    }

    func testMissingOrCorruptModelFailsClosed() throws {
        XCTAssertThrowsError(try AmbientVAD(modelURL: nil)) { error in
            XCTAssertEqual((error as? NativeError)?.code, "vad_unavailable")
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".onnx")
        defer { try? FileManager.default.removeItem(at: url) }
        try Data("synthetic corrupt model".utf8).write(to: url)
        XCTAssertThrowsError(try AmbientVAD(modelURL: url)) { error in
            XCTAssertEqual((error as? NativeError)?.code, "vad_unavailable")
        }
    }

    func testInvalidFrameCannotBecomeSpeechOrReachInference() throws {
        let detector = try AmbientVAD()
        for invalid in [Data(), Data([0]), Data(repeating: 0, count: 1026)] {
            XCTAssertThrowsError(try detector.probability(pcm: invalid)) { error in
                XCTAssertEqual((error as? NativeError)?.code, "vad_unavailable")
            }
        }
    }
}
