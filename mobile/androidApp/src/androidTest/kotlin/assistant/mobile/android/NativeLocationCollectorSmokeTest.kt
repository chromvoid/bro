package assistant.mobile.android

import android.Manifest
import android.location.Location
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import assistant.mobile.core.location.PositionPermission
import assistant.mobile.core.location.PositionPrecision
import assistant.mobile.platform.location.AndroidPositionSource
import assistant.mobile.platform.location.CurrentPositionProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android permission grants; every position is synthetic. */
@RunWith(AndroidJUnit4::class)
class NativeLocationCollectorSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun a_statusNeverPromptsAndDenialApproximateAndPreciseAreHonest() = runBlocking {
        // This fresh CI APK has no location grant. Revoking one here would kill instrumentation.
        context.getSharedPreferences("native-location-permission", 0).edit().clear().commit()
        val requests = AtomicInteger()
        val fixes = AtomicInteger()
        val source = AndroidPositionSource(context, requestPermission = { requests.incrementAndGet(); false },
            provider = CurrentPositionProvider { fixes.incrementAndGet(); fix(System.currentTimeMillis(), 17.2f) })
        assertEquals(PositionPermission.PROMPT, source.status().permission)
        assertNull(source.current())
        assertEquals(0, requests.get())
        assertEquals(0, fixes.get())
        assertEquals(PositionPermission.DENIED, source.request().permission)
        assertEquals(1, requests.get())
        assertNull(source.current())
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        assertEquals(PositionPrecision.APPROXIMATE, source.status().precision)
        val current = requireNotNull(source.current())
        assertEquals(18.0, current.accuracyMeters, 0.0)
        assertEquals(62.12345, current.latitude, 0.0)
        assertEquals(1, fixes.get())
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        assertEquals(PositionPrecision.PRECISE, source.status().precision)
        assertEquals(PositionPermission.GRANTED, source.status().permission)
        source.request()
        assertEquals(1, requests.get())
    }

    private fun fix(time: Long, accuracy: Float = 20f) = Location("synthetic").apply {
        latitude = 62.12345; longitude = 42.54321; this.accuracy = accuracy; this.time = time
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }
}
