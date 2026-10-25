package assistant.mobile.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import assistant.mobile.core.NativeServerCheckResult
import assistant.mobile.core.NativeServerKind
import assistant.mobile.core.NativeServerSelection
import assistant.mobile.core.NativeServerSelectionStore
import assistant.mobile.core.OriginMoveCoordinator
import assistant.mobile.core.OriginMoveSessions
import assistant.mobile.core.StoredSession
import assistant.mobile.core.nativeServerSelection

/** The app and its background collectors share one process-wide move decision. */
object AndroidNativeOrigins {
    internal var coordinator: OriginMoveCoordinator? = null

    @Synchronized
    fun get(context: Context): OriginMoveCoordinator = coordinator ?: run {
        val application = context.applicationContext
        val info = application.packageManager.getApplicationInfo(application.packageName, PackageManager.GET_META_DATA)
        val current = requireNotNull(info.metaData?.getString("assistant.origin")) {
            "The application must declare assistant.origin"
        }
        val previous = info.metaData?.getString("assistant.previous_origin")
        val debug = info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val store = KeystoreSessionSecretStore(application)
        get(AndroidNativeServerSelectionStore(application, debug)) { selection ->
            OriginMoveCoordinator(
                current, previous,
                object : OriginMoveSessions {
                    override fun exists(instance: String, origin: String): Boolean = store.exists(instance, origin)
                    override suspend fun load(instance: String, origin: String): StoredSession? = store.load(instance, origin)
                },
                AndroidOriginMoveRecordStore(application, debug),
                AndroidOriginMoveProbe(current, debug),
                allowInsecureLoopback = debug,
                selection = selection,
            )
        }
    }

    @Synchronized
    internal fun get(
        selections: NativeServerSelectionStore,
        create: (NativeServerSelection?) -> OriginMoveCoordinator,
    ): OriginMoveCoordinator = coordinator ?: create(selections.read()).also {
        // Freeze the namespace before any consumer can change session storage.
        it.homeOrigin
        coordinator = it
    }

    /** Persists the next process's choice; switching and restarting belong to the caller. */
    @Synchronized
    fun select(context: Context, server: NativeServerCheckResult.Compatible) {
        val application = context.applicationContext
        val info = application.packageManager.getApplicationInfo(application.packageName, PackageManager.GET_META_DATA)
        val current = requireNotNull(info.metaData?.getString("assistant.origin")) {
            "The application must declare assistant.origin"
        }
        val debug = info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        AndroidNativeServerSelectionStore(application, debug).write(
            nativeServerSelection(server, current, allowInsecureLoopback = debug),
        )
    }

    fun homeOrigin(context: Context): String = get(context).homeOrigin

    /** A background activity launch may be denied; the next foreground entry must finish it. */
    fun pendingRestartOrigin(context: Context): String? {
        val application = context.applicationContext
        val origins = get(application)
        val info = application.packageManager.getApplicationInfo(application.packageName, PackageManager.GET_META_DATA)
        val current = requireNotNull(info.metaData?.getString("assistant.origin")) {
            "The application must declare assistant.origin"
        }
        val debug = info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return pendingRestartOrigin(origins, AndroidNativeServerSelectionStore(application, debug), current)
    }

    internal fun pendingRestartOrigin(
        origins: OriginMoveCoordinator,
        selections: NativeServerSelectionStore,
        defaultOrigin: String,
    ): String? {
        val selected = selections.read()
        if (selected == origins.initialServerSelection) return null
        val target = selected?.takeIf { it.kind == NativeServerKind.CUSTOM }?.origin ?: defaultOrigin
        return target.takeIf { it != origins.homeOrigin }
    }

    fun serverOrigin(context: Context, home: String): String = get(context).serverOrigin(home)
}
