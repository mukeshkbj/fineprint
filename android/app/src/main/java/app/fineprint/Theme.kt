package app.fineprint

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp

@Immutable
data class Palette(
    val paper: Color,
    val surface: Color,
    val sunken: Color,
    val ink: Color,
    val muted: Color,
    val rule: Color,
    val pressed: Color,
    val warn: Color,
    val dark: Boolean,
) {
    val accent = Color(0xFF047857)
    val focus = Color(0xFF059669)
    val mark = Color(0xFFF5C400)
    val onAccent = Color.White

    fun severity(severity: Severity) = when (severity) {
        Severity.High -> Grade.E.color
        Severity.Medium -> Grade.D.color
        Severity.Low -> muted.copy(alpha = 0.55f)
    }
}

private val light = Palette(
    paper = Color(0xFFFAFAF8),
    surface = Color(0xFFFFFFFF),
    sunken = Color(0xFFF0EFEB),
    ink = Color(0xFF1C1D21),
    muted = Color(0xFF62636A),
    rule = Color(0xFFE3E2DD),
    pressed = Color(0x121C1D21),
    warn = Color(0xFFB45309),
    dark = false,
)

private val dark = Palette(
    paper = Color(0xFF17181B),
    surface = Color(0xFF202125),
    sunken = Color(0xFF111215),
    ink = Color(0xFFEDEDEA),
    muted = Color(0xFFA4A5AC),
    rule = Color(0xFF303136),
    pressed = Color(0x16FFFFFF),
    warn = Color(0xFFF59E0B),
    dark = true,
)

val Grade.color get() = Color(on)
val Grade.onColor get() = Color(ink)

val LocalPalette = staticCompositionLocalOf { light }
val LocalReduceMotion = staticCompositionLocalOf { false }

object Type {
    val tabular = "tnum"
    val title = TextStyle(fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
    val headline = TextStyle(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
    val plain = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val small = TextStyle(fontSize = 13.sp, lineHeight = 19.sp)
    val label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
    val quote = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, fontFamily = FontFamily.Serif)
    val stat = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = tabular)
}

object Motion {
    fun <T> small(): FiniteAnimationSpec<T> = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow * 2.4f)
    fun <T> large(): FiniteAnimationSpec<T> = spring(0.82f, 520f)
    fun <T> exit(): FiniteAnimationSpec<T> = spring(Spring.DampingRatioNoBouncy, 900f)
    fun <T> bouncy(): FiniteAnimationSpec<T> = spring(0.62f, 480f)
    val placement = spring(0.9f, 500f, IntOffset(1, 1))
}

fun reduceMotion(context: Context) = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

@Composable
fun FineprintTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) dark else light
    val reduce = reduceMotion(LocalContext.current)
    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalReduceMotion provides reduce,
        LocalTextSelectionColors provides TextSelectionColors(palette.focus, palette.focus.copy(alpha = 0.3f)),
        content = content,
    )
}
