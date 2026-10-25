package assistant.mobile.platform.ring

/** An optional query's rejected write is a native protocol outcome, not a failed collection. */
object RingProtocolWrite {
    suspend fun execute(queryId: String?, write: suspend () -> Unit, complete: (Boolean) -> Unit) {
        val success = try {
            write()
            true
        } catch (failure: RingFailure) {
            if (queryId !in setOf("running_steps:0:0", "declared_capabilities:core_3c") ||
                failure.code !in setOf("write_failed", "gatt_error"))
                throw failure
            false
        }
        complete(success)
    }
}
