package assistant.mobile.core.personal

data class PersonalDdxOutcome(val error: String? = null, val warning: String? = null, val newAnalyses: Long = 0,
    val updatedAnalyses: Long = 0, val observationsChanged: Long = 0, val batchesCommitted: Long = 0,
    val partial: Boolean = false, val projectionIncomplete: Boolean = false)
data class PersonalDdxRun(val id: String, val sourceId: String, val state: String, val trigger: String? = null,
    val createdAt: String? = null, val startedAt: String? = null, val finishedAt: String? = null,
    val availableAt: String? = null, val attempts: Long? = null, val outcome: PersonalDdxOutcome? = null) {
    val active: Boolean get() = state in setOf("queued", "running")
}
data class PersonalDdxConnection(val generation: Long, val revision: Long, val authState: String,
    val phoneMasked: String?, val automatic: Boolean, val canReconnect: Boolean = true,
    val nextSyncAt: String? = null, val lastAttemptAt: String? = null, val lastSuccessAt: String? = null,
    val lastMeasurementAt: String? = null, val retryAt: String? = null, val error: String? = null,
    val warning: String? = null, val lastRunId: String? = null, val lastResult: PersonalDdxOutcome? = null,
    val activeRun: PersonalDdxRun? = null)

/** A public, allowlisted projection. No provider password, phone or code can enter this state. */
data class PersonalDdxLogin(val id: String, val state: String, val requestKey: String? = null,
    val phoneMasked: String? = null, val expiresAt: String? = null, val resendAt: String? = null,
    val error: String? = null, val sourceId: String? = null, val canResend: Boolean = false) {
    val active: Boolean get() = state in setOf("sending", "waiting_code", "verifying", "staged")
}
data class PersonalDdxStatus(val available: Boolean, val canDisconnect: Boolean, val activeLogin: PersonalDdxLogin?)

/** Machine-defined E.164 input accepts the same Russian formatting as the browser. */
fun personalDdxPhone(input: String): String? {
    var phone = input.trim().filterNot { it.isWhitespace() || it in "()-" }
    if (phone.length == 11 && phone.first() in "78" && phone.all { it in '0'..'9' }) phone = "+7" + phone.drop(1)
    return phone.takeIf { it.length in 11..16 && it.first() == '+' && it[1] in '1'..'9' && it.drop(2).all { c -> c in '0'..'9' } }
}
fun personalDdxCode(input: String): String? = input.trim().takeIf { it.length == 6 && it.all { c -> c in '0'..'9' } }
