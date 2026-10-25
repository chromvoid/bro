package assistant.mobile.platform

import android.content.Context
import android.util.AtomicFile
import assistant.mobile.core.NativeIdentity
import assistant.mobile.core.NativeJson
import assistant.mobile.core.OriginMoveConfigResult
import assistant.mobile.core.OriginMoveMeResult
import assistant.mobile.core.OriginMoveProbe
import assistant.mobile.core.OriginMoveRecord
import assistant.mobile.core.OriginMoveRecordStore
import assistant.mobile.core.StoredSession
import assistant.mobile.core.canonicalOrigin
import assistant.mobile.core.decodeOriginMoveRecord
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** One non-secret decision; AtomicFile is the only commit point of a move. */
class AndroidOriginMoveRecordStore(
    context: Context,
    private val allowInsecureLoopback: Boolean = false,
) : OriginMoveRecordStore {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "native-origin-move.json"))

    @Synchronized
    override fun read(): OriginMoveRecord? = runCatching {
        decodeOriginMoveRecord(file.readFully(), allowInsecureLoopback)
    }.getOrNull()

    @Synchronized
    override fun write(record: OriginMoveRecord) {
        val output = file.startWrite()
        try {
            output.write(NativeJson.encodeToString(OriginMoveRecord.serializer(), record).encodeToByteArray())
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }
}

/** A separate client cannot expire, refresh or save the session it verifies. */
class AndroidOriginMoveProbe(
    currentOrigin: String,
    allowInsecureLoopback: Boolean = false,
) : OriginMoveProbe {
    private val currentOrigin = canonicalOrigin(currentOrigin, allowInsecureLoopback)
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override suspend fun config(origin: String): OriginMoveConfigResult = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url("$origin/v1/client/config").get().build()).execute().use { response ->
                if (response.code != 200) return@withContext OriginMoveConfigResult.Unavailable
                val body = response.body?.string() ?: return@withContext OriginMoveConfigResult.Unavailable
                val instanceId = NativeJson.parseToJsonElement(body).jsonObject
                    .getValue("instance_id").jsonPrimitive.content
                val canonicalId = UUID.fromString(instanceId).toString()
                if (!canonicalId.equals(instanceId, ignoreCase = true)) return@withContext OriginMoveConfigResult.Unavailable
                OriginMoveConfigResult.Available(canonicalId)
            }
        } catch (_: Exception) {
            OriginMoveConfigResult.Unavailable
        }
    }

    override suspend fun me(origin: String, session: StoredSession): OriginMoveMeResult = withContext(Dispatchers.IO) {
        // Even a mistaken caller cannot disclose a credential to an address outside this build.
        require(origin == currentOrigin) { "Move verification requires the current build origin" }
        try {
            val request = Request.Builder().url("$currentOrigin/v1/me")
                .header("Authorization", "Bearer ${session.credential}")
                .header("X-Expected-User", session.namespace.userId)
                .get().build()
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    401, 403 -> OriginMoveMeResult.Rejected
                    200 -> {
                        val body = response.body?.string() ?: return@withContext OriginMoveMeResult.Unavailable
                        val identity = NativeJson.decodeFromString<NativeIdentity>(body)
                        OriginMoveMeResult.Identity(identity.id, identity.deviceId)
                    }
                    else -> OriginMoveMeResult.Unavailable
                }
            }
        } catch (_: Exception) {
            OriginMoveMeResult.Unavailable
        }
    }
}
