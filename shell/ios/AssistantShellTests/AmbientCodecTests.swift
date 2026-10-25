import AVFoundation
import XCTest
@testable import AssistantShell

final class AmbientCodecTests: XCTestCase {
    private func signal(frames: Int) -> Data {
        var samples = [Int16](repeating: 0, count: frames)
        for index in samples.indices {
            let time = Double(index) / 16_000
            samples[index] = Int16(9_000 * sin(2 * .pi * 440 * time) + 2_000 * sin(2 * .pi * 1_130 * time))
        }
        return samples.withUnsafeBytes { Data($0) }
    }

    private func decode(_ packets: [Data], format: AmbientAudioFormat) throws -> [Float] {
        var stream = AudioStreamBasicDescription()
        stream.mSampleRate = 16_000; stream.mChannelsPerFrame = 1
        stream.mFormatID = format == .oggOpus ? kAudioFormatOpus : kAudioFormatMPEG4AAC
        stream.mFramesPerPacket = format == .oggOpus ? 320 : 1_024
        let compressed = try XCTUnwrap(AVAudioFormat(streamDescription: &stream))
        let pcm = try XCTUnwrap(AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16_000, channels: 1, interleaved: false))
        let converter = try XCTUnwrap(AVAudioConverter(from: compressed, to: pcm))
        let input = AVAudioCompressedBuffer(format: compressed, packetCapacity: AVAudioPacketCount(packets.count),
                                            maximumPacketSize: try XCTUnwrap(packets.map(\.count).max()))
        var offset = 0
        for (index, packet) in packets.enumerated() {
            _ = packet.withUnsafeBytes { source in memcpy(input.data.advanced(by: offset), source.baseAddress!, packet.count) }
            input.packetDescriptions![index] = AudioStreamPacketDescription(mStartOffset: Int64(offset),
                mVariableFramesInPacket: 0, mDataByteSize: UInt32(packet.count))
            offset += packet.count
        }
        input.byteLength = UInt32(offset); input.packetCount = AVAudioPacketCount(packets.count)
        var fed = false
        var samples: [Float] = []
        for _ in 0..<150 {
            let output = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: 4_096))
            var error: NSError?
            let status = converter.convert(to: output, error: &error) { _, state in
                if fed { state.pointee = .endOfStream; return nil }
                fed = true; state.pointee = .haveData; return input
            }
            XCTAssertNil(error)
            XCTAssertNotEqual(status, .error)
            if let values = output.floatChannelData?[0] { samples.append(contentsOf: UnsafeBufferPointer(start: values, count: Int(output.frameLength))) }
            if status == .endOfStream { return samples }
        }
        XCTFail("Decoder did not finish within the bounded output"); return samples
    }

    func testNativeOpusThirtySecondsAndPartialClipRoundTripWithoutContainerPadding() throws {
        guard AmbientCodec.converter(.oggOpus) != nil else { throw XCTSkip("Native Opus unavailable on this runtime") }
        for frames in [30 * 16_000, 4_377] {
            let encoded = try AmbientCodec.encode(signal(frames: frames), format: .oggOpus)
            let parsed = try AmbientCodec.opusPackets(encoded.data)
            XCTAssertEqual(encoded.frames, frames)
            XCTAssertEqual(parsed.frames, frames)
            XCTAssertGreaterThan(parsed.preSkip, 0)
            XCTAssertLessThanOrEqual(encoded.data.count, 110_000)
            let raw = try decode(parsed.packets, format: .oggOpus)
            let skip = parsed.preSkip / 3
            XCTAssertGreaterThanOrEqual(raw.count, frames + skip)
            let trimmed = Array(raw[skip..<(skip + frames)])
            XCTAssertEqual(trimmed.count, frames)
            // A lossy speech codec may change phase. Verify the two original frequencies and
            // their energy survive, without assuming sample-for-sample waveform equality.
            // An independent FFmpeg decode gave the exact frame count but a different phase;
            // direct unaligned SNR therefore measured codec phase as if it were corruption.
            var energy = 0.0
            var projections = [Double](repeating: 0, count: 4)
            for index in trimmed.indices {
                let time = Double(index) / 16_000
                let sample = Double(trimmed[index])
                energy += sample * sample
                projections[0] += sample * sin(2 * .pi * 440 * time)
                projections[1] += sample * cos(2 * .pi * 440 * time)
                projections[2] += sample * sin(2 * .pi * 1_130 * time)
                projections[3] += sample * cos(2 * .pi * 1_130 * time)
            }
            let primary = hypot(projections[0], projections[1]) * 2 / Double(frames)
            let secondary = hypot(projections[2], projections[3]) * 2 / Double(frames)
            XCTAssertTrue((0.15...0.35).contains(primary), "Primary tone amplitude \(primary)")
            XCTAssertTrue((0.02...0.10).contains(secondary), "Secondary tone amplitude \(secondary)")
            XCTAssertTrue((0.15...0.30).contains(secondary / primary), "Tone amplitude ratio \(secondary / primary)")
            let toneEnergy = projections.reduce(0) { $0 + $1 * $1 } * 2 / Double(frames)
            XCTAssertGreaterThan(toneEnergy / max(energy, 1e-12), 0.85)
        }
    }

    func testNativeAACFallbackADTSBoundedThirtySecondsAndPartialClip() throws {
        guard AmbientCodec.converter(.aacADTS) != nil else { throw XCTSkip("Native AAC unavailable on this runtime") }
        for frames in [30 * 16_000, 4_377] {
            let encoded = try AmbientCodec.encode(signal(frames: frames), format: .aacADTS)
            let packets = try AmbientCodec.aacPackets(encoded.data)
            XCTAssertEqual(encoded.frames, frames)
            XCTAssertLessThan(encoded.data.count, 150_000)
            let samples = try decode(packets, format: .aacADTS)
            XCTAssertGreaterThanOrEqual(samples.count, frames)
            XCTAssertLessThanOrEqual(samples.count, 31 * 16_000)
            XCTAssertGreaterThan(samples.reduce(0) { $0 + $1 * $1 }, 1)
        }
    }

    func testConstrainedVBRSilencePreservesAllSamplesWithLessTraffic() throws {
        guard let converter = AmbientCodec.converter(.oggOpus),
              converter.bitRateStrategy == AVAudioBitRateStrategy_VariableConstrained else {
            throw XCTSkip("Native Opus constrained VBR unavailable; compressed CBR remains supported")
        }
        let frames = 30 * 16_000
        let silence = try AmbientCodec.encode(Data(repeating: 0, count: frames * 2), format: .oggOpus)
        let voice = try AmbientCodec.encode(signal(frames: frames), format: .oggOpus)
        let parsed = try AmbientCodec.opusPackets(silence.data)
        XCTAssertEqual(parsed.frames, frames)
        XCTAssertEqual(silence.frames, frames)
        XCTAssertLessThan(silence.data.count, voice.data.count / 2)
        let decoded = try decode(parsed.packets, format: .oggOpus)
        let skip = parsed.preSkip / 3
        XCTAssertGreaterThanOrEqual(decoded.count, frames + skip)
        let retained = decoded[skip..<(skip + frames)]
        XCTAssertEqual(retained.count, frames)
        XCTAssertLessThan(retained.reduce(0) { $0 + $1 * $1 } / Float(frames), 1e-8)
    }

    func testReadinessNegotiatesCompressedFormatsAndPreservesLegacyMIME() throws {
        XCTAssertThrowsError(try AmbientCodec.preferredFormat(accepted: ["wav"]))
        for invalid in ["{}", "{\"accepted_formats\":null}", "{\"accepted_formats\":[1]}"] {
            XCTAssertThrowsError(try AmbientTransport.formats(Data(invalid.utf8)))
        }
        XCTAssertEqual(try AmbientTransport.formats(Data("{\"accepted_formats\":[\"ogg-opus\",\"aac-adts\",\"wav\"]}".utf8)), ["ogg-opus", "aac-adts", "wav"])
        if AmbientCodec.converter(.oggOpus) != nil {
            XCTAssertEqual(try AmbientCodec.preferredFormat(accepted: ["aac-adts", "ogg-opus"]), .oggOpus)
        }
        if AmbientCodec.converter(.aacADTS) != nil {
            XCTAssertEqual(try AmbientCodec.preferredFormat(accepted: ["aac-adts"]), .aacADTS)
        }
        let binding = AmbientBinding(origin: "https://assistant.example", instanceID: UUID().uuidString,
                                     userID: UUID().uuidString, deviceID: UUID().uuidString, expires: Date().addingTimeInterval(60))
        let origin = try XCTUnwrap(ShellOrigin(binding.origin, allowLoopbackHTTP: false))
        for format: AmbientAudioFormat in [.wav, .oggOpus, .aacADTS] {
            let batch = AmbientBatch(id: UUID().uuidString, sessionID: UUID().uuidString, sequence: 0,
                                     startedAt: Date(), sha256: String(repeating: "0", count: 64), bytes: 100,
                                     binding: binding, format: format, frames: 320)
            let request = AmbientTransport.uploadRequest(batch: batch, origin: origin,
                credentials: .init(cookie: "synthetic", csrf: "synthetic"))
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), format.mimeType)
            XCTAssertEqual(request.value(forHTTPHeaderField: "X-Audio-SHA256"), batch.sha256)
        }
    }

    func testDamagedContainersCannotPassEvenWithCorrectClaimedFrameCount() throws {
        guard AmbientCodec.converter(.oggOpus) != nil else { throw XCTSkip("Native Opus unavailable on this runtime") }
        let encoded = try AmbientCodec.encode(signal(frames: 3_200), format: .oggOpus)
        var damaged = encoded.data
        damaged[damaged.count - 1] ^= 1
        for invalid in [damaged, encoded.data.dropLast(), encoded.data + Data([0])] {
            XCTAssertThrowsError(try AmbientCodec.validate(Data(invalid), format: .oggOpus, frames: 3_200))
        }
        XCTAssertThrowsError(try AmbientCodec.validate(encoded.data, format: .oggOpus, frames: 3_201))
        XCTAssertThrowsError(try AmbientCodec.encode(Data(), format: .oggOpus))
        XCTAssertThrowsError(try AmbientCodec.encode(Data(repeating: 0, count: 960_002), format: .oggOpus))
    }

    func testHeaderPagesAndFinalTrimMatchServerContract() throws {
        guard AmbientCodec.converter(.oggOpus) != nil else { throw XCTSkip("Native Opus unavailable on this runtime") }
        let encoded = try AmbientCodec.encode(signal(frames: 3_200), format: .oggOpus)
        let bytes = [UInt8](encoded.data)
        var pages: [[UInt8]] = []
        var offset = 0
        while offset < bytes.count {
            let count = Int(bytes[offset + 26])
            let length = 27 + count + bytes[(offset + 27)..<(offset + 27 + count)].reduce(0) { $0 + Int($1) }
            pages.append(Array(bytes[offset..<(offset + length)])); offset += length
        }
        XCTAssertEqual(pages.count, 3)
        func checked(_ source: [UInt8]) -> [UInt8] {
            var page = source
            page.replaceSubrange(22..<26, with: [0, 0, 0, 0])
            var crc: UInt32 = 0
            for byte in page {
                crc ^= UInt32(byte) << 24
                for _ in 0..<8 { crc = crc & 0x8000_0000 != 0 ? crc << 1 ^ 0x04c1_1db7 : crc << 1 }
            }
            for index in 0..<4 { page[22 + index] = UInt8(truncatingIfNeeded: crc >> (8 * index)) }
            return page
        }
        func combined(_ first: [UInt8], _ second: [UInt8]) -> [UInt8] {
            let a = Int(first[26]), b = Int(second[26])
            var page = Array(first.prefix(27))
            page[5] |= second[5]; page.replaceSubrange(6..<14, with: second[6..<14]); page[26] = UInt8(a + b)
            page.append(contentsOf: first[27..<(27 + a)]); page.append(contentsOf: second[27..<(27 + b)])
            page.append(contentsOf: first.dropFirst(27 + a)); page.append(contentsOf: second.dropFirst(27 + b))
            return checked(page)
        }
        var renumberedAudio = pages[2]
        renumberedAudio.replaceSubrange(18..<22, with: [1, 0, 0, 0])
        let sharedHeaders = Data(combined(pages[0], pages[1]) + checked(renumberedAudio))
        let sharedTagsAndAudio = Data(pages[0] + combined(pages[1], pages[2]))
        XCTAssertThrowsError(try AmbientCodec.opusPackets(sharedHeaders))
        XCTAssertThrowsError(try AmbientCodec.opusPackets(sharedTagsAndAudio))
        var overtrimmed = pages[2]
        let granule = UInt64((3_200 - 321) * 3 + 312)
        for index in 0..<8 { overtrimmed[6 + index] = UInt8(truncatingIfNeeded: granule >> (8 * index)) }
        XCTAssertThrowsError(try AmbientCodec.opusPackets(Data(pages[0] + pages[1] + checked(overtrimmed))))
    }
}
