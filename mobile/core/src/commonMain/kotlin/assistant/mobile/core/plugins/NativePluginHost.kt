package assistant.mobile.core.plugins

import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import assistant.mobile.core.SseByteParser
import assistant.mobile.core.journal.NewSubmission
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.journal.SubmissionCoordinator
import assistant.mobile.core.journal.SubmissionState
import assistant.mobile.core.journal.StaleJournalSession
import assistant.mobile.pluginapi.InvocationOutcome
import assistant.mobile.pluginapi.PendingPluginWrite
import assistant.mobile.pluginapi.PluginCapabilities
import assistant.mobile.pluginapi.PluginConfiguration
import assistant.mobile.pluginapi.PluginError
import assistant.mobile.pluginapi.PluginEvent
import assistant.mobile.pluginapi.PluginHost
import assistant.mobile.pluginapi.PluginInstallation
import assistant.mobile.pluginapi.PluginScreen
import assistant.mobile.pluginapi.capabilities
import assistant.mobile.pluginapi.dispatchablePluginStatus
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.time.Clock

/** One account-bound installation. Configuration uses transient HTTP, writes use the journal. */
class NativePluginHost(
    scope: CoroutineScope,
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
    private val submissions: SubmissionCoordinator,
    installation: PluginInstallation,
    private val navigate: (PluginScreen) -> Unit = {},
    private val close: () -> Unit = {},
    private val firstBackoffMs: Long = 1_000,
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : PluginHost {
    override val installationId = installation.installationId.also { require(validInstallationId(it)) }
    override val packageId = installation.`package`.id
    override val locale = "ru"
    private val lease = sessions.lease()
    private val lifetime = SupervisorJob(scope.coroutineContext[Job])
    private var closed = false
    private val mutableCapabilities = MutableStateFlow(installation.capabilities())
    override val capabilities: StateFlow<PluginCapabilities> = mutableCapabilities.asStateFlow()
    private val mutableWrites = MutableStateFlow<List<PendingPluginWrite>>(emptyList())
    override val pendingWrites: StateFlow<List<PendingPluginWrite>> = mutableWrites.asStateFlow()
    private val base = "/v1/plugins/installations/$installationId"
    private val invokePath = "$base/invoke"
    private val lookupPrefix = "$base/operations/by-key/"
    private var cursor: String? = null

    fun update(installation: PluginInstallation) {
        require(installation.installationId == installationId && installation.`package`.id == packageId)
        mutableCapabilities.value = installation.capabilities()
    }

    /** Process startup is lookup-only. Never dispatch a previously journaled request here. */
    suspend fun restore() {
        val current = currentLease() ?: return
        for (entry in submissions.pending(current).filter { it.endpoint == invokePath }) {
            if (entry.state != SubmissionState.RESOLVED && entry.state != SubmissionState.REJECTED) {
                lookup(entry.key)
            }
        }
        refreshWrites(current)
    }

    override suspend fun invoke(tool: String, args: JsonObject): InvocationOutcome {
        if (closed) return rejected("closed", "Плагин закрыт")
        val current = currentLease() ?: return pluginDenied(401)
        val descriptor = capabilities.value.tools[tool]
            ?: return rejected("tool_unavailable", "Инструмент недоступен")
        if (!dispatchablePluginStatus(capabilities.value.status)) {
            return rejected("dependency_unavailable", capabilities.value.note ?: "Плагин недоступен")
        }
        val bytes = buildJsonObject {
            put("tool_id", tool)
            put("tool_version", descriptor.version)
            put("arguments", args)
        }.toString().encodeToByteArray()
        if (descriptor.effect == "read") return direct("POST", invokePath, bytes, view = false)
        val key = freshKey()
        return safely(key) {
            val entry = submissions.submit(current, NewSubmission(
                endpoint = invokePath, lookupPrefix = lookupPrefix, body = bytes,
                audience = "plugin:$installationId", found = SubmissionState.ACCEPTED, key = key,
            ))
            val outcome = written(entry)
            if (entry.state == SubmissionState.REJECTED) submissions.discardRefused(current, key)
            refreshWrites(current)
            outcome
        }
    }

    override suspend fun lookup(clientKey: String): InvocationOutcome {
        if (!validClientKey(clientKey)) return rejected("invalid_arguments", "Неверный ключ", clientKey)
        val current = currentLease() ?: return pluginDenied(401, clientKey)
        return safely(clientKey) {
            val entry = entry(current, clientKey)
            if (entry == null) {
                when (val response = http.request("GET", lookupPrefix + clientKey, lease = current, preserveRejectionBody = true)) {
                    is HttpOutcome.Success -> pluginResultOutcome(response.body, clientKey)
                    is HttpOutcome.Failure -> InvocationOutcome.Unknown(clientKey, response.status == 404,
                        outcomeError(pluginHttpOutcome(response, clientKey, clockMs())))
                    else -> InvocationOutcome.Unknown(clientKey, error = outcomeError(pluginHttpOutcome(response, clientKey, clockMs())))
                }
            } else {
                var observed: HttpOutcome? = null
                val checked = submissions.check(current, clientKey) { observed = it }
                refreshWrites(current)
                when (val response = observed) {
                    null -> written(checked)
                    is HttpOutcome.Success -> pluginResultOutcome(response.body, clientKey)
                    else -> InvocationOutcome.Unknown(clientKey,
                        response is HttpOutcome.Failure && response.status == 404,
                        outcomeError(pluginHttpOutcome(response, clientKey, clockMs())))
                }
            }
        }
    }

    override suspend fun retry(clientKey: String): InvocationOutcome {
        if (!validClientKey(clientKey)) return rejected("invalid_arguments", "Неверный ключ", clientKey)
        if (closed) return rejected("closed", "Плагин закрыт", clientKey)
        val current = currentLease() ?: return pluginDenied(401, clientKey)
        return safely(clientKey) {
            val entry = entry(current, clientKey) ?: return@safely rejected("not_found", "Запрос не найден", clientKey)
            if (entry.state !in setOf(SubmissionState.PREPARED, SubmissionState.SENDING, SubmissionState.UNCERTAIN)) {
                return@safely lookup(clientKey)
            }
            if (!dispatchablePluginStatus(capabilities.value.status)) {
                return@safely rejected("dependency_unavailable", capabilities.value.note ?: "Плагин недоступен", clientKey)
            }
            val body = pluginObject(entry.body)
            val tool = body?.pluginString("tool_id")?.let { capabilities.value.tools[it] }
            val savedMajor = body?.pluginString("tool_version")?.substringBefore('.')?.toIntOrNull()
            if (tool == null || savedMajor == null || tool.version.substringBefore('.').toIntOrNull() != savedMajor) {
                // Existing admission remains readable even after a tool is removed or upgraded.
                val observed = lookup(clientKey)
                if (observed !is InvocationOutcome.Unknown || !observed.notFound) return@safely observed
                return@safely rejected("tool_unavailable", "Инструмент недоступен", clientKey)
            }
            val retried = submissions.retry(current, clientKey)
            refreshWrites(current)
            written(retried)
        }
    }

    override suspend fun acknowledge(clientKey: String) {
        val current = currentLease() ?: return
        val entry = entry(current, clientKey) ?: return
        if (entry.state in setOf(SubmissionState.ACCEPTED, SubmissionState.RESOLVED) &&
            pluginResult(entry.response)?.pluginString("state") in pluginTerminalStates) {
            submissions.acknowledge(current, clientKey)
        }
        refreshWrites(current)
    }

    override val configuration: PluginConfiguration = object : PluginConfiguration {
        override suspend fun get(): InvocationOutcome = direct("GET", "$base/configuration", view = true)

        override suspend fun put(
            settings: JsonObject, secrets: Map<String, String>?, expectedRevision: Long?, options: JsonObject?,
        ): InvocationOutcome {
            if (capabilities.value.configurationWritable != true) return rejected("forbidden", "Недостаточно прав")
            if (expectedRevision != null && expectedRevision < 0) return rejected("invalid_arguments")
            val bytes = buildJsonObject {
                put("public", settings)
                secrets?.let { put("secret", JsonObject(it.mapValues { (_, value) -> JsonPrimitive(value) })) }
                options?.let { put("options", it) }
            }.toString().encodeToByteArray()
            // Byte arrays holding secrets are neither journaled nor retained after the request.
            return try {
                direct("PUT", "$base/configuration", bytes, view = false,
                    headers = revisionHeaders(expectedRevision), key = freshKey())
            } finally { bytes.fill(0) }
        }

        override suspend fun delete(expectedRevision: Long, purge: Boolean): InvocationOutcome {
            if (capabilities.value.configurationWritable != true) return rejected("forbidden", "Недостаточно прав")
            if (expectedRevision < 0) return rejected("invalid_arguments")
            return direct("DELETE", "$base/configuration${if (purge) "" else "?purge=0"}", view = false,
                headers = revisionHeaders(expectedRevision), key = freshKey())
        }
    }

    override fun observe(): Flow<PluginEvent> = channelFlow {
        val current = currentLease() ?: return@channelFlow
        val seen = ArrayDeque<String>()
        var backoff = firstBackoffMs
        val producer = this
        val shutdown = launch { lifetime.join(); producer.cancel() }
        try {
            while (isActive && !closed && sessions.isCurrent(current)) {
                val headers = cursor?.let { mapOf("Last-Event-ID" to it) }.orEmpty()
                val response = try {
                    http.stream("$base/events", current, headers) { _, responseHeaders, channel ->
                        require(responseHeaders["content-type"]?.substringBefore(';') == "text/event-stream")
                        backoff = firstBackoffMs
                        readPluginStream(channel) { name, data ->
                            if (!sessions.isCurrent(current)) return@readPluginStream
                            decodePluginEvent(name, data, installationId, seen)?.let { event ->
                                cursor = when (event) {
                                    is PluginEvent.Ready -> event.cursor
                                    is PluginEvent.Resync -> event.cursor
                                    is PluginEvent.Delivered -> event.cursor
                                    is PluginEvent.Denied -> cursor
                                }
                                send(event)
                            }
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { HttpOutcome.NetworkFailure }
                when (response) {
                    HttpOutcome.Unauthorized -> { send(PluginEvent.Denied(401)); return@channelFlow }
                    HttpOutcome.Forbidden -> { send(PluginEvent.Denied(403)); return@channelFlow }
                    is HttpOutcome.Failure -> if (response.status == 404) { send(PluginEvent.Denied(404)); return@channelFlow }
                    HttpOutcome.StaleSession -> return@channelFlow
                    else -> Unit
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000)
            }
        } finally { shutdown.cancel() }
    }

    override fun navigate(destination: PluginScreen) {
        if (!closed && destination.installation.value == installationId) navigate.invoke(destination)
    }
    override fun close() = close.invoke()
    fun shutdown() { closed = true; lifetime.cancel() }

    private fun currentLease(): SessionLease? = lease?.takeIf { sessions.isCurrent(it) }

    private suspend fun direct(
        method: String, path: String, bytes: ByteArray? = null, view: Boolean,
        headers: Map<String, String> = emptyMap(), key: String? = null,
    ): InvocationOutcome {
        if (closed) return rejected("closed", "Плагин закрыт")
        val current = currentLease() ?: return pluginDenied(401)
        return when (val response = http.request(method, path, bytes, current, key, headers, preserveRejectionBody = true)) {
            is HttpOutcome.Success -> if (view) {
                pluginObject(response.body)?.let { InvocationOutcome.Completed(it) }
                    ?: InvocationOutcome.Unknown(error = PluginError("unexpected_state"))
            } else pluginResultOutcome(response.body, null)
            else -> pluginHttpOutcome(response, nowMillis = clockMs())
        }
    }

    private suspend fun entry(current: SessionLease, key: String): Submission? =
        submissions.pending(current).firstOrNull { it.key == key && it.endpoint == invokePath }

    private suspend fun refreshWrites(current: SessionLease) {
        val values = submissions.pending(current).filter { it.endpoint == invokePath && it.state != SubmissionState.REJECTED }
            .sortedBy { it.createdAtMillis }.map { entry ->
                val body = pluginObject(entry.body)
                PendingPluginWrite(entry.key, body?.pluginString("tool_id").orEmpty(),
                    body?.get("arguments") as? JsonObject ?: JsonObject(emptyMap()),
                    entry.state.name.lowercase(), entry.createdAtMillis,
                    entry.state == SubmissionState.UNCERTAIN && entry.failureStatus == 404,
                    entry.state in setOf(SubmissionState.ACCEPTED, SubmissionState.RESOLVED))
            }
        if (sessions.isCurrent(current)) mutableWrites.value = values
    }

    private fun written(entry: Submission): InvocationOutcome = when (entry.state) {
        SubmissionState.ACCEPTED, SubmissionState.RESOLVED -> pluginResultOutcome(entry.response, entry.key)
        SubmissionState.REJECTED -> journalErrorOutcome(entry)
        else -> InvocationOutcome.Unknown(entry.key, entry.failureStatus == 404,
            entry.failureStatus?.takeUnless { it == 404 }?.let {
                outcomeError(journalErrorOutcome(entry))
            })
    }

    private fun journalErrorOutcome(entry: Submission): InvocationOutcome {
        val response = if (entry.failureStatus == 429 && entry.response == null && entry.retryAtMillis != null) {
            HttpOutcome.RateLimited(entry.retryAtMillis)
        } else HttpOutcome.Failure(entry.failureStatus ?: 0, entry.response ?: byteArrayOf())
        val outcome = pluginHttpOutcome(response, entry.key, clockMs())
        val delay = entry.retryAtMillis?.let { (it - clockMs()).coerceAtLeast(0) }
        return if (outcome is InvocationOutcome.Rejected && outcome.error.retryAfterMs == null && delay != null) {
            outcome.copy(error = outcome.error.copy(retryAfterMs = delay))
        } else outcome
    }

    private suspend fun safely(key: String? = null, block: suspend () -> InvocationOutcome): InvocationOutcome = try {
        block()
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: StaleJournalSession) { InvocationOutcome.Rejected(PluginError("stale_session"), clientKey = key)
    } catch (_: Exception) { InvocationOutcome.Unknown(key, error = PluginError("storage_unavailable")) }
}

private fun rejected(code: String, message: String = code, key: String? = null) =
    InvocationOutcome.Rejected(PluginError(code, message), clientKey = key)

private fun outcomeError(outcome: InvocationOutcome): PluginError? = when (outcome) {
    is InvocationOutcome.Unknown -> outcome.error
    is InvocationOutcome.Rejected -> outcome.error
    is InvocationOutcome.Failed -> outcome.error
    else -> null
}

private fun revisionHeaders(revision: Long?): Map<String, String> =
    revision?.let { mapOf("If-Match" to "\"$it\"") }.orEmpty()

@OptIn(ExperimentalUuidApi::class)
private fun freshKey(): String = Uuid.random().toString()
private fun validClientKey(value: String): Boolean = value.length in 16..80 && value.all {
    it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-'
}

private suspend fun readPluginStream(channel: ByteReadChannel, consume: suspend (String, String) -> Unit) {
    val parser = SseByteParser()
    val bytes = ByteArray(8_192)
    try {
        while (true) {
            val size = channel.readAvailable(bytes)
            if (size < 0) return
            for (frame in parser.push(bytes, size)) consume(frame.name, frame.data)
        }
    } finally { channel.cancel(null) }
}

internal fun decodePluginEvent(
    name: String, bytes: String, installationId: String, seen: ArrayDeque<String>,
): PluginEvent? {
    val body = pluginObject(bytes.encodeToByteArray()) ?: return null
    val cursor = body.pluginString("cursor")?.takeIf(::validPluginCursor) ?: return null
    if (name == "stream.ready") return PluginEvent.Ready(cursor)
    if (name == "resync_required") return PluginEvent.Resync(cursor)
    if (body.pluginString("installation_id") != installationId || body.pluginString("type") != name) return null
    val id = body.pluginString("event_id") ?: return null
    val occurredAt = body.pluginString("occurred_at") ?: return null
    if (id in seen) return null
    seen.addLast(id)
    if (seen.size > 256) seen.removeFirst()
    return PluginEvent.Delivered(id, name, occurredAt, cursor,
        body["payload"] as? JsonObject ?: JsonObject(emptyMap()), body.pluginString("operation_key"))
}

private fun validPluginCursor(value: String): Boolean = value.length in 1..256 && value.all {
    it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._:~-"
}
