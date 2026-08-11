package co.logos.noderemote

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Logos dark palette, taken from the design system rather than eyeballed:
 *   logos-design-system/src/qml/Logos/Theme/{DarkTheme,ColorPalette}.qml
 *
 * The token names below are the design-system names, so a value can be traced back to
 * its source instead of becoming an orphaned hex code.
 */
object LogosColors {
    val gray900 = Color(0xFF171717)   // background
    val gray875 = Color(0xFF1C1C1C)   // backgroundTertiary
    val gray850 = Color(0xFF262626)   // backgroundSecondary
    val gray360 = Color(0xFF232323)   // surfaceRaised
    val gray320 = Color(0xFF343434)   // surface
    val gray300 = Color(0xFF434343)   // border
    val gray400 = Color(0xFFA4A4A4)   // textSecondary
    val white   = Color(0xFFFFFFFF)   // text

    val orange300 = Color(0xFFED7B58) // primary
    val orange500 = Color(0xFFF55702) // primaryHover
    val green500  = Color(0xFF49F563) // success
    val red500    = Color(0xFFFB3748) // error
    val yellow500 = Color(0xFFFFA726) // warning
    val blue400   = Color(0xFF4A90E2) // info — the module's blend colour
}

private val Scheme = darkColorScheme(
    primary = LogosColors.orange300,
    onPrimary = LogosColors.gray900,
    primaryContainer = LogosColors.orange500,
    onPrimaryContainer = LogosColors.white,

    background = LogosColors.gray900,
    onBackground = LogosColors.white,

    surface = LogosColors.gray875,
    onSurface = LogosColors.white,
    surfaceVariant = LogosColors.gray360,
    onSurfaceVariant = LogosColors.gray400,

    // Material's "surfaceContainer" family is what Card/TabRow actually paint with;
    // leaving them at the M3 defaults produces the wrong grey next to gray900.
    surfaceContainer = LogosColors.gray850,
    surfaceContainerHigh = LogosColors.gray320,
    surfaceContainerHighest = LogosColors.gray320,
    surfaceContainerLow = LogosColors.gray875,
    surfaceContainerLowest = LogosColors.gray900,

    error = LogosColors.red500,
    onError = LogosColors.white,
    errorContainer = LogosColors.gray850,
    onErrorContainer = LogosColors.red500,

    outline = LogosColors.gray300,
    outlineVariant = LogosColors.gray320,
)

@Composable
fun NodeRemoteTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = Scheme, content = content)
