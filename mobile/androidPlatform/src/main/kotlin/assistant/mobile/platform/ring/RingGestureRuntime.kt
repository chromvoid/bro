package assistant.mobile.platform.ring

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import assistant.mobile.platform.R
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

/** Local actions accept a qualified physical HID source and explicit account-owned configuration. */
class RingGestureRuntime(private val context: Context, origin: String,
    private val binding: () -> RingWorkoutBinding?, private val foregroundUser: () -> String?,
    private val native: () -> JsonObject) {
    private val preferences = context.getSharedPreferences("ring-gestures-${sha256(origin).take(24)}", Context.MODE_PRIVATE)
    private val crypto = RingCrypto("assistant.ring.gestures.v1.${sha256(origin).take(24)}")
    private val aad = "ring-gestures-v1\n$origin"
    private var owner: String? = null
    private var enabled = false
    private var mappings = RingGestureInput.entries.associateWith { RingGestureAction.NONE }
    private var failed = false
    private var closed = false
    private var lastAction: RingGestureOutcome? = null
    private var actionOwner: String? = null
    private val seen = LinkedHashSet<String>()
    private val mutable = MutableStateFlow(RingGestureState())
    val states: StateFlow<RingGestureState> get() = mutable
    init {
        try {
            preferences.getString("policy", null)?.let { encoded ->
                val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
                require(bytes.size <= 32_768)
                val value = ringJson.parseToJsonElement(crypto.open(bytes, aad).toString(Charsets.UTF_8)) as JsonObject
                owner = value.string("owner"); enabled = value.bool("enabled") == true
                mappings = RingGestureActions.parse(value["mappings"] as? JsonObject ?: throw IllegalArgumentException("Invalid mappings"))
            }
        } catch (_: Exception) { failed = true; enabled = false }
        RingGestureRelay.attach(this, ::key, ::outcome, ::revoked)
        refresh()
    }
    private fun accessGranted(): Boolean {
        val component = ComponentName(context, RingGestureService::class.java)
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true
    }
    private fun qualifiedDevice(device: InputDevice?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || device == null || device.isVirtual || !device.isExternal) return false
        val identity = native()["identity"] as? JsonObject ?: return false
        val current = binding() ?: return false
        return RingGestureProofs.matches(identity, current.address, device.descriptor, device.vendorId, device.productId, device.sources)
    }
    private fun hasQualifiedDevice() = InputDevice.getDeviceIds().any { qualifiedDevice(InputDevice.getDevice(it)) }
    @Synchronized fun refresh() {
        val current = binding()?.takeIf { foregroundUser() == it.userId }
        val ours = current != null && current.identity == owner
        val qualified = current != null && hasQualifiedDevice()
        val access = accessGranted()
        mutable.value = RingGestureState(owner = current?.let(RingPhoneOwner::from), accessGranted = access,
            enabled = ours && enabled && !failed && qualified && access, qualified = qualified,
            reason = if (failed) "gesture_store_unavailable" else if (!qualified) "physical_hid_source_unqualified"
                else if (!access) "accessibility_access_required" else "qualified_local_hid_actions",
            mappings = if (ours) mappings else RingGestureInput.entries.associateWith { RingGestureAction.NONE },
            lastAction = if (ours) lastAction else null)
    }
    private fun requireOwner(expected: RingPhoneOwner): RingWorkoutBinding = binding()?.takeIf {
        expected.matches(it) && foregroundUser() == it.userId
    } ?: throw RingFailure("binding_changed", RingText.text(R.string.ring_phone_binding_changed))
    @Synchronized fun action(action: RingGestureActionRequest) {
        val current = requireOwner(action.owner)
        if (failed) throw RingFailure("gesture_store_unavailable", RingText.text(R.string.ring_gestures_store_unavailable))
        when (action) {
            is RingGestureActionRequest.OpenAccess -> context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            is RingGestureActionRequest.Configure -> {
                val proposed = RingGestureActions.validate(action.mappings)
                if (action.enabled && !accessGranted()) throw RingFailure("accessibility_access_required", RingText.text(R.string.ring_gestures_access_required))
                if (action.enabled && !hasQualifiedDevice()) throw RingFailure("physical_hid_source_unqualified", RingText.text(R.string.ring_gestures_source_unqualified))
                owner = current.identity; enabled = action.enabled; mappings = proposed; seen.clear(); lastAction = null; persist()
            }
        }
        refresh()
    }
    private fun persist() {
        try {
            val value = obj { put("owner", owner?.let(::JsonPrimitive) ?: JsonNull); put("enabled", enabled); put("mappings", RingGestureActions.json(mappings)) }
            val encoded = android.util.Base64.encodeToString(crypto.seal(value.toString().toByteArray(Charsets.UTF_8), aad), android.util.Base64.NO_WRAP)
            if (!preferences.edit().putString("policy", encoded).commit()) throw IllegalStateException("Policy commit failed")
        } catch (_: Exception) {
            failed = true; enabled = false
            throw RingFailure("gesture_store_unavailable", RingText.text(R.string.ring_gestures_save_failed))
        }
    }
    @Synchronized private fun key(event: KeyEvent, execute: (RingGestureDispatch) -> Boolean): Boolean {
        val current = binding() ?: return false
        val now = SystemClock.uptimeMillis()
        if (current.identity != owner || foregroundUser()?.let { it != current.userId } == true || !enabled || failed || !accessGranted() ||
            !qualifiedDevice(event.device) || event.repeatCount != 0 || event.action != KeyEvent.ACTION_DOWN ||
            event.eventTime > now || now - event.eventTime > 2_000) return false
        val input = RingGestureActions.input(event.keyCode) ?: return false
        val action = mappings[input]?.takeUnless { it == RingGestureAction.NONE } ?: return false
        val dedup = "${event.deviceId}:${event.downTime}:${event.keyCode}"
        if (!seen.add(dedup)) return false
        while (seen.size > 64) seen.remove(seen.first())
        val dispatch = RingGestureDispatch(UUID.randomUUID().toString(), action)
        actionOwner = current.identity
        lastAction = RingGestureOutcome(dispatch.id, action, RingGestureResult.DISPATCHING)
        val accepted = execute(dispatch)
        if (!accepted) outcome(dispatch.id, RingGestureResult.ANDROID_REJECTED) else refresh()
        return accepted
    }
    @Synchronized private fun outcome(id: String, status: RingGestureResult) {
        if (status == RingGestureResult.DISPATCHING || lastAction?.operationId != id || binding()?.identity != actionOwner) return
        lastAction = lastAction!!.copy(result = status); refresh()
    }
    @Synchronized private fun revoked() {
        if (closed) return
        enabled = false; seen.clear(); try { persist() } catch (_: Exception) { failed = true }; refresh()
    }
    @Synchronized fun bindingChanged(clear: Boolean) {
        enabled = false; seen.clear(); lastAction = null; actionOwner = null
        if (clear) { owner = null; mappings = RingGestureInput.entries.associateWith { RingGestureAction.NONE } }
        try { persist() } catch (_: Exception) { failed = true }; refresh()
    }
    @Synchronized fun foregroundDetached() { seen.clear(); lastAction = null; actionOwner = null; refresh() }
    @Synchronized fun close() { closed = true; RingGestureRelay.detach(this) }
}

internal data class RingGestureDispatch(val id: String, val action: RingGestureAction)
internal object RingGestureRelay {
    private var owner: Any? = null
    private var key: ((KeyEvent, (RingGestureDispatch) -> Boolean) -> Boolean)? = null
    private var outcome: ((String, RingGestureResult) -> Unit)? = null
    private var revoked: (() -> Unit)? = null
    @Synchronized fun attach(owner: Any, key: (KeyEvent, (RingGestureDispatch) -> Boolean) -> Boolean,
        outcome: (String, RingGestureResult) -> Unit, revoked: () -> Unit) { this.owner = owner; this.key = key; this.outcome = outcome; this.revoked = revoked }
    @Synchronized fun detach(owner: Any) { if (this.owner === owner) { this.owner = null; key = null; outcome = null; revoked = null } }
    fun key(event: KeyEvent, execute: (RingGestureDispatch) -> Boolean): Boolean = synchronized(this) { key }?.invoke(event, execute) == true
    fun outcome(id: String, status: RingGestureResult) { synchronized(this) { outcome }?.invoke(id, status) }
    fun revoked() { synchronized(this) { revoked }?.invoke() }
}
