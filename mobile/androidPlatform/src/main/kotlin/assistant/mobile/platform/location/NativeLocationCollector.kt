package assistant.mobile.platform.location

import assistant.mobile.platform.AndroidNativeOrigins

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.canonicalOrigin
import assistant.mobile.core.location.BackgroundLocationState
import assistant.mobile.core.location.BackgroundRecording
import assistant.mobile.core.location.LocationAcknowledgement
import assistant.mobile.core.location.LocationCollectorGrant
import assistant.mobile.core.location.LocationQueuePolicy
import assistant.mobile.core.location.LocationUploadDisposition
import assistant.mobile.core.location.PositionPermission
import assistant.mobile.core.location.PositionSource
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class AndroidBackgroundRecording(
    context: Context,
    origin: String,
    private val position: PositionSource,
    private val requestBackgroundPermission: suspend () -> Boolean,
    private val enroll: suspend (AccountNamespace, String) -> LocationCollectorGrant,
    private val isCurrentAccount: (AccountNamespace) -> Boolean,
    private val isCurrentEnrollment: (AccountNamespace) -> Boolean = isCurrentAccount,
) : BackgroundRecording {
    val collector = NativeLocationCollector.get(context, origin)
    private val enableMutex = Mutex()

    override suspend fun status(namespace: AccountNamespace): BackgroundLocationState =
        withContext(Dispatchers.IO) { collector.status(namespace) { isCurrentAccount(namespace) } }

    override suspend fun enable(namespace: AccountNamespace): BackgroundLocationState = enableMutex.withLock {
        if (position.request().permission != PositionPermission.GRANTED) return@withLock status(namespace)
        if (!collector.backgroundGranted() && !requestBackgroundPermission()) return@withLock status(namespace)
        if (!collector.backgroundGranted()) return@withLock status(namespace)
        if (status(namespace).enabled) return@withLock status(namespace)
        val installation = UUID.randomUUID().toString()
        val grant = enroll(namespace, installation)
        withContext(Dispatchers.IO) { collector.enable(namespace, installation, grant) {
            isCurrentAccount(namespace) && isCurrentEnrollment(namespace)
        } }
    }

    override suspend fun disable(): BackgroundLocationState = collector.disable()
}

/** Native-only collector grant and account-isolated queue; human session credentials never enter this class. */
class NativeLocationCollector(
    context: Context,
    origin: String,
    private val transport: CollectorTransport = NarrowCollectorTransport(origin,
        serverOrigin = { AndroidNativeOrigins.serverOrigin(context, origin) }),
    private val updates: LocationUpdateRegistration = AndroidLocationUpdateRegistration(context, origin),
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val context = context.applicationContext
    val origin = canonicalOrigin(origin, allowInsecureLoopback = true)
    private val lock = Any()
    private val uploadMutex = Mutex()
    private val store = NativeLocationStore(this.context, this.origin)
    private val work get() = WorkManager.getInstance(context)
    private val workSuffix = locationDigest(this.origin)
    private val uploadName = "native-location-upload-$workSuffix"
    private val periodicName = "native-location-periodic-$workSuffix"
    private val preferences = this.context.getSharedPreferences(REGISTRY, Context.MODE_PRIVATE)
    private var serverSwitchStarted = false
    private var serverSwitchToken: String? = null

    init { instances[this.origin] = this }

    fun foregroundGranted(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    fun backgroundGranted(): Boolean = foregroundGranted() &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun hasServerData(): Boolean = synchronized(lock) {
        store.hasData() || synchronized(registryLock) {
            origin in preferences.getStringSet(ORIGINS, emptySet()).orEmpty()
        }
    }

    fun status(namespace: AccountNamespace? = null, isCurrentAccount: () -> Boolean = { true }): BackgroundLocationState = synchronized(lock) {
        if (!isCurrentAccount()) return@synchronized BackgroundLocationState(
            enabled = false,
            permission = if (backgroundGranted()) PositionPermission.GRANTED else PositionPermission.PROMPT,
            queued = 0,
        )
        val record = activeGrant()
        if (record != null && namespace != null && record.namespace != namespace) disableLocally()
        BackgroundLocationState(
            enabled = activeGrant() != null && backgroundGranted(),
            permission = if (backgroundGranted()) PositionPermission.GRANTED else PositionPermission.PROMPT,
            queued = store.queued(),
        )
    }

    fun enable(namespace: AccountNamespace, installation: String, grant: LocationCollectorGrant,
        isCurrentAccount: () -> Boolean = { true }): BackgroundLocationState = synchronized(lock) {
        if (serverSwitchStarted) throw CancellationException("Location server switch started")
        check(isCurrentAccount()) { "Location account changed" }
        require(namespace.origin == origin && namespace.userId == grant.userId && installation.isNotBlank())
        require(grant.collectorId.isNotBlank() && grant.token.isNotBlank() && !LocationQueuePolicy.expired(grant.expiresAt, clockMs()))
        check(backgroundGranted()) { "Location background permission is unavailable" }
        val previous = activeGrant()
        val sameInstallation = previous != null && previous.namespace == namespace &&
            previous.installation == installation && previous.grant.token == grant.token
        val captureNotBeforeMs = if (sameInstallation) requireNotNull(previous).captureNotBeforeMs else {
            disableLocally()
            clockMs()
        }
        store.saveGrant(StoredLocationCollector(namespace, installation, grant, captureNotBeforeMs))
        synchronized(registryLock) {
            preferences.edit().putStringSet(ORIGINS, preferences.getStringSet(ORIGINS, emptySet()).orEmpty() + origin).commit()
        }
        start()
        status(namespace)
    }

    /** Stop immediately, then attempt narrow-token revocation; an unavailable network cannot keep recording. */
    suspend fun disable(): BackgroundLocationState {
        val token = withContext(Dispatchers.IO) {
            synchronized(lock) { store.grant()?.grant?.token.also { disableLocally() } }
        }
        if (token != null) try { transport.post(REVOKE, JsonObject(emptyMap()), token) }
        catch (failure: Exception) { if (failure is CancellationException) throw failure }
        return withContext(Dispatchers.IO) { status() }
    }

    /** Erase this origin without a human session, retaining only an in-memory narrow-token revoke. */
    suspend fun clearForServerSwitch(): suspend () -> Unit {
        val token = withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (!serverSwitchStarted) serverSwitchToken = runCatching { store.grant()?.grant?.token }.getOrNull()
                serverSwitchStarted = true
                disableLocally()
                serverSwitchToken
            }.also {
                work.cancelUniqueWork(uploadName).result.get()
                work.cancelUniqueWork(periodicName).result.get()
            }
        }
        return {
            if (token != null) try { transport.post(REVOKE, JsonObject(emptyMap()), token) }
            catch (failure: Exception) { if (failure is CancellationException) throw failure }
        }
    }

    /** Logout is synchronous erasure. A stale logout never removes another account's collector. */
    fun disableLocally(namespace: AccountNamespace? = null) = synchronized(lock) {
        if (namespace != null) {
            val record = store.grant()
            if (record != null && record.namespace != namespace) return@synchronized
        }
        updates.stop()
        store.clear()
        work.cancelUniqueWork(uploadName)
        work.cancelUniqueWork(periodicName)
        synchronized(registryLock) {
            preferences.edit().putStringSet(ORIGINS, preferences.getStringSet(ORIGINS, emptySet()).orEmpty() - origin).commit()
        }
    }

    /** Boot/update restore only existing unexpired consent; they never request Android permissions. */
    fun resume() = synchronized(lock) { if (activeGrant() != null && backgroundGranted()) start() }

    private fun start() {
        updates.start()
        val periodic = PeriodicWorkRequestBuilder<NativeLocationUploadWorker>(1, TimeUnit.HOURS)
            .setInputData(workDataOf(INPUT_ORIGIN to origin))
            .setConstraints(networkConstraints()).build()
        work.enqueueUniquePeriodicWork(periodicName, ExistingPeriodicWorkPolicy.KEEP, periodic)
    }

    fun received(locations: List<Location>) = synchronized(lock) {
        val record = activeGrant() ?: return@synchronized
        if (!backgroundGranted()) { updates.stop(); return@synchronized }
        // Android may deliver a previously batched fix after logout and a new person's enrollment.
        val points = locations.filter { validLocation(it) && it.time >= record.captureNotBeforeMs }
            .mapNotNull { runCatching { nativeLocationFix(it).normalized() }.getOrNull() }
        if (points.isEmpty()) return@synchronized
        store.append(record, points, clockMs())
        val upload = OneTimeWorkRequestBuilder<NativeLocationUploadWorker>()
            .setInputData(workDataOf(INPUT_ORIGIN to origin))
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
        work.enqueueUniqueWork(uploadName, ExistingWorkPolicy.KEEP, upload)
    }

    /** A retry keeps original timestamps. ACK only erases that grant's sent rows, atomically with dedup memory. */
    suspend fun upload(): Boolean = uploadMutex.withLock {
        while (true) {
            val batch = withContext(Dispatchers.IO) {
                synchronized(lock) {
                    val record = activeGrant() ?: return@synchronized null
                    val rows = store.oldest(record, LocationQueuePolicy.BATCH_SIZE)
                    val (send, drop) = rows.partition { (time, point) ->
                        point != null && !LocationQueuePolicy.expiredPoint(time, clockMs())
                    }
                    store.remove(drop.map { it.first })
                    Triple(record, send, rows.isEmpty())
                }
            } ?: return@withLock true
            val (record, rows, empty) = batch
            if (empty) return@withLock true
            if (rows.isEmpty()) continue
            val body = buildJsonObject { put("points", JsonArray(rows.map { (_, fix) ->
                val value = Json.encodeToJsonElement(assistant.mobile.core.location.LocationFix.serializer(), requireNotNull(fix)).jsonObject
                JsonObject(value + ("source" to JsonPrimitive("background")))
            })) }
            val reply = try { transport.post(POINTS, body, record.grant.token) }
                catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    return@withLock false
                }
            val acknowledgement = if (reply.status in 200..299) {
                runCatching {
                    Json.decodeFromString(LocationAcknowledgement.serializer(), reply.body)
                }.getOrNull()
            } else null
            val disposition = LocationQueuePolicy.uploadDisposition(reply.status, acknowledgement, rows.size)
            if (disposition == LocationUploadDisposition.RETRY) return@withLock false
            withContext(Dispatchers.IO) {
                synchronized(lock) {
                    if (store.grant()?.grant?.token == record.grant.token) when (disposition) {
                        LocationUploadDisposition.REVOKE -> disableLocally()
                        LocationUploadDisposition.ACK -> store.remove(rows.map { it.first }, acknowledged = true)
                        LocationUploadDisposition.DISCARD -> store.remove(rows.map { it.first })
                        LocationUploadDisposition.RETRY -> Unit
                    }
                }
            }
        }
        @Suppress("UNREACHABLE_CODE") true
    }

    private fun activeGrant(): StoredLocationCollector? {
        val record = store.grant()
        if (record == null) {
            if (store.hasGrant() || store.queued() > 0) disableLocally()
            return null
        }
        if (record.namespace.origin != origin || LocationQueuePolicy.expired(record.grant.expiresAt, clockMs())) {
            disableLocally()
            return null
        }
        return record
    }

    private fun networkConstraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    companion object {
        const val INPUT_ORIGIN = "location-origin"
        const val POINTS = "/v1/me/location/collector/points"
        const val REVOKE = "/v1/me/location/collector/revoke"
        private const val REGISTRY = "native-location-origins"
        private const val ORIGINS = "origins"
        private val registryLock = Any()
        private val instances = ConcurrentHashMap<String, NativeLocationCollector>()

        fun get(context: Context, origin: String): NativeLocationCollector {
            val canonical = canonicalOrigin(origin, allowInsecureLoopback = true)
            return instances[canonical] ?: synchronized(instances) {
                instances[canonical] ?: NativeLocationCollector(context, canonical)
            }
        }

        fun installed(context: Context): List<NativeLocationCollector> {
            val origins = synchronized(registryLock) {
                context.getSharedPreferences(REGISTRY, Context.MODE_PRIVATE)
                    .getStringSet(ORIGINS, emptySet()).orEmpty().toSet()
            }
            return origins.mapNotNull { origin ->
                if (cancelIfForeignOrigin(context, origin)) null
                else runCatching { get(context, origin) }.getOrNull()
            }
        }

        /** Stale OS inputs cannot open another server's private store or revive its background work. */
        fun cancelIfForeignOrigin(context: Context, origin: String): Boolean {
            val canonical = runCatching { canonicalOrigin(origin, allowInsecureLoopback = true) }.getOrNull()
                ?: return true
            if (canonical == AndroidNativeOrigins.homeOrigin(context)) return false
            instances[canonical]?.updates?.stop()
            AndroidLocationUpdateRegistration(context, canonical).stop()
            val suffix = locationDigest(canonical)
            WorkManager.getInstance(context).apply {
                cancelUniqueWork("native-location-upload-$suffix")
                cancelUniqueWork("native-location-periodic-$suffix")
            }
            val preferences = context.getSharedPreferences(REGISTRY, Context.MODE_PRIVATE)
            synchronized(registryLock) {
                preferences.edit().putStringSet(ORIGINS,
                    preferences.getStringSet(ORIGINS, emptySet()).orEmpty() - canonical).commit()
            }
            return true
        }
    }
}
