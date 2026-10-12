package dev.retrobit.voice.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Tema "retro-neon": gelap, ungu elektrik, aksen merah muda — sengaja berbeda
 * dari asisten bawaan supaya pengguna tahu ini Retrobit, bukan Google.
 */
private val NeonPurple = Color(0xFF7C5CFF)
private val NeonPink = Color(0xFFFF4D6D)
private val NeonAmber = Color(0xFFFFB703)
private val NeonGreen = Color(0xFF2ECC71)

object RetroColors {
    val listening = NeonPink
    val processing = NeonAmber
    val success = NeonGreen
    val accent = NeonPurple
}

private val DarkScheme = darkColorScheme(
    primary = NeonPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A2350),
    onPrimaryContainer = Color(0xFFE4DEFF),
    secondary = NeonPink,
    onSecondary = Color.White,
    tertiary = NeonAmber,
    background = Color(0xFF0E0E12),
    onBackground = Color(0xFFEDEDF2),
    surface = Color(0xFF16161C),
    onSurface = Color(0xFFEDEDF2),
    surfaceVariant = Color(0xFF23232C),
    onSurfaceVariant = Color(0xFFC9C9D4),
    error = Color(0xFFFF6B6B),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF5A3AE0),
    secondary = NeonPink,
    tertiary = Color(0xFFB07800),
    background = Color(0xFFFAF9FF),
    surface = Color.White,
    surfaceVariant = Color(0xFFEFECFA),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 15.sp),
    labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun RetrobitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme

    val view = LocalContext.current
    SideEffect {
        (view as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = AppTypography,
        content = content,
    )
}
