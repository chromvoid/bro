package assistant.mobile.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import assistant.mobile.core.ThemePreference
import assistant.mobile.core.effectiveFontScale

@OptIn(ExperimentalTextApi::class)
private val InterFontFamily = FontFamily(
    Font(
        R.font.inter_variable,
        FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.inter_variable,
        FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        R.font.inter_variable,
        FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
    Font(
        R.font.inter_variable,
        FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

private val nativeTypography = Typography().let { defaults ->
    Typography(
        displayLarge = defaults.displayLarge.copy(fontFamily = InterFontFamily, letterSpacing = 0.sp),
        displayMedium = defaults.displayMedium.copy(fontFamily = InterFontFamily, letterSpacing = 0.sp),
        displaySmall = defaults.displaySmall.copy(fontFamily = InterFontFamily, letterSpacing = 0.sp),
        headlineLarge = defaults.headlineLarge.copy(fontFamily = InterFontFamily, letterSpacing = 0.sp),
        headlineMedium = defaults.headlineMedium.copy(fontFamily = InterFontFamily, letterSpacing = 0.sp),
        headlineSmall = defaults.headlineSmall.copy(
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            letterSpacing = 0.sp,
            fontFeatureSettings = "tnum",
        ),
        titleLarge = defaults.titleLarge.copy(
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            letterSpacing = 0.sp,
        ),
        titleMedium = defaults.titleMedium.copy(
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.sp,
        ),
        titleSmall = defaults.titleSmall.copy(fontFamily = InterFontFamily, letterSpacing = 0.sp),
        bodyLarge = defaults.bodyLarge.copy(
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.sp,
        ),
        bodyMedium = defaults.bodyMedium.copy(
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.sp,
        ),
        bodySmall = defaults.bodySmall.copy(
            fontFamily = InterFontFamily,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.sp,
        ),
        labelLarge = defaults.labelLarge.copy(
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.sp,
        ),
        labelMedium = defaults.labelMedium.copy(
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.sp,
        ),
        labelSmall = defaults.labelSmall.copy(
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.sp,
        ),
    )
}

private fun TextUnit.scaledSp(scale: Float): TextUnit = if (isSp) this * scale else this

private fun TextStyle.scaled(scale: Float): TextStyle = copy(
    fontSize = fontSize.scaledSp(scale),
    lineHeight = lineHeight.scaledSp(scale),
    letterSpacing = letterSpacing.scaledSp(scale),
)

/** Scale a fresh baseline once; Material3's constructor also initializes emphasized roles. */
private fun scaledTypography(scale: Float): Typography {
    val base = nativeTypography
    return Typography(
        displayLarge = base.displayLarge.scaled(scale),
        displayMedium = base.displayMedium.scaled(scale),
        displaySmall = base.displaySmall.scaled(scale),
        headlineLarge = base.headlineLarge.scaled(scale),
        headlineMedium = base.headlineMedium.scaled(scale),
        headlineSmall = base.headlineSmall.scaled(scale),
        titleLarge = base.titleLarge.scaled(scale),
        titleMedium = base.titleMedium.scaled(scale),
        titleSmall = base.titleSmall.scaled(scale),
        bodyLarge = base.bodyLarge.scaled(scale),
        bodyMedium = base.bodyMedium.scaled(scale),
        bodySmall = base.bodySmall.scaled(scale),
        labelLarge = base.labelLarge.scaled(scale),
        labelMedium = base.labelMedium.scaled(scale),
        labelSmall = base.labelSmall.scaled(scale),
    )
}

/** Matches the web tab bar's `bg-background`. */
val ColorScheme.navigationBarContainer: Color get() = background

fun nativeColorScheme(dark: Boolean): ColorScheme {
    val colors = if (dark) GeneratedColors.dark else GeneratedColors.light
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = colors.getValue("primary"),
        onPrimary = colors.getValue("primary-foreground"),
        primaryContainer = colors.getValue("accent"),
        onPrimaryContainer = colors.getValue("accent-foreground"),
        inversePrimary = colors.getValue("primary-foreground"),
        secondary = colors.getValue("primary"),
        onSecondary = colors.getValue("primary-foreground"),
        secondaryContainer = colors.getValue("accent"),
        onSecondaryContainer = colors.getValue("accent-foreground"),
        // Tertiary means success here; M3 only defaults to it for TimePicker's AM/PM, absent in Russian 24-hour time.
        tertiary = colors.getValue("success"),
        onTertiary = colors.getValue("background"),
        tertiaryContainer = colors.getValue("success-surface"),
        onTertiaryContainer = colors.getValue("success"),
        background = colors.getValue("background"),
        onBackground = colors.getValue("foreground"),
        surface = colors.getValue("card"),
        onSurface = colors.getValue("card-foreground"),
        surfaceVariant = colors.getValue("muted"),
        onSurfaceVariant = colors.getValue("muted-foreground"),
        surfaceTint = colors.getValue("primary"),
        inverseSurface = colors.getValue("foreground"),
        inverseOnSurface = colors.getValue("background"),
        error = colors.getValue("destructive"),
        onError = colors.getValue("destructive-foreground"),
        errorContainer = colors.getValue("destructive-surface"),
        onErrorContainer = colors.getValue("destructive"),
        outline = colors.getValue("input"),
        outlineVariant = colors.getValue("border"),
        scrim = colors.getValue("overlay-strong-background"),
        surfaceBright = colors.getValue("card"),
        surfaceDim = colors.getValue("muted"),
        surfaceContainer = colors.getValue("popover"),
        surfaceContainerHigh = colors.getValue("popover"),
        surfaceContainerHighest = colors.getValue("card"),
        // Sheets use card: dark accent is 0.050 OKLab away, versus only 0.020 from popover, keeping selected chips visible.
        surfaceContainerLow = colors.getValue("card"),
        surfaceContainerLowest = colors.getValue("background"),
        primaryFixed = GeneratedColors.light.getValue("accent"),
        primaryFixedDim = GeneratedColors.light.getValue("accent"),
        onPrimaryFixed = GeneratedColors.light.getValue("accent-foreground"),
        onPrimaryFixedVariant = GeneratedColors.light.getValue("accent-foreground"),
        secondaryFixed = GeneratedColors.light.getValue("accent"),
        secondaryFixedDim = GeneratedColors.light.getValue("accent"),
        onSecondaryFixed = GeneratedColors.light.getValue("accent-foreground"),
        onSecondaryFixedVariant = GeneratedColors.light.getValue("accent-foreground"),
        tertiaryFixed = GeneratedColors.light.getValue("success-surface"),
        tertiaryFixedDim = GeneratedColors.light.getValue("success-surface"),
        onTertiaryFixed = GeneratedColors.light.getValue("success"),
        onTertiaryFixedVariant = GeneratedColors.light.getValue("success"),
    )
}

@Composable
fun NativeTheme(
    mode: ThemePreference,
    onThemeApplied: (dark: Boolean, colors: ColorScheme) -> Unit = { _, _ -> },
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemePreference.System -> isSystemInDarkTheme()
        ThemePreference.Light -> false
        ThemePreference.Dark -> true
    }
    val scheme = remember(dark) { nativeColorScheme(dark) }
    val density = LocalDensity.current.density
    // Read the platform configuration, never an already adapted LocalDensity.fontScale.
    val systemFontScale = LocalConfiguration.current.fontScale
    val typography = remember(systemFontScale) {
        scaledTypography(effectiveFontScale(systemFontScale))
    }
    // NodeCoordinator and text measurement copy density/fontScale scalars, discarding custom
    // conversion overrides. A neutral fontScale also survives that copy without nonlinear
    // Android SP scaling. All font growth is already present once in the theme's typography.
    val fontDensity = remember(density) { Density(density, fontScale = 1f) }
    SideEffect { onThemeApplied(dark, scheme) }
    CompositionLocalProvider(LocalDensity provides fontDensity) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
