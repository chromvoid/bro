package assistant.mobile.platform.ring

import org.junit.Assert.fail
import org.junit.Test

class RingDiagnosticsAccessTest {
    @Test fun externalAppsCannotExportOrStartCollectionEvenIfTheyHoldDumpPermission() {
        for (caller in listOf(1000, 10001, 10101, 102000, -1)) {
            try { RingDiagnosticsAccess.requireCaller(caller, 10123); fail("Accepted external UID $caller") }
            catch (_: SecurityException) { }
        }
        for (caller in listOf(0, 2000, 10123)) RingDiagnosticsAccess.requireCaller(caller, 10123)
    }
}
