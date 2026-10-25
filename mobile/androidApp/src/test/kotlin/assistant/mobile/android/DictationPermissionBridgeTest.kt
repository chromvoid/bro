package assistant.mobile.android

import assistant.mobile.core.dictation.DictationPermissionResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DictationPermissionBridgeTest {
    @Test
    fun classificationCoversEveryCombinationOfGrantRationaleAndPriorDenial() {
        val no = DictationPermissionResult.NotAnswered
        val blocked = DictationPermissionResult.Blocked
        val expectedDenied = listOf(no, blocked, no, no, blocked, blocked, no, no)
        var index = 0
        for (before in listOf(false, true)) for (after in listOf(false, true)) for (deniedBefore in listOf(false, true)) {
            assertEquals("before=$before after=$after deniedBefore=$deniedBefore", expectedDenied[index++],
                classifyDictationPermission(false, before, after, deniedBefore))
            assertEquals(DictationPermissionResult.Granted,
                classifyDictationPermission(true, before, after, deniedBefore))
        }
        assertEquals(8, index)
    }

    @Test
    fun cancelledTypedRequestCannotAnswerNextRequest() = runTest {
        for (oldResult in DictationPermissionResult.entries) {
            val bridge = DictationPermissionBridge()
            var launches = 0
            bridge.attach(Any()) { launches++ }
            val old = async { bridge.requestResult() }
            runCurrent()
            old.cancelAndJoin()
            val current = async { bridge.requestResult() }
            runCurrent()
            bridge.result(oldResult)
            runCurrent()
            assertEquals(2, launches)
            assertFalse(current.isCompleted)
            bridge.result(DictationPermissionResult.NotAnswered)
            assertEquals(DictationPermissionResult.NotAnswered, current.await())
        }
    }

    @Test
    fun detachedOwnerLateResultCannotCompleteTheNewOwnersRequest() = runTest {
        val bridge = DictationPermissionBridge()
        val oldOwner = Any()
        var oldLaunches = 0
        var newLaunches = 0
        bridge.attach(oldOwner) { oldLaunches++ }
        val detached = async { bridge.request() }
        runCurrent()
        bridge.detach(oldOwner)
        assertFalse(detached.await())

        val newOwner = Any()
        bridge.attach(newOwner) { newLaunches++ }
        val current = async { bridge.request() }
        runCurrent()
        assertEquals(1, oldLaunches)
        assertEquals(0, newLaunches)
        bridge.detach(oldOwner)
        assertFalse(current.isCompleted)

        bridge.result(false)
        runCurrent()
        assertEquals(1, newLaunches)
        assertFalse(current.isCompleted)
        bridge.result(true)
        assertTrue(current.await())
    }

    @Test
    fun launcherFailureReachesItsRequestAndDoesNotBlockTheNextLaunch() = runTest {
        val bridge = DictationPermissionBridge()
        // Request metadata prevents coroutine stacktrace recovery from copying this fixture.
        // Assert the bridge propagates the original failure, independently of that recovery.
        class LauncherFailure(val request: Any) : IllegalStateException("Synthetic launcher failure")
        val failure = LauncherFailure(Any())
        var launches = 0
        bridge.attach(Any()) {
            launches++
            if (launches == 1) throw failure
            bridge.result(true)
        }
        assertSame(failure, runCatching { bridge.request() }.exceptionOrNull())
        assertTrue(bridge.request())
        assertEquals(2, launches)
    }

    @Test
    fun requestWithoutVisibleOwnerFailsWithoutLaunching() = runTest {
        val bridge = DictationPermissionBridge()
        assertFalse(bridge.request())
        bridge.result(true)
        assertFalse(bridge.request())
    }
}
