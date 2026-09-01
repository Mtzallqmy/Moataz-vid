package com.moatazvid.editor.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object MoatazSpacing { val xs = 4.dp; val sm = 8.dp; val md = 16.dp; val lg = 24.dp }
object MoatazColors {
    val Accent = Color(0xFF7C5CFC); val Video = Color(0xFF3D6CE7); val Audio = Color(0xFF2C9C69)
    val Caption = Color(0xFFE39A36); val Overlay = Color(0xFFA45CC7); val PendingRemove = Color(0xFFD84C4C)
}

@Composable fun MoatazVidTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(primary = MoatazColors.Accent) else lightColorScheme(primary = MoatazColors.Accent)
    MaterialTheme(colorScheme = scheme, typography = Typography(), shapes = Shapes(), content = content)
}
