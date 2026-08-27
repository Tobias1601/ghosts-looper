package com.diy.loopstation

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path

/**
 * Simple flat ghost glyph: rounded head, scalloped bottom edge, two dot eyes.
 * Deliberately minimal - single-color silhouette, no shading, no gradients.
 */
@Composable
fun GhostIcon(modifier: Modifier = Modifier, color: Color = AccentSignal) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val segW = w / 4f

        val path = Path().apply {
            moveTo(0f, h * 0.55f)
            cubicTo(0f, h * 0.05f, w, h * 0.05f, w, h * 0.55f)
            lineTo(w, h * 0.82f)
            quadraticBezierTo(w - segW * 0.5f, h, w - segW, h * 0.82f)
            quadraticBezierTo(w - segW * 1.5f, h, w - segW * 2f, h * 0.82f)
            quadraticBezierTo(w - segW * 2.5f, h, w - segW * 3f, h * 0.82f)
            quadraticBezierTo(w - segW * 3.5f, h, 0f, h * 0.82f)
            close()
        }
        drawPath(path, color = color)

        val eyeR = w * 0.055f
        drawCircle(color = BgBlack, radius = eyeR, center = Offset(w * 0.35f, h * 0.42f))
        drawCircle(color = BgBlack, radius = eyeR, center = Offset(w * 0.65f, h * 0.42f))
    }
}
