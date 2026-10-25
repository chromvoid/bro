package assistant.mobile.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppearanceTest {
    @Test
    fun systemTextScaleIsAppliedHalfwayAndCapped() {
        assertEquals(0.925f, effectiveFontScale(0.85f))
        assertEquals(1f, effectiveFontScale(1f))
        assertEquals(1.1f, effectiveFontScale(1.2f))
        assertEquals(1.25f, effectiveFontScale(1.5f))
        assertEquals(1.25f, effectiveFontScale(2f))
    }

    @Test
    fun appearanceDtoRoundTripsAndAllowsAdditiveFields() {
        val json = Json { ignoreUnknownKeys = true }
        val state = AppearanceState(ThemePreference.Light)
        assertEquals(state, json.decodeFromString<AppearanceState>(json.encodeToString(state)))
        assertEquals(state, json.decodeFromString<AppearanceState>("""{"theme":"Light","future":true}"""))
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString<AppearanceState>("""{"theme":"Unknown"}""")
        }
    }
}
