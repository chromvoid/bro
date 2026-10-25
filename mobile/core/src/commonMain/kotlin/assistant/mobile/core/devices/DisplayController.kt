package assistant.mobile.core.devices

import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import assistant.mobile.core.nativeError
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Edits configuration only. Every pixel and preview-button result comes from the server. */
class DisplayController(
    scope: CoroutineScope,
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
    private val onDevicesChanged: suspend () -> Unit = {},
) {
    private val mutableState = MutableStateFlow<DisplayState?>(null)
    val state: StateFlow<DisplayState?> = mutableState.asStateFlow()
    private val visibility = MutableStateFlow(DevicesVisibility(false, 0))
    private val selected = MutableStateFlow<String?>(null)
    private var boundLease: SessionLease? = null
    private var boundId: String? = null
    private var etag: String? = null
    private val reads = Mutex()
    private val previews = Mutex()
    private val writes = Mutex()
    private val inputs = Mutex()
    private val owner = scope.launch {
        combine(sessions.leases, selected, visibility) { lease, id, visible -> Triple(lease, id, visible) }
            .collectLatest { (lease, id, visible) ->
                bind(lease, id)
                if (lease == null || id == null || !visible.active) return@collectLatest
                refresh()
                loadPreview()
                var ticks = 0
                while (true) {
                    delay(5_000)
                    loadPreview()
                    if (++ticks % 3 == 0) refresh()
                }
            }
    }

    fun activate(id: String) {
        require(id.isNotBlank())
        bind(sessions.lease(), id)
        selected.value = id
        visibility.update { DevicesVisibility(true, it.generation + 1) }
    }

    fun deactivate(id: String? = null) {
        if (id == null || selected.value == id) visibility.update { it.copy(active = false) }
    }

    fun close() {
        owner.cancel()
        deactivate()
        selected.value = null
        boundLease = null
        boundId = null
        etag = null
        mutableState.value = null
    }

    suspend fun refresh() {
        if (!visibility.value.active) return
        reads.withLock {
            val lease = sessions.lease() ?: return@withLock
            val id = selected.value ?: return@withLock
            if (!owned(lease, id) || state.value?.closed == true) return@withLock
            change(lease, id) { copy(display = display.copy(loading = true, error = null, serverError = null)) }
            val outcome = http.request("GET", path(id), lease = lease)
            if (!owned(lease, id)) return@withLock
            val description = if (outcome is HttpOutcome.Success) runCatching { decodeDisplayDescription(outcome.body) }.getOrNull() else null
            if (description == null || description.device.id != id) {
                val failure = if (outcome is HttpOutcome.Success) HttpOutcome.InvalidResponse else outcome
                change(lease, id) { copy(display = display.copy(loading = false, error = nativeError(failure), serverError = devicesServerError(failure))) }
            } else change(lease, id) {
                // Like the web watcher, status-only polls preserve the edit; a new saved revision
                // replaces the copy, including after a 409 from another client.
                val revised = display.data?.device?.revision != description.device.revision
                copy(display = DevicesQuery(description), draft = if (revised) description.settings else draft,
                    name = if (revised) description.device.name else name)
            }
        }
    }

    /** The path is constructed here; neither a response URL nor a redirect can receive Bearer. */
    suspend fun loadPreview() {
        if (!visibility.value.active) return
        previews.withLock {
            val lease = sessions.lease() ?: return@withLock
            val id = selected.value ?: return@withLock
            if (!owned(lease, id) || state.value?.closed == true) return@withLock
            val outcome = http.request("GET", path(id) + "/preview.png", lease = lease,
                headers = etag?.let { mapOf("If-None-Match" to it) }.orEmpty())
            if (!owned(lease, id)) return@withLock
            // Preview polling is quiet on errors and 304, exactly like the browser preview.
            if (outcome !is HttpOutcome.Success || outcome.status != 200 ||
                !validDisplayPng(outcome.body, outcome.headers["content-type"])) return@withLock
            etag = outcome.headers["etag"]
            change(lease, id) { copy(preview = outcome.body.copyOf(), previewVersion = previewVersion + 1) }
        }
    }

    fun updateName(name: String) = edit { copy(name = name.take(60)) }
    fun setLayout(page: Int, layout: DisplayLayout) = updatePage(page) { withDisplayLayout(it, layout, fallback()) }
    fun setTitle(page: Int, title: String) = updatePage(page) { it.copy(title = title.take(40)) }
    fun setCard(page: Int, slot: Int, card: String) {
        if (state.value?.display?.data?.cards?.none { it.id == card && it.available } != false) return
        updatePage(page) { it.copy(cards = it.cards.mapIndexed { index, current -> if (index == slot) card else current }) }
    }
    fun setTicker(page: Int, card: String, on: Boolean) {
        if (state.value?.display?.data?.cards?.none { it.id == card && it.available && it.brief } != false) return
        updatePage(page) { toggledDisplayTicker(it, card, on) }
    }
    fun setTickerPeriod(page: Int, seconds: Int) {
        if (seconds in DISPLAY_TICKER_PERIODS) updatePage(page) { it.copy(tickerSeconds = seconds) }
    }
    fun setHomeAfter(seconds: Int) {
        if (seconds in DISPLAY_HOME_PERIODS) updateSettings { it.copy(homeAfterSeconds = seconds) }
    }
    fun addPage() = updateSettings {
        if (it.pages.size >= DISPLAY_MAX_PAGES) it else it.copy(pages = it.pages + DisplayPage(DisplayLayout.Single, listOf(fallback())))
    }
    fun removePage(index: Int) = updateSettings {
        if (it.pages.size <= 1 || index !in it.pages.indices) it else it.copy(pages = it.pages.filterIndexed { position, _ -> position != index })
    }
    fun movePage(index: Int, to: Int) = updateSettings {
        if (index !in it.pages.indices || to !in it.pages.indices) it
        else it.copy(pages = it.pages.toMutableList().apply { add(to, removeAt(index)) })
    }
    fun reset() { state.value?.display?.data?.defaults?.let { defaults -> updateSettings { defaults } } }
    fun clearError() = edit { copy(error = null, serverError = null) }

    suspend fun save() {
        if (!writes.tryLock()) return
        try {
            val lease = sessions.lease() ?: return
            val before = state.value ?: return
            val description = before.display.data ?: return
            val draft = before.draft ?: return
            val id = before.deviceId
            if (!owned(lease, id) || !before.changed || before.closed) return
            change(lease, id) { copy(saving = true, saved = false, error = null, serverError = null) }
            try {
                val outcome = http.request("PUT", path(id), displaySaveBody(description, draft, before.name), lease)
                if (!owned(lease, id)) return
                when (outcome) {
                    is HttpOutcome.Success -> {
                        change(lease, id) { copy(saved = true) }
                        refresh()
                        onDevicesChanged()
                        loadPreview()
                    }
                    is HttpOutcome.Conflict -> {
                        change(lease, id) { copy(error = GeneratedStrings.Display.changed, serverError = null) }
                        refresh()
                    }
                    else -> change(lease, id) { copy(error = nativeError(outcome), serverError = devicesServerError(outcome)) }
                }
            } finally { change(lease, id) { copy(saving = false) } }
        } finally { writes.unlock() }
    }

    suspend fun press(input: PreviewInput) {
        if (!inputs.tryLock()) return
        try {
            val lease = sessions.lease() ?: return
            val id = selected.value ?: return
            if (!owned(lease, id) || state.value?.closed == true) return
            change(lease, id) { copy(pressing = true, error = null, serverError = null, saved = false) }
            try {
                val body = buildJsonObject { put("event", input.wire) }.toString().encodeToByteArray()
                val outcome = http.request("POST", path(id) + "/preview/input", body, lease)
                if (!owned(lease, id)) return
                if (outcome is HttpOutcome.Success) loadPreview()
                else change(lease, id) { copy(error = nativeError(outcome), serverError = devicesServerError(outcome)) }
            } finally { change(lease, id) { copy(pressing = false) } }
        } finally { inputs.unlock() }
    }

    suspend fun revoke(): Boolean {
        if (!writes.tryLock()) return false
        try {
            val lease = sessions.lease() ?: return false
            val id = selected.value ?: return false
            if (!owned(lease, id) || state.value?.closed == true) return false
            change(lease, id) { copy(revoking = true, error = null, serverError = null, saved = false) }
            try {
                val outcome = http.request("POST", "/v1/devices/${id.encodeURLPathPart()}/revoke", "{}".encodeToByteArray(), lease)
                if (!owned(lease, id)) return false
                if (outcome !is HttpOutcome.Success) {
                    change(lease, id) { copy(error = nativeError(outcome), serverError = devicesServerError(outcome)) }
                    return false
                }
                etag = null
                deactivate()
                onDevicesChanged()
                // Publishing closed navigates away and can cancel the screen's caller scope.
                // Finish the authoritative list read before that navigation becomes observable.
                change(lease, id) { copy(closed = true, preview = null) }
                return owned(lease, id)
            } finally { change(lease, id) { copy(revoking = false) } }
        } finally { writes.unlock() }
    }

    private fun updatePage(index: Int, update: (DisplayPage) -> DisplayPage) = updateSettings { settings ->
        settings.copy(pages = settings.pages.mapIndexed { position, page -> if (position == index) update(page) else page })
    }
    private fun updateSettings(update: (DisplaySettings) -> DisplaySettings) = edit {
        copy(draft = draft?.let(update), error = null, serverError = null, saved = false)
    }
    private fun fallback(): String = state.value?.display?.data?.cards?.firstOrNull { it.available }?.id ?: "clock"
    private fun edit(update: DisplayState.() -> DisplayState) {
        val lease = sessions.lease() ?: return
        val before = state.value ?: return
        if (before.saving || before.revoking || before.closed) return
        change(lease, before.deviceId, update)
    }
    private fun path(id: String) = "/v1/display/devices/${id.encodeURLPathPart()}"
    private fun bind(lease: SessionLease?, id: String?) {
        if (boundLease === lease && boundId == id) return
        boundLease = lease
        boundId = id
        etag = null
        mutableState.value = if (lease != null && id != null) DisplayState(lease.namespace, id) else null
    }
    private fun owned(lease: SessionLease, id: String): Boolean =
        boundLease === lease && boundId == id && sessions.isCurrent(lease)
    private inline fun change(lease: SessionLease, id: String, update: DisplayState.() -> DisplayState) {
        if (owned(lease, id)) mutableState.update { it?.update() }
    }
}
