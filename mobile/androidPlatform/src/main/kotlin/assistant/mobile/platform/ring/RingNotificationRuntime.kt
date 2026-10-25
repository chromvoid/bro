package assistant.mobile.platform.ring

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import assistant.mobile.platform.R
import java.util.LinkedHashMap
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

/** Encrypted local explicit allowlist. Android access alone never enables forwarding. */
class RingNotificationPolicy(private val context: Context, origin: String,
    private val granted: () -> Boolean = {
        val component = ComponentName(context, RingNotificationListener::class.java).flattenToString()
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")?.split(':')?.contains(component) == true
    }, private val applications: (() -> List<RingNotificationApp>)? = null) {
    private val preferences = context.getSharedPreferences("ring-notifications-${sha256(origin).take(24)}", Context.MODE_PRIVATE)
    private val crypto = RingCrypto("assistant.ring.notifications.v1.${sha256(origin).take(24)}")
    private val aad = "ring-notifications-v1\n$origin"
    private var owner: String? = null
    private var enabled = false
    private var allowed = emptySet<String>()
    private val seen = object : LinkedHashMap<String, Boolean>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 256
    }
    init {
        preferences.getString("policy", null)?.let {
            try {
                val bytes = android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
                require(bytes.size <= 32_768)
                val saved = ringJson.parseToJsonElement(crypto.open(bytes, aad).toString(Charsets.UTF_8)) as JsonObject
                owner = saved.string("owner"); enabled = saved.bool("enabled") == true
                allowed = (saved["allowed_packages"] as? JsonArray)?.map { item ->
                    (item as JsonPrimitive).takeIf { p -> p.isString }!!.content
                }?.toSet() ?: emptySet()
                require(allowed.size <= 100)
                (saved["seen"] as? JsonArray)?.take(256)?.forEach { item ->
                    val key = (item as? JsonPrimitive)?.takeIf { p -> p.isString && p.content.length == 64 }?.content
                    if (key != null) seen[key] = true
                }
            } catch (_: Exception) { throw RingFailure("notification_store_unavailable", RingText.text(R.string.ring_notifications_store_unavailable)) }
        }
    }
    fun accessGranted(): Boolean = granted()
    fun apps(): List<RingNotificationApp> {
        applications?.let { return it() }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val values = context.packageManager.queryIntentActivities(intent, 0)
        return values.mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            if (packageName == context.packageName) return@mapNotNull null
            RingNotificationApp(packageName, info.loadLabel(context.packageManager).toString().take(100))
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }.take(500)
    }
    @Synchronized fun snapshot(binding: RingWorkoutBinding?): RingNotificationState = RingNotificationState(
        owner = binding?.let(RingPhoneOwner::from), accessGranted = accessGranted(),
        enabled = binding?.identity == owner && enabled && accessGranted(),
        allowedPackages = if (binding?.identity == owner) allowed else emptySet(), apps = if (binding != null) apps() else emptyList())
    @Synchronized fun configure(binding: RingWorkoutBinding, on: Boolean, names: Set<String>) {
        val installed = apps().map { it.packageName }.toSet()
        if (names.size > 100 || names.any { it !in installed })
            throw RingFailure("bad_payload", RingText.text(R.string.ring_notifications_choose_installed))
        if (on && !accessGranted()) throw RingFailure("notification_access_required", RingText.text(R.string.ring_notifications_access_required))
        owner = binding.identity; enabled = on; allowed = names.toSet(); seen.clear(); persist()
    }
    private fun persist() {
        try {
            val value = obj { put("owner", owner?.let(::JsonPrimitive) ?: JsonNull); put("enabled", enabled)
                put("allowed_packages", JsonArray(allowed.sorted().map(::JsonPrimitive))); put("seen", JsonArray(seen.keys.map(::JsonPrimitive))) }
            val encoded = android.util.Base64.encodeToString(crypto.seal(value.toString().toByteArray(Charsets.UTF_8), aad), android.util.Base64.NO_WRAP)
            if (!preferences.edit().putString("policy", encoded).commit()) throw IllegalStateException("Policy commit failed")
        } catch (_: Exception) {
            enabled = false
            throw RingFailure("notification_store_unavailable", RingText.text(R.string.ring_notifications_save_failed))
        }
    }
    @Synchronized fun accept(binding: RingWorkoutBinding, packageName: String, key: String, at: Long, text: String): JsonObject? {
        if (!canForward(binding, packageName)) return null
        val content = RingNotificationText.bounded(text)
        if (content.isEmpty()) return null
        val dedup = sha256("$packageName\n$key\n$at\n$content")
        if (seen.put(dedup, true) != null) return null
        persist() // Hash only, committed before one-shot forwarding. Never a replay queue.
        val type = when (packageName) {
            "com.facebook.katana" -> 5; "com.whatsapp" -> 6; "com.twitter.android" -> 7
            "com.skype.raider" -> 8; "jp.naver.line.android" -> 9; "com.tencent.mm" -> 3; "com.tencent.mobileqq" -> 2
            else -> 1 // Generic SMS fallback still requires the independent qualified protocol recipe.
        }
        return obj { put("type", "notification"); put("message_type", type); put("text", content) }
    }
    @Synchronized fun canForward(binding: RingWorkoutBinding, packageName: String): Boolean =
        binding.identity == owner && enabled && accessGranted() && packageName in allowed
    @Synchronized fun revoked() { enabled = false; seen.clear(); persist() }
    @Synchronized fun bindingChanged(clear: Boolean) { seen.clear(); enabled = false; if (clear) { owner = null; allowed = emptySet() }; persist() }
}

/** App lifetime forwarding: notification bodies live only until this single qualified BLE attempt. */
class RingNotificationRuntime(private val context: Context, origin: String,
    private val binding: () -> RingWorkoutBinding?, private val foregroundUser: () -> String?,
    private val capabilities: () -> JsonObject, private val radioBusy: () -> Boolean,
    private val execute: suspend (JsonObject, String, RingWorkoutBinding, Boolean) -> JsonObject) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var policy: RingNotificationPolicy? = null
    private var failed = false
    private var closed = false
    @Volatile private var job: Job? = null
    private val mutable = MutableStateFlow(RingNotificationState())
    val states: StateFlow<RingNotificationState> get() = mutable
    init {
        try { policy = RingNotificationPolicy(context, origin) } catch (_: Exception) { failed = true }
        RingNotificationRelay.attach(this, ::notification, ::accessLost)
        refresh()
    }
    fun busy(): Boolean = job?.isActive == true
    @Synchronized fun refresh() {
        val owner = binding()?.takeIf { foregroundUser() == it.userId }
        val snapshot = try { policy?.snapshot(owner) ?: RingNotificationState() }
            catch (_: Exception) { failed = true; RingNotificationState() }
        mutable.value = if (failed) snapshot.copy(enabled = false, error = RingNativeError("notification_store_unavailable",
            RingText.text(R.string.ring_notifications_store_unavailable))) else snapshot
    }
    private fun requireOwner(expected: RingPhoneOwner): RingWorkoutBinding = binding()?.takeIf {
        expected.matches(it) && foregroundUser() == it.userId
    } ?: throw RingFailure("binding_changed", RingText.text(R.string.ring_phone_binding_changed))
    @Synchronized fun action(action: RingNotificationAction) {
        val owner = requireOwner(action.owner)
        if (failed) throw RingFailure("notification_store_unavailable", RingText.text(R.string.ring_notifications_store_unavailable))
        when (action) {
            is RingNotificationAction.OpenAccess -> context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            is RingNotificationAction.Configure -> {
                job?.cancel()
                try { policy!!.configure(owner, action.enabled, action.allowedPackages) }
                catch (failure: RingFailure) { if (failure.code == "notification_store_unavailable") { failed = true; refresh() }; throw failure }
            }
        }
        refresh()
    }
    @Synchronized private fun notification(packageName: String, key: String, at: Long, text: String) {
        val owner = binding() ?: return
        val now = System.currentTimeMillis()
        if (failed || busy() || radioBusy() || at < now - 60_000 || at > now + 5_000 ||
            foregroundUser()?.let { it != owner.userId } == true) return
        val command = try { policy?.accept(owner, packageName, key, at, text) } catch (_: Exception) { failed = true; refresh(); return } ?: return
        try { RingDeviceCommands.requireQualified(capabilities(), command) } catch (_: RingFailure) { return }
        job = scope.launch {
            try {
                val permitted = synchronized(this@RingNotificationRuntime) { !failed && binding() == owner &&
                    foregroundUser()?.let { it != owner.userId } != true && policy?.canForward(owner, packageName) == true &&
                    at >= System.currentTimeMillis() - 60_000 }
                currentCoroutineContext().ensureActive()
                if (permitted) execute(command, UUID.randomUUID().toString(), owner, false)
            } catch (_: Exception) { /* Drop once; notification content never becomes diagnostics or recovery data. */ }
        }
    }
    @Synchronized private fun accessLost() {
        if (closed) return
        job?.cancel(); try { policy?.revoked() } catch (_: Exception) { failed = true }; refresh()
    }
    @Synchronized fun bindingChanged(clear: Boolean) {
        job?.cancel(); try { policy?.bindingChanged(clear) } catch (_: Exception) { failed = true }; refresh()
    }
    @Synchronized fun foregroundDetached() { refresh() }
    fun close() {
        synchronized(this) { closed = true; RingNotificationRelay.detach(this); scope.cancel() }
        runBlocking { scope.coroutineContext[Job]?.join() }
    }
}
