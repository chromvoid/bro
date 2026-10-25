package assistant.mobile.android

import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import assistant.mobile.core.calls.CallIceConfiguration
import assistant.mobile.core.calls.CallMediaConnection
import assistant.mobile.core.calls.CallMediaListener
import assistant.mobile.platform.AndroidWebRtcPeer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeCallsJniSmokeTest {
    @Test fun packagedWebRtcJniLoadsOnTheSixteenKiBEmulator() {
        assertEquals("The Android CI emulator must exercise 16 KiB JNI pages", 16_384L, Os.sysconf(OsConstants._SC_PAGESIZE))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidWebRtcPeer.initialize(context)
        AndroidWebRtcPeer(context, object : CallMediaListener {
            override fun connection(state: CallMediaConnection) = Unit
        }).use { it.configure(CallIceConfiguration(emptyList())) }
    }
}
