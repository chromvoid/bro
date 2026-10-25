package assistant.mobile.core

import kotlinx.serialization.Serializable
import kotlin.math.min

@Serializable
enum class ThemePreference { System, Light, Dark }

@Serializable
data class AppearanceState(val theme: ThemePreference = ThemePreference.System)

sealed interface AppearanceAction {
    data class SelectTheme(val theme: ThemePreference) : AppearanceAction
}

fun reduceAppearance(state: AppearanceState, action: AppearanceAction): AppearanceState = when (action) {
    is AppearanceAction.SelectTheme -> state.copy(theme = action.theme)
}

/** Apply the generated web text-scale policy once at the Android theme boundary. */
fun effectiveFontScale(systemFontScale: Float): Float {
    require(systemFontScale.isFinite() && systemFontScale > 0f)
    return min(
        (systemFontScale + GeneratedTextScale.offset) / GeneratedTextScale.divisor,
        GeneratedTextScale.maximum,
    )
}
