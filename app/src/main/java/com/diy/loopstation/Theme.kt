package com.diy.loopstation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
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

// Palette: near-black ground, off-white text, one accent (signal orange/red)
val BgBlack = Color(0xFF0A0A0A)
val SurfaceDark = Color(0xFF151515)
val OutlineGrey = Color(0xFF2C2C2C)
val TextPrimary = Color(0xFFECECEC)
val TextSecondary = Color(0xFF848484)
val AccentSignal = Color(0xFFFF3D1A) // TE/Nothing-style signal red-orange
val AccentDim = Color(0xFF7A1E0F)
val ConfirmGreen = Color(0xFF2CFF6B)

val LooperColorScheme = darkColorScheme(
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
    MaterialTheme(
        colorScheme = LooperColorScheme,
        typography = LooperTypography,
        shapes = LooperShapes,
        content = content
    )
}
