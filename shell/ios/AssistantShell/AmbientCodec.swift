import AVFoundation
import Foundation

/// Standard codecs preserve the recorded signal for later analysis. No voice enhancement,
/// activity detection, neural reconstruction or recognition runs during ingestion.
enum AmbientCodec {
    struct Encoded {
        let data: Data
        let format: AmbientAudioFormat
        let frames: Int
    }
    static let sampleRate = 16_000.0
    static let maximumFrames = 30 * 16_000
    static let maximumBytes = 256 * 1_024
    private static let pcmFormat = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: sampleRate,
                                                 channels: 1, interleaved: true)!
    private static let supportsOpusConstrainedVBR: Bool = {
        var stream = AudioStreamBasicDescription()
        stream.mSampleRate = sampleRate; stream.mFormatID = kAudioFormatOpus
        stream.mChannelsPerFrame = 1; stream.mFramesPerPacket = 320
        var native: AudioConverterRef?
        guard AudioConverterNew(pcmFormat.streamDescription, &stream, &native) == noErr,
              let native else { return false }
        defer { AudioConverterDispose(native) }
        var mode = UInt32(kAudioCodecBitRateControlMode_VariableConstrained)
        // Probe with an OSStatus API before using a property setter on older codecs.
        return AudioConverterSetProperty(native, kAudioCodecPropertyBitRateControlMode,
                                         UInt32(MemoryLayout<UInt32>.size), &mode) == noErr
    }()

    static func converter(_ format: AmbientAudioFormat) -> AVAudioConverter? {
        guard format != .wav else { return nil }
        var stream = AudioStreamBasicDescription()
        stream.mSampleRate = sampleRate
        stream.mFormatID = format == .oggOpus ? kAudioFormatOpus : kAudioFormatMPEG4AAC
        stream.mChannelsPerFrame = 1
        stream.mFramesPerPacket = format == .oggOpus ? 320 : 1_024
        guard let output = AVAudioFormat(streamDescription: &stream),
              let converter = AVAudioConverter(from: pcmFormat, to: output) else { return nil }
        let rate = format == .oggOpus ? 24_000 : 32_000
        guard converter.applicableEncodeBitRates?.contains(NSNumber(value: rate)) == true else { return nil }
        converter.bitRateStrategy = AVAudioBitRateStrategy_Constant
        converter.bitRate = rate
        if format == .oggOpus, supportsOpusConstrainedVBR {
            // Constrained VBR saves quiet-period traffic while keeping every captured sample.
            converter.bitRateStrategy = AVAudioBitRateStrategy_VariableConstrained
            if converter.bitRateStrategy != AVAudioBitRateStrategy_VariableConstrained {
                converter.bitRateStrategy = AVAudioBitRateStrategy_Constant
            }
        }
        return converter
    }

    static func preferredFormat(accepted: [String]) throws -> AmbientAudioFormat {
        for format: AmbientAudioFormat in [.oggOpus, .aacADTS] {
            if accepted.contains(format.rawValue), converter(format) != nil { return format }
        }
        throw NativeError.code("codec_unavailable")
    }

    /// Encode one independent clip while the capture engine and resampler continue running.
    /// End-of-stream flushes codec delay; each Ogg clip carries its own pre-skip/end trimming.
    static func encode(_ pcm: Data, format: AmbientAudioFormat) throws -> Encoded {
        let frames = pcm.count / 2
        guard pcm.count.isMultiple(of: 2), (1...maximumFrames).contains(frames),
              let converter = converter(format),
              let input = AVAudioPCMBuffer(pcmFormat: pcmFormat, frameCapacity: AVAudioFrameCount(frames)) else {
            throw NativeError.code("capture_failed")
        }
        input.frameLength = AVAudioFrameCount(frames)
        pcm.withUnsafeBytes { source in
            if let from = source.baseAddress, let to = input.int16ChannelData?[0] { memcpy(to, from, pcm.count) }
        }
        let packetMaximum = converter.maximumOutputPacketSize
        guard (1...8_192).contains(packetMaximum) else { throw NativeError.code("capture_failed") }
        var supplied = false
        var packets: [Data] = []
        var bytes = 0
        var ended = false
        // 30 seconds needs <=1502 Opus packets or <500 AAC packets. Bound output, iterations and RAM.
        for _ in 0..<32 {
            let output = AVAudioCompressedBuffer(format: converter.outputFormat, packetCapacity: 128,
                                                maximumPacketSize: packetMaximum)
            var error: NSError?
            let status = converter.convert(to: output, error: &error) { _, state in
                if supplied { state.pointee = .endOfStream; return nil }
                supplied = true; state.pointee = .haveData; return input
            }
            guard status != .error, error == nil, let descriptions = output.packetDescriptions else {
                throw NativeError.code("capture_failed")
            }
            for index in 0..<Int(output.packetCount) {
                let description = descriptions[index]
                let length = Int(description.mDataByteSize)
                let offset = Int(description.mStartOffset)
                guard length > 0, offset >= 0, offset <= Int(output.byteLength) - length,
                      bytes <= maximumBytes - length, packets.count < 1_600 else { throw NativeError.code("capture_failed") }
                packets.append(Data(bytes: output.data.advanced(by: offset), count: length))
                bytes += length
            }
            if status == .endOfStream { ended = true; break }
            if output.packetCount == 0 { throw NativeError.code("capture_failed") }
        }
        guard ended, !packets.isEmpty else { throw NativeError.code("capture_failed") }
        let data: Data
        if format == .oggOpus {
            let skip = Int(converter.primeInfo.leadingFrames) * 3 // Ogg Opus always counts at 48 kHz.
            guard skip <= Int(UInt16.max) else { throw NativeError.code("capture_failed") }
            data = try ogg(packets: packets, frames: frames, preSkip: skip)
        } else {
            var adts = Data()
            for packet in packets {
                let length = packet.count + 7
                guard length < 8_192 else { throw NativeError.code("capture_failed") }
                // MPEG-4 AAC-LC, 16 kHz, mono, no CRC, one raw data block per access unit.
                adts.append(contentsOf: [0xff, 0xf1, 0x60, UInt8(0x40 | (length >> 11)),
                                         UInt8((length >> 3) & 0xff), UInt8(((length & 7) << 5) | 0x1f), 0xfc])
                adts.append(packet)
            }
            data = adts
        }
        try validate(data, format: format, frames: frames)
        return Encoded(data: data, format: format, frames: frames)
    }

    static func validate(_ data: Data, format: AmbientAudioFormat, frames: Int) throws {
        guard format != .wav, (1...maximumFrames).contains(frames), !data.isEmpty, data.count <= maximumBytes else {
            throw AmbientStoreError.invalidSegment
        }
        switch format {
        case .oggOpus:
            let parsed = try opusPackets(data)
            guard parsed.frames == frames else { throw AmbientStoreError.invalidSegment }
        case .aacADTS:
            let packets = try aacPackets(data)
            let decoded = packets.count * 1_024
            // ADTS cannot signal AAC priming/trimming; preserve capture frames in the manifest.
            // Bound the decoder output including priming to 31 s, and allow <=4096 delay/padding frames.
            guard decoded >= frames, decoded - frames <= 4_096, decoded <= 31 * 16_000 else {
                throw AmbientStoreError.invalidSegment
            }
        case .wav: throw AmbientStoreError.invalidSegment
        }
    }

    struct OpusPackets { let packets: [Data]; let preSkip: Int; let frames: Int }

    /// Read the deliberately narrow ingestion subset: one mono stream, complete packets/page,
    /// bounded tags, valid CRC/sequence, explicit EOS, and exact captured duration.
    static func opusPackets(_ data: Data) throws -> OpusPackets {
        let bytes = [UInt8](data)
        guard bytes.count <= maximumBytes else { throw AmbientStoreError.invalidSegment }
        var offset = 0
        var serial: UInt32?
        var sequence: UInt32 = 0
        var packetIndex = 0
        var packets: [Data] = []
        var preSkip = 0
        var decoded = 0
        var lastPacketSamples = 0
        var lastGranule = 0
        var ended = false
        while offset < bytes.count {
            guard !ended, bytes.count - offset >= 27, Array(bytes[offset..<(offset + 4)]) == [79, 103, 103, 83],
                  bytes[offset + 4] == 0 else { throw AmbientStoreError.invalidSegment }
            let flags = bytes[offset + 5]
            let granule = read(bytes, offset + 6, 8)
            let pageSerial = UInt32(read(bytes, offset + 14, 4))
            let pageSequence = UInt32(read(bytes, offset + 18, 4))
            let expectedCRC = UInt32(read(bytes, offset + 22, 4))
            let count = Int(bytes[offset + 26])
            guard flags & 0xf9 == 0, count > 0, bytes.count - offset >= 27 + count,
                  pageSequence == sequence, serial == nil || serial == pageSerial,
                  (sequence == 0 ? flags == 2 : flags & 2 == 0), granule <= UInt64(Int.max) else {
                throw AmbientStoreError.invalidSegment
            }
            serial = pageSerial; sequence += 1
            let laces = Array(bytes[(offset + 27)..<(offset + 27 + count)])
            let payloadSize = laces.reduce(0) { $0 + Int($1) }
            let end = offset + 27 + count + payloadSize
            guard end <= bytes.count, laces.last != 255 else { throw AmbientStoreError.invalidSegment }
            var page = Array(bytes[offset..<end])
            page.replaceSubrange(22..<26, with: [0, 0, 0, 0])
            guard crc(page) == expectedCRC else { throw AmbientStoreError.invalidSegment }
            var cursor = offset + 27 + count
            var length = 0
            for lace in laces {
                length += Int(lace)
                if lace == 255 { continue }
                guard length > 0 else { throw AmbientStoreError.invalidSegment }
                let packet = Data(bytes[cursor..<(cursor + length)])
                if packetIndex == 0 {
                    let head = [UInt8](packet)
                    guard head.count == 19, packet.prefix(8) == Data("OpusHead".utf8), head[8] == 1,
                          head[9] == 1, read(head, 12, 4) == 16_000, read(head, 16, 2) == 0, head[18] == 0 else {
                        throw AmbientStoreError.invalidSegment
                    }
                    preSkip = Int(read(head, 10, 2))
                } else if packetIndex == 1 {
                    guard packet.count >= 16, packet.count <= 4_096, packet.prefix(8) == Data("OpusTags".utf8) else {
                        throw AmbientStoreError.invalidSegment
                    }
                    let tags = [UInt8](packet)
                    let vendor = Int(read(tags, 8, 4))
                    guard vendor <= packet.count - 16, read(tags, 12 + vendor, 4) == 0 else {
                        throw AmbientStoreError.invalidSegment
                    }
                } else {
                    lastPacketSamples = try opusSamples(packet)
                    decoded += lastPacketSamples
                    guard decoded <= 32 * 48_000, packets.count < 1_600 else { throw AmbientStoreError.invalidSegment }
                    packets.append(packet)
                }
                packetIndex += 1; cursor += length; length = 0
            }
            guard cursor == end else { throw AmbientStoreError.invalidSegment }
            guard pageSequence != 0 || packetIndex == 1,
                  pageSequence != 1 || packetIndex == 2 else { throw AmbientStoreError.invalidSegment }
            let value = Int(granule)
            if packetIndex <= 2 {
                guard value == 0, flags & 4 == 0 else { throw AmbientStoreError.invalidSegment }
            } else if flags & 4 != 0 {
                guard value >= lastGranule, value > preSkip, value <= decoded,
                      value >= decoded - lastPacketSamples, end == bytes.count else {
                    throw AmbientStoreError.invalidSegment
                }
                ended = true
            } else {
                guard value == decoded else { throw AmbientStoreError.invalidSegment }
            }
            lastGranule = value; offset = end
        }
        guard ended, !packets.isEmpty, lastGranule > preSkip, (lastGranule - preSkip).isMultiple(of: 3),
              lastGranule - preSkip <= 30 * 48_000 else { throw AmbientStoreError.invalidSegment }
        return OpusPackets(packets: packets, preSkip: preSkip, frames: (lastGranule - preSkip) / 3)
    }

    static func aacPackets(_ data: Data) throws -> [Data] {
        let bytes = [UInt8](data)
        guard bytes.count <= maximumBytes else { throw AmbientStoreError.invalidSegment }
        var offset = 0
        var packets: [Data] = []
        while offset < bytes.count {
            guard bytes.count - offset >= 7, bytes[offset] == 0xff, bytes[offset + 1] == 0xf1,
                  bytes[offset + 2] >> 6 == 1, (bytes[offset + 2] >> 2) & 15 == 8,
                  ((bytes[offset + 2] & 1) << 2) | (bytes[offset + 3] >> 6) == 1,
                  bytes[offset + 6] & 3 == 0 else { throw AmbientStoreError.invalidSegment }
            let length = (Int(bytes[offset + 3] & 3) << 11) | (Int(bytes[offset + 4]) << 3) | Int(bytes[offset + 5] >> 5)
            guard length > 7, length <= bytes.count - offset, packets.count < 485 else { throw AmbientStoreError.invalidSegment }
            packets.append(Data(bytes[(offset + 7)..<(offset + length)])); offset += length
        }
        guard !packets.isEmpty else { throw AmbientStoreError.invalidSegment }
        return packets
    }

    private static func opusSamples(_ packet: Data) throws -> Int {
        let bytes = [UInt8](packet)
        guard let toc = bytes.first else { throw AmbientStoreError.invalidSegment }
        let config = Int(toc >> 3)
        let perFrame = config >= 16 ? 120 << (config & 3) : config >= 12 ? 480 << (config & 1) : [480, 960, 1_920, 2_880][config & 3]
        let code = toc & 3
        let count = code == 0 ? 1 : code == 3 ? (bytes.count > 1 ? Int(bytes[1] & 63) : 0) : 2
        guard count > 0, count * perFrame <= 5_760 else { throw AmbientStoreError.invalidSegment }
        return count * perFrame
    }

    private static func ogg(packets: [Data], frames: Int, preSkip: Int) throws -> Data {
        var head = Data("OpusHead".utf8)
        head.append(contentsOf: [1, 1]); append(UInt64(preSkip), bytes: 2, to: &head)
        append(16_000, bytes: 4, to: &head); append(0, bytes: 2, to: &head); head.append(0)
        let vendor = Data("AssistantShell".utf8)
        var tags = Data("OpusTags".utf8)
        append(UInt64(vendor.count), bytes: 4, to: &tags); tags.append(vendor); append(0, bytes: 4, to: &tags)
        let serial = UInt32.random(in: 1...UInt32.max)
        var result = Data()
        var sequence: UInt32 = 0
        func page(_ packets: [Data], granule: Int, flags: UInt8) throws {
            var laces: [UInt8] = []
            for packet in packets {
                laces.append(contentsOf: repeatElement(255, count: packet.count / 255))
                laces.append(UInt8(packet.count % 255))
            }
            guard laces.count <= 255 else { throw NativeError.code("capture_failed") }
            var data = Data("OggS".utf8); data.append(contentsOf: [0, flags])
            append(UInt64(granule), bytes: 8, to: &data); append(UInt64(serial), bytes: 4, to: &data)
            append(UInt64(sequence), bytes: 4, to: &data); append(0, bytes: 4, to: &data)
            data.append(UInt8(laces.count)); data.append(contentsOf: laces)
            for packet in packets { data.append(packet) }
            let checksum = crc([UInt8](data))
            for index in 0..<4 { data[22 + index] = UInt8(truncatingIfNeeded: checksum >> (8 * index)) }
            guard result.count <= maximumBytes - data.count else { throw NativeError.code("capture_failed") }
            result.append(data); sequence += 1
        }
        try page([head], granule: 0, flags: 2)
        try page([tags], granule: 0, flags: 0)
        var decoded = 0
        for offset in stride(from: 0, to: packets.count, by: 50) {
            let end = min(offset + 50, packets.count)
            let group = Array(packets[offset..<end])
            for packet in group { decoded += try opusSamples(packet) }
            let final = end == packets.count
            let granule = final ? frames * 3 + preSkip : decoded
            guard granule <= decoded else { throw NativeError.code("capture_failed") }
            try page(group, granule: granule, flags: final ? 4 : 0)
        }
        return result
    }

    private static func append(_ value: UInt64, bytes: Int, to data: inout Data) {
        for index in 0..<bytes { data.append(UInt8(truncatingIfNeeded: value >> (8 * index))) }
    }
    private static func read(_ bytes: [UInt8], _ offset: Int, _ count: Int) -> UInt64 {
        bytes[offset..<(offset + count)].enumerated().reduce(0) { $0 | UInt64($1.element) << (8 * $1.offset) }
    }
    private static let crcTable: [UInt32] = (0..<256).map { index in
        var value = UInt32(index) << 24
        for _ in 0..<8 { value = value & 0x8000_0000 != 0 ? value << 1 ^ 0x04c1_1db7 : value << 1 }
        return value
    }
    private static func crc(_ bytes: [UInt8]) -> UInt32 {
        bytes.reduce(0) { previous, byte in
            previous << 8 ^ crcTable[Int((previous >> 24) ^ UInt32(byte))]
        }
    }
}
