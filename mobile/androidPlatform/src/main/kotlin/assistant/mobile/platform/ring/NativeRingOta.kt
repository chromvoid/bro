package assistant.mobile.platform.ring

import assistant.mobile.platform.R

import kotlinx.serialization.json.*

/** Internal typed codec JNI. This class cannot activate or access Bluetooth. */
object NativeRingOta {
    init { System.loadLibrary("herzring") }
    external fun encode(request: ByteArray): ByteArray
}

object RingOtaWire {
    private fun encode(request: JsonObject): ByteArray {
        val reply = ringJson.parseToJsonElement(NativeRingOta.encode(request.toString().toByteArray(Charsets.UTF_8))
            .toString(Charsets.UTF_8)) as? JsonObject ?: throw RingFailure("native_response", RingText.text(R.string.ring_ota_0cf717b8e0f3))
        val encoded = reply.string("bytes_hex") ?: throw RingFailure("ota_frame_invalid", RingText.text(R.string.ring_ota_c3a00f2b2f21))
        if (encoded.length % 2 != 0 || encoded.length > (1024 + 8) * 2) throw RingFailure("native_response", RingText.text(R.string.ring_ota_6f974f9d6c33))
        return ByteArray(encoded.length / 2) { index -> encoded.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }
    fun start() = encode(obj { put("type", "start") })
    fun initialize(image: ByteArray) = encode(obj {
        put("type", "initialize"); put("size_bytes", image.size); put("image_crc16", RingOtaCodec.crc(image))
        put("image_sum16", image.sumOf { it.toInt() and 255 } and 0xffff)
    })
    fun block(image: ByteArray, index: Int): ByteArray {
        require(image.size in 1..RingOtaCodec.MAX_IMAGE_BYTES && index in 1..((image.size + 1023) / 1024))
        val offset = (index - 1) * 1024
        return encode(obj { put("type", "block"); put("index", index)
            put("bytes_hex", image.copyOfRange(offset, minOf(offset + 1024, image.size)).joinToString("") { "%02x".format(it) }) })
    }
    fun check() = encode(obj { put("type", "check") })
    fun end() = encode(obj { put("type", "end") })
}
