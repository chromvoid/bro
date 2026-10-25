package assistant.mobile.core.personal

import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.SessionLease
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface PersonalDdxReader {
    suspend fun status(lease: SessionLease): PersonalDdxStatus
    suspend fun login(id: String, lease: SessionLease): PersonalDdxLogin
    suspend fun postLogin(path: String, body: ByteArray, key: String, lease: SessionLease): PersonalDdxLogin
    suspend fun disconnect(sourceId: String, revision: Long, key: String, lease: SessionLease)
}

class NativePersonalDdxApi(private val http: NativeHttpClient) : PersonalDdxReader {
    override suspend fun status(lease: SessionLease): PersonalDdxStatus {
        val row = personalObject(request("GET", "/v1/personal/ddx/status", lease = lease))
        return PersonalDdxStatus(row.sBoolean("available"), row.sBooleanOrNull("can_disconnect") ?: row.sBoolean("available"),
            row.sObjectOrNull("active_login")?.let(::decodePersonalDdxLogin))
    }
    override suspend fun login(id: String, lease: SessionLease): PersonalDdxLogin =
        decodePersonalDdxLogin(personalObject(request("GET", "/v1/personal/ddx/logins/${id.encodeURLParameter()}", lease = lease))
            .getValue("login") as JsonObject).also { require(it.id == id) }

    override suspend fun postLogin(path: String, body: ByteArray, key: String, lease: SessionLease): PersonalDdxLogin {
        require(path == "/v1/personal/ddx/logins" || (path.startsWith("/v1/personal/ddx/logins/") &&
            path.substringAfterLast('/') in setOf("code", "resend", "cancel")))
        // A lifecycle reset may clear the private retry buffer while this request is suspended.
        val sending = body.copyOf()
        return try { decodePersonalDdxLogin(personalObject(request("POST", path, sending, key, lease)).getValue("login") as JsonObject) }
        finally { sending.fill(0) }
    }
    override suspend fun disconnect(sourceId: String, revision: Long, key: String, lease: SessionLease) {
        val body = buildJsonObject { put("expected_revision", revision) }.toString().encodeToByteArray()
        val receipt = personalObject(request("POST", "/v1/personal/ddx/connections/${sourceId.encodeURLParameter()}/disconnect", body, key, lease))
        require(receipt.sText("source_id") == sourceId && receipt.sBoolean("disconnected"))
    }
    private suspend fun request(method: String, path: String, body: ByteArray? = null, key: String? = null, lease: SessionLease): ByteArray {
        val reply = http.request(method, path, body, lease, key)
        if (reply !is HttpOutcome.Success) throw PersonalReadException(reply)
        return reply.body
    }
}

fun decodePersonalDdxLogin(row: JsonObject): PersonalDdxLogin = PersonalDdxLogin(row.sText("id"),
    row.sText("state").also { require(it in setOf("sending", "waiting_code", "verifying", "staged", "ready", "failed", "cancelled", "expired", "interrupted")) },
    row.sTextOrNull("request_key"), row.sTextOrNull("phone_masked"), row.sTextOrNull("expires_at"), row.sTextOrNull("resend_at"),
    row.sTextOrNull("error"), row.sTextOrNull("source_id"), row.sBooleanOrNull("can_resend") ?: false)
fun decodePersonalDdxRun(bytes: ByteArray): PersonalDdxRun = decodePersonalDdxRun(personalObject(bytes))
fun decodePersonalDdxRun(row: JsonObject): PersonalDdxRun = PersonalDdxRun(row.sText("id"), row.sText("source_id"),
    row.sText("state").also { require(it in setOf("queued", "running", "succeeded", "failed", "partial", "cancelled")) },
    row.sTextOrNull("trigger"), row.sTextOrNull("created_at"), row.sTextOrNull("started_at"), row.sTextOrNull("finished_at"),
    row.sTextOrNull("available_at"), row.sLongOrNull("attempts"), row.sObjectOrNull("outcome")?.let(::decodePersonalDdxOutcome))
fun decodePersonalDdxOutcome(row: JsonObject): PersonalDdxOutcome = PersonalDdxOutcome(row.sTextOrNull("error"), row.sTextOrNull("warning"),
    row.sLongOrNull("new_analyses") ?: 0, row.sLongOrNull("updated_analyses") ?: 0, row.sLongOrNull("observations_changed") ?: 0,
    row.sLongOrNull("batches_committed") ?: 0, row.sBooleanOrNull("partial") ?: false, row.sBooleanOrNull("projection_incomplete") ?: false)
fun decodePersonalDdxConnection(row: JsonObject): PersonalDdxConnection {
    require(row.sText("provider") == "ddx")
    return PersonalDdxConnection(row.sLong("generation"), row.sLong("revision"),
        row.sText("auth_state").also { require(it in setOf("active", "auth_required", "disconnected")) },
        row.sTextOrNull("phone_masked"), row.sBoolean("automatic"), row.sBooleanOrNull("can_reconnect") ?: true,
        row.sTextOrNull("next_sync_at"), row.sTextOrNull("last_attempt_at"), row.sTextOrNull("last_success_at"),
        row.sTextOrNull("last_measurement_at"), row.sTextOrNull("retry_at"), row.sTextOrNull("error"), row.sTextOrNull("warning"),
        row.sTextOrNull("last_run_id"), row.sObjectOrNull("last_result")?.let(::decodePersonalDdxOutcome),
        row.sObjectOrNull("active_run")?.let(::decodePersonalDdxRun))
}
