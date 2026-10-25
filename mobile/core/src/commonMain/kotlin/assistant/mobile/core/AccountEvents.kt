package assistant.mobile.core

import assistant.mobile.core.journal.JournalStorageException
import assistant.mobile.core.journal.JournalStore
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.time.Clock

/** Durable account cursor. A failed read must throw; it never means an empty cursor. */
interface EventCursorStore {
    suspend fun read(namespace: AccountNamespace): String?
    suspend fun write(namespace: AccountNamespace, value: String)
}

class EventCursorUnavailable(cause: Throwable? = null) :
    Exception("Event cursor storage unavailable", cause)

class JournalEventCursorStore(private val journal: JournalStore) : EventCursorStore {
    override suspend fun read(namespace: AccountNamespace): String? = storage {
        journal.readCursor(namespace)
    }

    override suspend fun write(namespace: AccountNamespace, value: String) = storage {
        journal.writeCursor(namespace, value)
    }

    private suspend fun <T> storage(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw EventCursorUnavailable(failure)
    }
}

enum class EventDomain {
    Conversation, Community, Nutrition, Catalog, Personal, Activity, Tasks, Timers,
    Challenges, Notifications, Calls, Shared,
}

sealed interface EventInvalidation {
    data object All : EventInvalidation
    data class Domains(val values: Set<EventDomain>) : EventInvalidation
}

/** Events only wake authoritative reads. They never authorize a domain write. */
interface AccountEventEffects {
    suspend fun refetchAll(lease: SessionLease)
    suspend fun recover(lease: SessionLease)
    suspend fun invalidate(lease: SessionLease, invalidation: EventInvalidation)
}

data class AccountChangeEvent(
    val namespace: AccountNamespace,
    val generation: Long,
    val id: String,
    val name: String,
    val topic: String?,
    val data: JsonElement?,
)

sealed interface AccountEventState {
    data object Stopped : AccountEventState
    data object Connecting : AccountEventState
    data object Connected : AccountEventState
    data class BackingOff(val milliseconds: Long) : AccountEventState
    data class Denied(val status: Int) : AccountEventState
    data object StorageUnavailable : AccountEventState
}

data class EventStreamPolicy(
    val idleMilliseconds: Long = 45_000,
    val random: () -> Double = { Random.nextDouble() },
    val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    init {
        require(idleMilliseconds > 0)
    }

    internal fun reconnectDelay(ceiling: Long): Long =
        (ceiling * (0.5 + random().coerceIn(0.0, 1.0) / 2)).toLong().coerceIn(1_000, 30_000)
}

/**
 * App-scoped owner of the single foreground account stream. The platform forwards foreground
 * lifecycle here; session replacement cancels the old channel before opening its successor.
 * Optional progress subscribers use [changes], so they never create another HTTP connection.
 */
class AccountEvents(
    scope: CoroutineScope,
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
    private val cursorStore: EventCursorStore,
    private val effects: AccountEventEffects,
    private val policy: EventStreamPolicy = EventStreamPolicy(),
) {
    private val foreground = MutableStateFlow(false)
    private val restart = MutableStateFlow(0L)
    private val mutableState = MutableStateFlow<AccountEventState>(AccountEventState.Stopped)
    val state: StateFlow<AccountEventState> = mutableState.asStateFlow()
    private val mutableChanges = MutableSharedFlow<AccountChangeEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Disposable progress; consumers check [isCurrent] again after their own suspension. */
    val changes: SharedFlow<AccountChangeEvent> = mutableChanges.asSharedFlow()

    private val owner: Job = scope.launch {
        combine(sessions.leases, foreground, restart) { lease, visible, epoch ->
            Pair(if (visible) lease else null, epoch)
        }.collectLatest { (lease, _) ->
            mutableState.value = AccountEventState.Stopped
            if (lease != null) loop(lease)
        }
    }

    fun setForeground(value: Boolean) {
        foreground.value = value
    }

    fun isCurrent(event: AccountChangeEvent): Boolean = sessions.lease()?.let {
        it.namespace == event.namespace && it.generation == event.generation
    } ?: false

    /** Call after an explicit identity refresh restored access to a stream stopped by 403. */
    fun refreshAccess() {
        restart.value += 1
    }

    suspend fun close() {
        owner.cancel()
        owner.join()
        mutableState.value = AccountEventState.Stopped
    }

    private suspend fun loop(lease: SessionLease) {
        var ceiling = 1_000L
        var recovered = false
        while (sessions.isCurrent(lease)) {
            var notBefore: Long? = null
            try {
                if (!recovered) {
                    refreshIdentity(lease)
                    if (!sessions.isCurrent(lease)) return
                    effects.recover(lease)
                    if (!sessions.isCurrent(lease)) return
                    recovered = true
                }
                val saved = cursorStore.read(lease.namespace)
                if (!sessions.isCurrent(lease)) return
                if (saved != null && sequence(saved) == null) throw EventCursorUnavailable()
                val headers = if (saved == null) emptyMap() else mapOf("Last-Event-ID" to saved)
                mutableState.value = AccountEventState.Connecting
                val result = http.stream(
                    if (saved == null) "/v1/me/events?cursor=latest" else "/v1/me/events",
                    lease,
                    headers,
                ) { _, responseHeaders, channel ->
                    val contentType = responseHeaders.entries.firstOrNull {
                        it.key.equals("Content-Type", ignoreCase = true)
                    }?.value?.substringBefore(';')?.trim()
                    require(contentType.equals("text/event-stream", ignoreCase = true)) {
                        "Unexpected event stream content type"
                    }
                    if (sessions.isCurrent(lease)) {
                        mutableState.value = AccountEventState.Connected
                        read(channel, lease, saved) { ceiling = 1_000 }
                    }
                }
                if (!sessions.isCurrent(lease)) return
                when (result) {
                    is HttpOutcome.Unauthorized -> return deny(401)
                    is HttpOutcome.Forbidden -> return deny(403)
                    is HttpOutcome.StaleSession -> return
                    is HttpOutcome.RateLimited -> notBefore = result.retryAtMillis
                    else -> Unit
                }
            } catch (timeout: TimeoutCancellationException) {
                // A live account heartbeat arrives every 10 s. An idle socket gets a fresh GET.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unavailable: EventCursorUnavailable) {
                mutableState.value = AccountEventState.StorageUnavailable
                return
            } catch (unavailable: JournalStorageException) {
                mutableState.value = AccountEventState.StorageUnavailable
                return
            } catch (denied: EventAccessDenied) {
                return deny(denied.status)
            } catch (limited: EventRateLimited) {
                notBefore = limited.retryAtMillis
            } catch (_: Exception) {
                // No bodies or event payloads are logged; the durable cursor will replay work.
            }
            if (!sessions.isCurrent(lease)) return
            val wait = maxOf(policy.reconnectDelay(ceiling), (notBefore ?: 0) - policy.nowMillis())
            mutableState.value = AccountEventState.BackingOff(wait)
            delay(wait)
            ceiling = (ceiling * 2).coerceAtMost(30_000)
        }
    }

    private fun deny(status: Int) {
        mutableState.value = AccountEventState.Denied(status)
    }

    private suspend fun refreshIdentity(lease: SessionLease) {
        when (val result = sessions.refreshIdentity(lease)) {
            is HttpOutcome.Success -> Unit
            is HttpOutcome.Unauthorized -> throw EventAccessDenied(401)
            is HttpOutcome.Forbidden -> throw EventAccessDenied(403)
            is HttpOutcome.RateLimited -> throw EventRateLimited(result.retryAtMillis)
            is HttpOutcome.StorageUnavailable -> throw EventCursorUnavailable()
            else -> throw EventProcessingFailed()
        }
    }

    private suspend fun read(
        channel: ByteReadChannel,
        lease: SessionLease,
        saved: String?,
        onActivity: () -> Unit,
    ) {
        val parser = SseByteParser()
        val bytes = ByteArray(8_192)
        var cursor = saved?.let(::sequence)
        try {
            while (sessions.isCurrent(lease)) {
                val size = withTimeout(policy.idleMilliseconds) { channel.readAvailable(bytes) }
                if (size < 0) return
                if (size > 0) onActivity()
                for (event in parser.push(bytes, size)) {
                    if (!sessions.isCurrent(lease)) return
                    val id = event.id?.let(::sequence) ?: continue
                    if (event.name != "resync" && cursor != null && id <= cursor) continue
                    val data = try {
                        Json.parseToJsonElement(event.data)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                    val topic = ((data as? JsonObject)?.get("topic") as? JsonPrimitive)
                        ?.takeIf { it.isString }?.content
                    if (event.name == "resync") {
                        refreshIdentity(lease)
                        if (!sessions.isCurrent(lease)) return
                        effects.refetchAll(lease)
                        if (!sessions.isCurrent(lease)) return
                        effects.recover(lease)
                    } else {
                        if (topic == "access" || topic == "access.changed") refreshIdentity(lease)
                        if (!sessions.isCurrent(lease)) return
                        effects.invalidate(lease, affectedDomains(topic))
                    }
                    if (!sessions.isCurrent(lease)) return
                    sessions.withLease(lease) {
                        cursorStore.write(lease.namespace, id.toString())
                    } ?: return
                    if (!sessions.isCurrent(lease)) return
                    cursor = id
                    mutableChanges.tryEmit(AccountChangeEvent(
                        lease.namespace, lease.generation, id.toString(), event.name, topic, data,
                    ))
                }
            }
        } finally {
            channel.cancel(null)
        }
    }
}

private class EventAccessDenied(val status: Int) : Exception()
private class EventRateLimited(val retryAtMillis: Long) : Exception()
private class EventProcessingFailed : Exception()

private fun sequence(value: String): Long? =
    value.takeIf { it.isNotEmpty() && it.all { character -> character in '0'..'9' } }
        ?.toLongOrNull()?.takeIf { it >= 0 }

fun affectedDomains(topic: String?): EventInvalidation = when (topic) {
    "assistant.changed" -> domains(EventDomain.Conversation, EventDomain.Community)
    "nutrition.changed" -> domains(EventDomain.Nutrition)
    "nutrition.catalog" -> domains(EventDomain.Catalog)
    "personal.changed" -> domains(EventDomain.Personal)
    "activity.changed" -> domains(EventDomain.Activity)
    "tasks.changed" -> domains(EventDomain.Tasks)
    "timers.changed" -> domains(EventDomain.Timers)
    "challenges", "challenges.changed" -> domains(EventDomain.Challenges)
    "notifications.changed" -> domains(EventDomain.Notifications)
    "calls.changed" -> domains(EventDomain.Calls)
    // A scope change can affect every section, including personal cards and an open projection.
    "access", "access.changed" -> EventInvalidation.All
    "resource", "resource.changed" -> domains(
        EventDomain.Community, EventDomain.Shared, EventDomain.Tasks, EventDomain.Challenges,
    )
    // Unknown, malformed and browser-only admin topics only invalidate; they open no controls.
    else -> EventInvalidation.All
}

private fun domains(vararg values: EventDomain): EventInvalidation =
    EventInvalidation.Domains(values.toSet())

internal data class SseMessage(val id: String?, val name: String, val data: String)

/** Bounded UTF-8 line assembly keeps split multi-byte characters and CRLF chunks intact. */
internal class SseByteParser(
    private val maxLineBytes: Int = 65_536,
    private val maxEventBytes: Int = 1_048_576,
) {
    init { require(maxLineBytes > 0 && maxEventBytes > 0) }
    private var line = ByteArray(minOf(8_192, maxLineBytes))
    private var length = 0
    private var skipLf = false
    private var firstLine = true
    private var id: String? = null
    private var name = ""
    private val data = mutableListOf<String>()
    private var eventBytes = 0

    fun push(bytes: ByteArray, size: Int = bytes.size): List<SseMessage> {
        val events = mutableListOf<SseMessage>()
        for (index in 0 until size) {
            val byte = bytes[index]
            if (skipLf) {
                skipLf = false
                if (byte == 10.toByte()) continue
            }
            if (byte == 13.toByte() || byte == 10.toByte()) {
                var value = line.decodeToString(0, length, throwOnInvalidSequence = true)
                if (firstLine) {
                    value = value.removePrefix("\uFEFF")
                    firstLine = false
                }
                acceptLine(value, length)?.let(events::add)
                length = 0
                skipLf = byte == 13.toByte()
            } else {
                require(length < maxLineBytes) { "Event line too large" }
                if (length == line.size) line = line.copyOf(minOf(maxLineBytes, line.size * 2))
                line[length++] = byte
            }
        }
        return events
    }

    private fun acceptLine(value: String, byteCount: Int): SseMessage? {
        if (value.isEmpty()) {
            val event = if (data.isEmpty()) null else SseMessage(id, name.ifEmpty { "message" }, data.joinToString("\n"))
            name = ""
            data.clear()
            eventBytes = 0
            return event
        }
        if (value.startsWith(':')) return null
        val colon = value.indexOf(':')
        val field = if (colon < 0) value else value.substring(0, colon)
        val content = if (colon < 0) "" else value.substring(colon + 1).removePrefix(" ")
        when (field) {
            "id" -> if ('\u0000' !in content) id = content
            "event" -> name = content
            "data" -> {
                eventBytes += byteCount + 1
                require(eventBytes <= maxEventBytes) { "Event too large" }
                data.add(content)
            }
        }
        return null
    }
}
