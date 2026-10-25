package assistant.mobile.platform

import assistant.mobile.core.NativeServerKind
import assistant.mobile.core.NativeServerSelection
import assistant.mobile.core.NativeServerSelectionStore
import assistant.mobile.core.OriginMoveConfigResult
import assistant.mobile.core.OriginMoveCoordinator
import assistant.mobile.core.OriginMoveMeResult
import assistant.mobile.core.OriginMoveProbe
import assistant.mobile.core.OriginMoveRecord
import assistant.mobile.core.OriginMoveRecordStore
import assistant.mobile.core.OriginMoveSessions
import assistant.mobile.core.OriginMoveState
import assistant.mobile.core.StoredSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AndroidNativeOriginsTest {
    private var previousCoordinator: OriginMoveCoordinator? = null

    @Before fun clearProcessChoice() {
        previousCoordinator = AndroidNativeOriginsTestAccess.replace(null)
    }

    @After fun restoreProcessChoice() {
        AndroidNativeOriginsTestAccess.replace(previousCoordinator)
    }

    @Test fun customSelectionAcceptsItsLoginOriginAndRejectsBuildAndPreviousOrigins() {
        val origins = AndroidNativeOriginsTestAccess.get(SelectionStore(selection(CUSTOM))) { coordinator(it) }

        assertTrue(origins.acceptsOrigin(CUSTOM))
        assertFalse(origins.acceptsOrigin(DEFAULT))
        assertFalse(origins.acceptsOrigin(PREVIOUS))
        assertFalse(origins.acceptsOrigin(null))
    }

    @Test fun committedSelectionRequiresRestartWithoutChangingTheFrozenProcessHome() {
        val selections = SelectionStore(selection(CUSTOM))
        val origins = AndroidNativeOriginsTestAccess.get(selections) { coordinator(it) }
        assertNull(AndroidNativeOriginsTestAccess.pendingRestartOrigin(origins, selections, DEFAULT))

        selections.write(selection(NEXT))
        assertEquals(NEXT, AndroidNativeOriginsTestAccess.pendingRestartOrigin(origins, selections, DEFAULT))
        assertEquals(CUSTOM, origins.homeOrigin)
        assertEquals(CUSTOM, origins.currentOrigin)

        AndroidNativeOriginsTestAccess.replace(null)
        val restarted = AndroidNativeOriginsTestAccess.get(selections) { coordinator(it) }
        assertEquals(NEXT, restarted.homeOrigin)
        assertNull(AndroidNativeOriginsTestAccess.pendingRestartOrigin(restarted, selections, DEFAULT))
    }

    @Test fun existingDefaultMoveDoesNotCauseARestartLoop() {
        val selected = selection(DEFAULT).copy(kind = NativeServerKind.DEFAULT)
        val moved = OriginMoveRecord(PREVIOUS, DEFAULT, selected.instanceId, OriginMoveState.MOVED, selected.selectedAt)
        val selections = SelectionStore(selected)
        val origins = AndroidNativeOriginsTestAccess.get(selections) { coordinator(it, initialRecord = moved) }
        assertEquals(PREVIOUS, origins.homeOrigin)
        assertEquals(DEFAULT, origins.currentOrigin)
        assertNull(AndroidNativeOriginsTestAccess.pendingRestartOrigin(origins, selections, DEFAULT))
    }

    private fun coordinator(
        selection: NativeServerSelection?,
        sessions: OriginMoveSessions = noSessions,
        initialRecord: OriginMoveRecord? = null,
    ) =
        OriginMoveCoordinator(DEFAULT, PREVIOUS, sessions, object : OriginMoveRecordStore {
            override fun read(): OriginMoveRecord? = initialRecord
            override fun write(record: OriginMoveRecord) = error("No origin move is expected")
        }, object : OriginMoveProbe {
            override suspend fun config(origin: String): OriginMoveConfigResult = error("No probe is expected")
            override suspend fun me(origin: String, session: StoredSession): OriginMoveMeResult =
                error("No probe is expected")
        }, selection = selection)

    private val noSessions = object : OriginMoveSessions {
        override fun exists(instance: String, origin: String) = false
        override suspend fun load(instance: String, origin: String): StoredSession? = null
    }

    private class SelectionStore(var selection: NativeServerSelection?) : NativeServerSelectionStore {
        var reads = 0
        var writes = 0
        override fun read(): NativeServerSelection? { reads++; return selection }
        override fun write(selection: NativeServerSelection) { writes++; this.selection = selection }
    }

    private fun selection(origin: String) = NativeServerSelection(NativeServerKind.CUSTOM, origin,
        "11111111-1111-4111-8111-111111111111", "Synthetic server", "2026-10-22T12:00:00Z")

    private companion object {
        const val DEFAULT = "https://default.synthetic.invalid"
        const val PREVIOUS = "https://previous.synthetic.invalid"
        const val CUSTOM = "https://custom.synthetic.invalid"
        const val NEXT = "https://next.synthetic.invalid"
    }
}
