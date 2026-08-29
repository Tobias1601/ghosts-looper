package com.diy.loopstation

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val SpaceMono = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold)
)

// Static palette
val BgBlack = Color(0xFF0A0A0A)
val SurfaceDark = Color(0xFF151515)
val OutlineGrey = Color(0xFF2C2C2C)
val TextPrimary = Color(0xFFECECEC)
val TextSecondary = Color(0xFF848484)
val ConfirmGreen = Color(0xFF2CFF6B)

// Dynamic accent - reads UiSettings.accentHue, so any Composable or Canvas draw
// scope that references AccentSignal/AccentDim recomposes/redraws live when the
// hue slider moves. No separate "apply" step needed.
val AccentSignal: Color
    get() = Color(android.graphics.Color.HSVToColor(floatArrayOf(UiSettings.accentHue, 0.82f, 1f)))

val AccentDim: Color
    get() = Color(android.graphics.Color.HSVToColor(floatArrayOf(UiSettings.accentHue, 0.82f, 0.32f)))

/** Complementary hue (180° opposite the accent) - used to signal "actively recording
 *  a fresh track" so the indicator color always contrasts with whatever accent the
 *  user has picked, instead of being hardcoded red. */
val ComplementarySignal: Color
    get() = Color(android.graphics.Color.HSVToColor(floatArrayOf((UiSettings.accentHue + 180f) % 360f, 0.82f, 1f)))

/** A different contrast (120° from the accent) for "overdubbing onto an existing
 *  track", so it's visually distinct from a fresh-length recording. */
val OverdubSignal: Color
    get() = Color(android.graphics.Color.HSVToColor(floatArrayOf((UiSettings.accentHue + 120f) % 360f, 0.75f, 1f)))

val LooperShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(3.dp),
    large = RoundedCornerShape(4.dp)
)

val LooperTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = SpaceMono, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, letterSpacing = 1.sp
    ),
    titleMedium = TextStyle(
        fontFamily = SpaceMono, fontWeight = FontWeight.Bold,
        fontSize = 15.sp, letterSpacing = 1.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = SpaceMono, fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),
    bodySmall = TextStyle(
        fontFamily = SpaceMono, fontWeight = FontWeight.Normal,
        fontSize = 11.sp, letterSpacing = 0.5.sp
    ),
    labelLarge = TextStyle(
        fontFamily = SpaceMono, fontWeight = FontWeight.Bold,
        fontSize = 13.sp, letterSpacing = 1.5.sp
    )
)

@Composable
fun LooperTheme(content: @Composable () -> Unit) {
    // Reading AccentSignal here (which reads UiSettings.accentHue) means this whole
    // composable recomposes - and hands MaterialTheme a fresh colorScheme - the
    // instant the hue slider moves.
    val scheme = darkColorScheme(
        background = BgBlack,
        surface = SurfaceDark,
        primary = AccentSignal,
        onPrimary = Color.Black,
        secondary = ConfirmGreen,
        onSecondary = Color.Black,
        onBackground = TextPrimary,
        onSurface = TextPrimary,
        outline = OutlineGrey,
        error = AccentSignal
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = LooperTypography,
        shapes = LooperShapes,
        content = content
    )
}
