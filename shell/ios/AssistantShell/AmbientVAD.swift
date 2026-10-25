import CryptoKit
import Foundation
import OnnxRuntimeBindings

/// The pinned Silero model runs only on the capture worker. Its recurrent state and 64-sample
/// context persist through silence and file boundaries; a new capture gets a fresh detector.
final class AmbientVAD {
    static let modelName = "silero-v6.2.1"
    static let modelSHA256 = "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3"
    static let frameSamples = 512
    private static let contextSamples = 64
    private let environment: ORTEnv
    private let session: ORTSession
    private let inputs: [String: ORTValue]
    private let outputs: [String: ORTValue]
    private let inputBytes = NSMutableData(length: (512 + 64) * MemoryLayout<Float>.size)!
    private let stateBytes = NSMutableData(length: 2 * 128 * MemoryLayout<Float>.size)!
    private let nextStateBytes = NSMutableData(length: 2 * 128 * MemoryLayout<Float>.size)!
    private let probabilityBytes = NSMutableData(length: MemoryLayout<Float>.size)!
    private var context = [Float](repeating: 0, count: contextSamples)

    init(modelURL: URL? = Bundle.main.url(forResource: AmbientVAD.modelName, withExtension: "onnx")) throws {
        do {
            guard let modelURL else { throw NativeError.code("vad_unavailable") }
            let model = try Data(contentsOf: modelURL, options: .mappedIfSafe)
            guard SHA256.hash(data: model).map({ String(format: "%02x", $0) }).joined() == Self.modelSHA256 else {
                throw NativeError.code("vad_unavailable")
            }
            environment = try ORTEnv(loggingLevel: .error)
            let options = try ORTSessionOptions()
            try options.setIntraOpNumThreads(1)
            try options.addConfigEntry(withKey: "session.intra_op.allow_spinning", value: "0")
            try options.addConfigEntry(withKey: "session.inter_op.allow_spinning", value: "0")
            // No hardware provider is registered: CPU runs this small recurrent model with no
            // provider partition transfers. ORT's Apple platform telemetry provider is a stub;
            // its Objective-C environment API exposes no telemetry/network configuration.
            session = try ORTSession(env: environment, modelPath: modelURL.path, sessionOptions: options)
            var rate = Int64(16_000)
            let rateBytes = NSMutableData(bytes: &rate, length: MemoryLayout<Int64>.size)
            inputs = [
                "input": try ORTValue(tensorData: inputBytes, elementType: .float, shape: [1, 576]),
                "state": try ORTValue(tensorData: stateBytes, elementType: .float, shape: [2, 1, 128]),
                "sr": try ORTValue(tensorData: rateBytes, elementType: .int64, shape: [])
            ]
            outputs = [
                "output": try ORTValue(tensorData: probabilityBytes, elementType: .float, shape: [1, 1]),
                "stateN": try ORTValue(tensorData: nextStateBytes, elementType: .float, shape: [2, 1, 128])
            ]
            guard Set(try session.inputNames()) == Set(inputs.keys),
                  Set(try session.outputNames()) == Set(outputs.keys) else { throw NativeError.code("vad_unavailable") }
            // Verify the bundled graph, shapes and inference before opening the microphone.
            _ = try probability(pcm: Data(repeating: 0, count: Self.frameSamples * 2))
            reset()
        } catch { throw NativeError.code("vad_unavailable") }
    }

    /// A final short window is zero padded for inference only. The gate receives its original
    /// byte count, so this padding cannot confirm a short candidate or enter persisted audio.
    func probability(pcm: Data) throws -> Float {
        do {
            guard pcm.count > 0, pcm.count <= Self.frameSamples * 2, pcm.count % 2 == 0 else {
                throw NativeError.code("vad_unavailable")
            }
            let input = inputBytes.mutableBytes.assumingMemoryBound(to: Float.self)
            for index in 0..<Self.contextSamples { input[index] = context[index] }
            for index in 0..<Self.frameSamples { input[Self.contextSamples + index] = 0 }
            pcm.withUnsafeBytes { bytes in
                for index in 0..<(pcm.count / 2) {
                    input[Self.contextSamples + index] = Float(bytes.loadUnaligned(fromByteOffset: index * 2, as: Int16.self)) / 32_768
                }
            }
            try session.run(withInputs: inputs, outputs: outputs, runOptions: nil)
            let confidence = probabilityBytes.bytes.assumingMemoryBound(to: Float.self).pointee
            let state = nextStateBytes.bytes.assumingMemoryBound(to: Float.self)
            guard confidence.isFinite, (0...1).contains(confidence),
                  (0..<(2 * 128)).allSatisfy({ state[$0].isFinite }) else { throw NativeError.code("vad_unavailable") }
            memcpy(stateBytes.mutableBytes, nextStateBytes.bytes, stateBytes.length)
            for index in 0..<Self.contextSamples { context[index] = input[Self.frameSamples + index] }
            return confidence
        } catch { throw NativeError.code("vad_unavailable") }
    }

    func reset() {
        memset(stateBytes.mutableBytes, 0, stateBytes.length)
        memset(nextStateBytes.mutableBytes, 0, nextStateBytes.length)
        context = [Float](repeating: 0, count: Self.contextSamples)
    }
}
