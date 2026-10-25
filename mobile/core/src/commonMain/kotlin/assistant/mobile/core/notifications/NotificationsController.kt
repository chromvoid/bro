package assistant.mobile.core.notifications

import assistant.mobile.core.*
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The web notification actions have no ledger/lookup API. Each explicit action is sent once,
 * bound to its native lease, then followed by authoritative reads; foreground never retries it.
 */
class NotificationsController(
    scope: CoroutineScope,
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
    private val onChanged: suspend () -> Unit = {},
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutableState = MutableStateFlow<NotificationsState?>(null)
    val state: StateFlow<NotificationsState?> = mutableState.asStateFlow()
    private val visible = MutableStateFlow(false)
    private val owners = mutableSetOf<Any>()
    private var boundLease: SessionLease? = null
    private val readLock = Mutex()
    private val mutationLock = Mutex()

    init {
        scope.launch {
            combine(sessions.leases, sessions.identity, visible) { lease, identity, active -> Triple(lease, identity, active) }
                .collectLatest { (lease, identity, active) ->
                    bind(lease, identity)
                    if (active && lease != null && identity != null) refresh()
                }
        }
    }

    fun activate(owner: Any = DEFAULT_OWNER) {
        bind(sessions.lease(), sessions.identity.value)
        owners.add(owner)
        visible.value = owners.isNotEmpty()
    }
    fun deactivate(owner: Any = DEFAULT_OWNER) {
        owners.remove(owner)
        visible.value = owners.isNotEmpty()
    }
    suspend fun foreground() { if (visible.value) refresh() }
    fun nextMorning(): String = notificationNextMorning(clockMs(), state.value?.timezone ?: "UTC")
    fun clearNotice() { mutableState.update { it?.copy(notice = null, error = null) } }

    suspend fun refresh() = readLock.withLock {
        val lease = sessions.lease() ?: return@withLock
        bind(lease, sessions.identity.value)
        if (!owned(lease)) return@withLock
        change(lease) { copy(list = list.copy(loading = true, error = null), settings = settings.copy(loading = true, error = null)) }
        val list = read(lease, "/v1/notifications?limit=50", ::decodeNotificationList)
        change(lease) { copy(list = if (list.data != null) list else this.list.copy(loading = false, error = list.error)) }
        val settings = read(lease, "/v1/notifications/settings", ::decodeNotificationSettings)
        change(lease) { copy(settings = if (settings.data != null) settings else this.settings.copy(loading = false, error = settings.error)) }
    }

    suspend fun invalidate(invalidation: EventInvalidation) {
        if (invalidation is EventInvalidation.Domains && EventDomain.Notifications !in invalidation.values) return
        mutableState.update { it?.copy(list = it.list.copy(stale = it.list.data != null), settings = it.settings.copy(stale = it.settings.data != null)) }
        if (visible.value) refresh()
    }

    suspend fun read(ids: List<String>? = null): Boolean = mutate { lease ->
        require(ids == null || ids.size in 1..100 && ids.all { it.isNotBlank() })
        val body = buildJsonObject {
            if (ids == null) put("all", true) else put("ids", JsonArray(ids.map(::JsonPrimitive)))
        }.toString().encodeToByteArray()
        write(lease, "POST", "/v1/notifications/read", body) {
            require(notificationObject(it).getValue("read").jsonPrimitive.int >= 0)
        }
    }

    suspend fun snooze(id: String, minutes: Int): Boolean = mutate { lease ->
        require(id.isNotBlank() && minutes in 1..7 * 24 * 60)
        write(lease, "POST", "/v1/notifications/${id.encodeURLPathPart()}/snooze",
            buildJsonObject { put("minutes", minutes) }.toString().encodeToByteArray(), ::validateSnooze)
    }

    suspend fun snoozeUntil(id: String, until: String): Boolean = mutate { lease ->
        require(id.isNotBlank())
        val instant = Instant.parse(until).toEpochMilliseconds()
        require(instant > clockMs() && instant <= clockMs() + 7L * 86_400_000)
        write(lease, "POST", "/v1/notifications/${id.encodeURLPathPart()}/snooze",
            buildJsonObject { put("until", until) }.toString().encodeToByteArray(), ::validateSnooze)
    }

    suspend fun saveSettings(draft: NotificationSettingsDraft): Boolean = mutate { lease ->
        val current = state.value?.settings?.data ?: return@mutate false
        val body = notificationSettingsBody(draft, current)
        val outcome = http.request("PUT", "/v1/notifications/settings", body, lease)
        if (!owned(lease)) return@mutate false
        val saved = if (outcome is HttpOutcome.Success) runCatching { decodeNotificationSettings(outcome.body) }.getOrNull() else null
        if (saved != null) {
            change(lease) { copy(settings = NotificationQuery(saved), notice = GeneratedStrings.NotificationSettings.saved) }
            onChanged()
            true
        } else {
            change(lease) { copy(error = nativeError(if (outcome is HttpOutcome.Success) HttpOutcome.InvalidResponse else outcome)) }
            refresh()
            false
        }
    }

    suspend fun test(): Boolean = mutate { lease ->
        val success = write(lease, "POST", "/v1/notifications/test", "{}".encodeToByteArray()) {
            val root = notificationObject(it)
            val written = root.getValue("written").jsonPrimitive.boolean
            require(!written || root.getValue("id").jsonPrimitive.contentOrNull?.isNotBlank() == true)
        }
        if (success) change(lease) { copy(notice = GeneratedStrings.NotificationSettings.testSent) }
        success
    }

    private suspend fun mutate(action: suspend (SessionLease) -> Boolean): Boolean {
        if (!mutationLock.tryLock()) return false
        try {
            val lease = sessions.lease() ?: return false
            bind(lease, sessions.identity.value)
            if (!owned(lease)) return false
            change(lease) { copy(busy = true, error = null, notice = null) }
            return try { action(lease) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IllegalArgumentException) { change(lease) { copy(error = GeneratedStrings.Errors.invalid) }; false }
            catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Errors.network) }; false }
            finally { change(lease) { copy(busy = false) } }
        } finally { mutationLock.unlock() }
    }

    private suspend fun write(lease: SessionLease, method: String, path: String, body: ByteArray,
        validate: (ByteArray) -> Unit): Boolean {
        val outcome = http.request(method, path, body, lease)
        if (!owned(lease)) return false
        val success = outcome is HttpOutcome.Success && runCatching { validate(outcome.body) }.isSuccess
        if (!success) change(lease) { copy(error = nativeError(if (outcome is HttpOutcome.Success) HttpOutcome.InvalidResponse else outcome)) }
        // Also read after an unknown outcome: a committed write may have lost only its reply.
        refresh()
        if (success && owned(lease)) onChanged()
        return success && owned(lease)
    }

    private suspend fun <T> read(lease: SessionLease, path: String, decode: (ByteArray) -> T): NotificationQuery<T> {
        val outcome = http.request("GET", path, lease = lease)
        return if (outcome is HttpOutcome.Success) try { NotificationQuery(decode(outcome.body)) }
            catch (_: Exception) { NotificationQuery(error = GeneratedStrings.Errors.server) }
        else NotificationQuery(error = nativeError(outcome))
    }
    private fun bind(lease: SessionLease?, identity: NativeIdentity?) {
        if (lease == null || identity == null || identity.id != lease.namespace.userId) {
            boundLease = null; mutableState.value = null; return
        }
        val changed = boundLease !== lease
        boundLease = lease
        mutableState.update { old ->
            if (changed || old == null) NotificationsState(lease.namespace, identity.timezone)
            else old.copy(timezone = identity.timezone)
        }
    }
    private fun owned(lease: SessionLease) = boundLease === lease && sessions.isCurrent(lease)
    private fun change(lease: SessionLease, update: NotificationsState.() -> NotificationsState) {
        if (owned(lease)) mutableState.update { it?.update() }
    }
    private companion object {
        val DEFAULT_OWNER = Any()
        fun validateSnooze(bytes: ByteArray) { Instant.parse(notificationObject(bytes).getValue("snoozed_until").jsonPrimitive.content) }
    }
}
