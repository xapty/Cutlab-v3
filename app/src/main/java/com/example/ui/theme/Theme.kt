package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class StudioExtendedColors(
    val hairlineBorder: Color = HairlineBorder,
    val elevatedPanel: Color = ElevatedPanel,
    val highestPanel: Color = HighestPanel,
    val audioAmber: Color = AmberAudio,
    val audioAmberContainer: Color = AmberContainer,
    val readyEmerald: Color = EmeraldReady,
    val readyEmeraldContainer: Color = EmeraldContainer,
    val quickCyan: Color = ElectricCyan,
    val advancedViolet: Color = LuminousViolet,
    val inkMuted: Color = InkMuted,
)

val LocalStudioColors = staticCompositionLocalOf { StudioExtendedColors() }

private val CutLabDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = ObsidianCanvas,
    primaryContainer = CyanContainer,
    onPrimaryContainer = CyanOnContainer,
    secondary = LuminousViolet,
    onSecondary = InkPrimary,
    secondaryContainer = VioletContainer,
    onSecondaryContainer = VioletOnContainer,
    tertiary = AmberAudio,
    onTertiary = ObsidianCanvas,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = InkPrimary,
    background = ObsidianCanvas,
    onBackground = InkPrimary,
    surface = StudioSurface,
    onSurface = InkPrimary,
    surfaceVariant = ElevatedPanel,
    onSurfaceVariant = InkSecondary,
    surfaceContainerLowest = ObsidianCanvas,
    surfaceContainerLow = StudioSurface,
    surfaceContainer = ElevatedPanel,
    surfaceContainerHigh = HighestPanel,
    surfaceContainerHighest = HighestPanel,
    outline = HairlineBorder,
    outlineVariant = Color(0xFF1F2736),
    error = CoralError,
    onError = InkPrimary,
    errorContainer = CoralContainer,
    onErrorContainer = Color(0xFFFCA5A5),
)

val CutLabShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun CutLabTheme(
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalStudioColors provides StudioExtendedColors()) {
        MaterialTheme(
            colorScheme = CutLabDarkColorScheme,
            typography = CutLabTypography,
            shapes = CutLabShapes,
            content = content,
        )
    }
}
