package assistant.mobile.platform

import assistant.mobile.core.NativeServerSelection
import assistant.mobile.core.NativeServerSelectionStore
import assistant.mobile.core.OriginMoveCoordinator

/** Instrumentation replaces the same process source; this seam is absent from release bytecode. */
object AndroidNativeOriginsTestAccess {
    fun replace(coordinator: OriginMoveCoordinator?): OriginMoveCoordinator? = synchronized(AndroidNativeOrigins) {
        AndroidNativeOrigins.coordinator.also { AndroidNativeOrigins.coordinator = coordinator }
    }

    fun get(
        selections: NativeServerSelectionStore,
        create: (NativeServerSelection?) -> OriginMoveCoordinator,
    ): OriginMoveCoordinator = AndroidNativeOrigins.get(selections, create)

    fun pendingRestartOrigin(
        origins: OriginMoveCoordinator,
        selections: NativeServerSelectionStore,
        defaultOrigin: String,
    ): String? = AndroidNativeOrigins.pendingRestartOrigin(origins, selections, defaultOrigin)
}
