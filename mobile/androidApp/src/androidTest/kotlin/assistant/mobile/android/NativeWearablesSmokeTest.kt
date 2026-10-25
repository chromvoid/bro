package assistant.mobile.android

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import assistant.mobile.android.whoop.WhoopCard
import assistant.mobile.android.whoop.WhoopDevice
import assistant.mobile.android.whoop.WhoopHeaderRow
import assistant.mobile.android.whoop.WhoopState
import assistant.mobile.core.ThemePreference
import assistant.mobile.design.NativeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pure Compose surfaces with synthetic native state: never contacts a real wearable. */
@RunWith(AndroidJUnit4::class)
class NativeWearablesSmokeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun whoopSectionOpensWithSyntheticDevice() {
        val state = WhoopState(permission = "granted", bluetoothEnabled = true,
            selected = WhoopDevice("00:00:00:00:00:52", "WHOOP Synthetic"), battery = 64,
            source = "synthetic-source", user = "synthetic-person")
        compose.setContent {
            At125Percent {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    WhoopHeaderRow(state, "UTC", onSync = {}, onOpenSource = {})
                    WhoopCard(state, onPermission = {}, onScan = {}, onSelect = {}, onSync = {},
                        onBackground = {}, onForget = {}, onInitialize = {})
                }
            }
        }
        compose.onNodeWithTag("whoop_title").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("whoop_selected").performScrollTo().assertTextContains("WHOOP Synthetic")
    }

    @Composable
    private fun At125Percent(content: @Composable () -> Unit) {
        val configuration = Configuration(LocalConfiguration.current).apply { fontScale = 1.5f }
        CompositionLocalProvider(LocalConfiguration provides configuration) {
            NativeTheme(ThemePreference.Light) {
                Surface(color = MaterialTheme.colorScheme.background, content = content)
            }
        }
    }
}
