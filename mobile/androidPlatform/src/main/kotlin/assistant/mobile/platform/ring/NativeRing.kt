package assistant.mobile.platform.ring

/** The portable protocol has no JVM callbacks, threads or Android Bluetooth dependency. */
object NativeRing {
    init { System.loadLibrary("herzring") }
    external fun apiVersion(): Int
    external fun create(configJson: ByteArray): Long
    external fun dispatch(handle: Long, inputJson: ByteArray, nowMs: Long): ByteArray
    external fun destroy(handle: Long)
}
