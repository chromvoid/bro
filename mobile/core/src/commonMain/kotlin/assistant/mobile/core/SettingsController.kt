package assistant.mobile.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.ceil
import kotlin.math.round
import kotlin.time.Clock

data class PersonalLimit(val kind: String, val maximum: Double?, val used: Double?) {
    val exhausted: Boolean get() = maximum != null && (used ?: 0.0) >= maximum
    val sharePercent: Float? get() = maximum?.let {
        if (it == 0.0) 100f else (((used ?: 0.0) / it) * 100).coerceIn(0.0, 100.0).toFloat()
    }
    val label: LocalizedString get() = when (kind) {
        "assistant" -> GeneratedStrings.Settings.Limit.assistant
        "research" -> GeneratedStrings.Settings.Limit.research
        "dictation_ms" -> GeneratedStrings.Settings.Limit.dictation_ms
        "tts_chars" -> GeneratedStrings.Settings.Limit.tts_chars
        else -> GeneratedStrings.Settings.limits
    }
    val usage: LocalizedString get() {
        val shownUsed = show(used ?: 0.0)
        return when {
            maximum == null -> GeneratedStrings.Settings.usedUnlimited(shownUsed)
            kind == "dictation_ms" -> GeneratedStrings.Settings.usedMinutes(shownUsed, show(maximum))
            else -> GeneratedStrings.Settings.used(shownUsed, show(maximum))
        }
    }

    private fun show(value: Double): String {
        val amount = if (kind == "dictation_ms") ceil(value / 6_000.0) / 10.0 else round(value)
        return if (amount % 1.0 == 0.0 && amount <= Long.MAX_VALUE.toDouble()) amount.toLong().toString()
        else amount.toString()
    }
}

data class PersonalCapability(
    val key: String,
    val available: Boolean,
    val title: LocalizedString,
    val description: LocalizedString,
)

/** A capability is a live scope projection, never a device feature switch. */
fun personalCapabilities(scopes: List<String>): List<PersonalCapability> = listOf(
    PersonalCapability("assistant", "session.control" in scopes, GeneratedStrings.Capabilities.Feature.assistant,
        GeneratedStrings.Capabilities.FeatureText.assistant),
    PersonalCapability("nutrition", "nutrition.read" in scopes, GeneratedStrings.Capabilities.Feature.nutrition,
        GeneratedStrings.Capabilities.FeatureText.nutrition),
    PersonalCapability("tasks", "tasks.read" in scopes, GeneratedStrings.Capabilities.Feature.tasks,
        GeneratedStrings.Capabilities.FeatureText.tasks),
    PersonalCapability("timers", "timers.read" in scopes, GeneratedStrings.Capabilities.Feature.timers,
        GeneratedStrings.Capabilities.FeatureText.timers),
    PersonalCapability("personal", "personal.read" in scopes, GeneratedStrings.Capabilities.Feature.personal,
        GeneratedStrings.Capabilities.FeatureText.personal),
    PersonalCapability("challenges", "challenges.read" in scopes, GeneratedStrings.Capabilities.Feature.challenges,
        GeneratedStrings.Capabilities.FeatureText.challenges),
)

data class SettingsState(
    val identity: NativeIdentity,
    val name: String = identity.name,
    val locale: String = identity.locale,
    val timezone: String = identity.timezone,
    val busy: Boolean = false,
    val loadingLimits: Boolean = false,
    val limits: List<PersonalLimit> = emptyList(),
    val error: LocalizedString? = null,
    val limitsError: LocalizedString? = null,
    val saved: Boolean = false,
    val retryAtMillis: Long? = null,
) {
    val capabilities: List<PersonalCapability> get() = personalCapabilities(identity.scopes)
    val dirty: Boolean get() = name != identity.name || locale != identity.locale || timezone != identity.timezone
}

/**
 * The existing profile endpoint replaces three preferences and has no operation receipt or
 * keyed lookup. It must remain a transient POST rather than inventing command-journal evidence.
 */
class SettingsController(
    scope: CoroutineScope,
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutableState = MutableStateFlow<SettingsState?>(null)
    val state: StateFlow<SettingsState?> = mutableState.asStateFlow()
    private var boundLease: SessionLease? = null

    init {
        scope.launch {
            combine(sessions.leases, sessions.identity) { lease, identity -> lease to identity }
                .collect { (lease, identity) ->
                    if (lease == null || identity == null || identity.id != lease.namespace.userId) {
                        boundLease = null
                        mutableState.value = null
                    } else syncIdentity(lease, identity)
                }
        }
    }

    fun updateName(value: String) = edit { copy(name = value.take(100)) }
    fun updateLocale(value: String) = edit { copy(locale = value.take(20)) }
    fun updateTimezone(value: String) = edit { copy(timezone = value) }

    suspend fun refresh() {
        val lease = sessions.lease() ?: return
        val outcome = sessions.refreshIdentity(lease)
        if (!sessions.isCurrent(lease)) return
        sessions.identity.value?.let { syncIdentity(lease, it) }
        if (outcome !is HttpOutcome.Success) {
            update(lease) { copy(error = nativeError(outcome), retryAtMillis = outcome.retryDeadline()) }
            return
        }
        refreshLimits()
    }

    /** Account SSE refreshes identity before calling this authoritative read. */
    suspend fun refreshLimits() {
        val lease = sessions.lease() ?: return
        val identity = sessions.identity.value ?: return
        syncIdentity(lease, identity)
        if (state.value?.loadingLimits == true) return
        update(lease) { copy(loadingLimits = true, limitsError = null) }
        try {
            val outcome = http.request("GET", "/v1/me/limits", lease = lease)
            if (outcome is HttpOutcome.Success) {
                val limits = try { decodePersonalLimits(outcome.body) } catch (_: Exception) {
                    update(lease) { copy(loadingLimits = false, limitsError = GeneratedStrings.Errors.server) }
                    return
                }
                update(lease) { copy(loadingLimits = false, limits = limits, limitsError = null, retryAtMillis = null) }
            } else update(lease) {
                copy(loadingLimits = false, limitsError = nativeError(outcome), retryAtMillis = outcome.retryDeadline())
            }
        } catch (cancelled: CancellationException) {
            update(lease) { copy(loadingLimits = false) }
            throw cancelled
        }
    }

    suspend fun save() {
        val lease = sessions.lease() ?: return
        val identity = sessions.identity.value ?: return
        syncIdentity(lease, identity)
        val form = state.value ?: return
        if (form.busy || form.retryAtMillis?.let { it > clockMs() } == true) return
        val validation = when {
            form.name.isBlank() -> GeneratedStrings.Settings.nameRequired
            !validTimezone(form.timezone) -> GeneratedStrings.Personal.zoneInvalid
            form.locale.isBlank() -> GeneratedStrings.Errors.invalid
            else -> null
        }
        if (validation != null) {
            update(lease) { copy(error = validation, saved = false) }
            return
        }
        update(lease) { copy(busy = true, error = null, saved = false) }
        try {
            val outcome = http.request("POST", "/v1/me", buildJsonObject {
                put("name", form.name.trim())
                put("locale", form.locale)
                put("timezone", form.timezone)
            }.toString().encodeToByteArray(), lease = lease)
            if (!sessions.isCurrent(lease)) return
            if (outcome is HttpOutcome.Success) {
                // POST /me is not a native session snapshot: GET preserves device/session binding.
                val refreshed = sessions.refreshIdentity(lease)
                if (refreshed is HttpOutcome.Success) {
                    sessions.identity.value?.let { syncIdentity(lease, it, replaceForm = true) }
                    update(lease) { copy(busy = false, saved = true, error = null, retryAtMillis = null) }
                } else update(lease) {
                    copy(busy = false, error = nativeError(refreshed), retryAtMillis = refreshed.retryDeadline())
                }
            } else update(lease) {
                copy(busy = false, error = nativeError(outcome), retryAtMillis = outcome.retryDeadline())
            }
        } catch (cancelled: CancellationException) {
            update(lease) { copy(busy = false) }
            throw cancelled
        }
    }

    private fun syncIdentity(lease: SessionLease, identity: NativeIdentity, replaceForm: Boolean = false) {
        if (!sessions.isCurrent(lease) || identity.id != lease.namespace.userId) return
        val current = state.value
        mutableState.value = if (boundLease !== lease || current == null) SettingsState(identity)
        else if (replaceForm || !current.dirty) current.copy(
            identity = identity, name = identity.name, locale = identity.locale, timezone = identity.timezone,
        ) else current.copy(identity = identity)
        boundLease = lease
    }

    private fun update(lease: SessionLease, change: SettingsState.() -> SettingsState) {
        if (sessions.isCurrent(lease) && boundLease === lease) mutableState.update { it?.change() }
    }

    private fun edit(change: SettingsState.() -> SettingsState) {
        val lease = sessions.lease() ?: return
        if (boundLease !== lease || state.value?.busy != false) return
        update(lease) { change().copy(error = null, saved = false) }
    }
}

private fun HttpOutcome.retryDeadline(): Long? = (this as? HttpOutcome.RateLimited)?.retryAtMillis

internal fun decodePersonalLimits(bytes: ByteArray): List<PersonalLimit> {
    val document = NativeJson.parseToJsonElement(bytes.decodeToString()).jsonObject
    return document.getValue("limits").jsonArray.map { element ->
        val row = element.jsonObject
        val kind = row.getValue("kind").jsonPrimitive.also { require(it.isString) }.content
        require(kind.isNotBlank())
        PersonalLimit(kind, row.count("maximum"), row.count("used"))
    }
}

private fun JsonObject.count(key: String): Double? {
    val value = getValue(key)
    if (value == JsonNull) return null
    val count = (value as JsonPrimitive).content.toDouble()
    require(count.isFinite() && count >= 0)
    return count
}
