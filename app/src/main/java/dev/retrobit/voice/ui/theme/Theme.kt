package dev.retrobit.voice.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Tema "Retro Cyberdeck — Grayscale E-Paper".
 *
 * Prinsipnya seperti layar e-paper 1-bit:
 *  - hanya ada tinta (ink) dan kertas (paper) — tidak ada warna sama sekali;
 *  - status tidak dibedakan lewat warna, melainkan lewat **glyph** (✓ / ! / ▚ / [ON]),
 *    karena itu memang satu-satunya cara di layar monokrom;
 *  - semua sudut tajam (0–2dp), garis tepi 1px, dan tipografi monospace;
 *  - sedikit tekstur scanline agar terasa seperti panel perangkat, bukan kartu Material.
 *
 * Dua mode permukaan:
 *  - DECK  — panel gelap (bawaan), seperti layar instrumen malam;
 *  - PAPER — kertas terang, seperti e-reader di bawah matahari.
 */
object Deck {

    // ---------- Mode gelap (deck) ----------
    val paperDark = Color(0xFF0C0C0C)
    val panelDark = Color(0xFF141414)
    val panelAltDark = Color(0xFF1C1C1C)
    val inkDark = Color(0xFFE9E9E4)
    val dimDark = Color(0xFF8E8E88)
    val faintDark = Color(0xFF5C5C57)
    val lineDark = Color(0xFF2C2C2C)
    val lineStrongDark = Color(0xFF4C4C4C)

    // ---------- Mode terang (paper) ----------
    val paperLight = Color(0xFFE7E7E2)
    val panelLight = Color(0xFFF2F2EE)
    val panelAltLight = Color(0xFFDCDCD6)
    val inkLight = Color(0xFF111111)
    val dimLight = Color(0xFF5E5E58)
    val faintLight = Color(0xFF8E8E88)
    val lineLight = Color(0xFFBCBCB6)
    val lineStrongLight = Color(0xFF8A8A84)

    /** Karakter dekoratif khas terminal — dipakai bersama oleh seluruh komponen. */
    const val GLYPH_BLOCK = "▚"
    const val GLYPH_DOT_ON = "●"
    const val GLYPH_DOT_OFF = "○"
    const val GLYPH_BOX_ON = "■"
    const val GLYPH_BOX_OFF = "□"
    const val GLYPH_PROMPT_IN = ">"
    const val GLYPH_PROMPT_OUT = "<"
    const val RULE = "─"
}

/**
 * Warna semantik yang dipakai seluruh layar. Karena paletnya monokrom, perbedaan
 * "berhasil / gagal / sedang proses" dibawa oleh glyph dan inversi, bukan hue.
 */
object RetroColors {
    val accent = Color(0xFFE9E9E4)
    val listening = Color(0xFFE9E9E4)
    val processing = Color(0xFF9A9A94)
    val success = Color(0xFFE9E9E4)
    val dim = Color(0xFF8E8E88)
}

private val Mono = FontFamily.Monospace

private fun deckTypography() = Typography(
    headlineSmall = TextStyle(
        fontFamily = Mono,
        fontSize = 19.sp,
        letterSpacing = 2.sp,
        fontWeight = FontWeight.Normal,
    ),
    titleMedium = TextStyle(
        fontFamily = Mono,
        fontSize = 14.sp,
        letterSpacing = 1.6.sp,
        fontWeight = FontWeight.Bold,
    ),
    titleLarge = TextStyle(
        fontFamily = Mono,
        fontSize = 16.sp,
        letterSpacing = 2.sp,
        fontWeight = FontWeight.Bold,
    ),
    bodyMedium = TextStyle(
        fontFamily = Mono,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.4.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Mono,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Mono,
        fontSize = 10.sp,
        letterSpacing = 1.4.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontFamily = Mono,
        fontSize = 12.sp,
        letterSpacing = 1.2.sp,
    ),
)

/** Semua sudut tajam: e-paper tidak punya lengkungan. */
private val DeckShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(2.dp),
    large = RoundedCornerShape(3.dp),
    extraLarge = RoundedCornerShape(4.dp),
)

private val DeckScheme = darkColorScheme(
    primary = Deck.inkDark,
    onPrimary = Deck.paperDark,
    primaryContainer = Deck.panelAltDark,
    onPrimaryContainer = Deck.inkDark,
    secondary = Deck.dimDark,
    onSecondary = Deck.paperDark,
    tertiary = Deck.lineStrongDark,
    onTertiary = Deck.inkDark,
    background = Deck.paperDark,
    onBackground = Deck.inkDark,
    surface = Deck.panelDark,
    onSurface = Deck.inkDark,
    surfaceVariant = Deck.panelAltDark,
    onSurfaceVariant = Deck.dimDark,
    outline = Deck.lineDark,
    outlineVariant = Deck.lineStrongDark,
    // Monokrom: kesalahan dibedakan oleh tanda "!" dan teksnya, bukan warna merah.
    error = Deck.inkDark,
    onError = Deck.paperDark,
    errorContainer = Deck.panelAltDark,
    onErrorContainer = Deck.inkDark,
)

/** Mode "paper": kertas terang dengan tinta gelap — e-paper sesungguhnya. */
private val PaperScheme = lightColorScheme(
    primary = Deck.inkLight,
    onPrimary = Deck.paperLight,
    primaryContainer = Deck.panelAltLight,
    onPrimaryContainer = Deck.inkLight,
    secondary = Deck.dimLight,
    onSecondary = Deck.panelLight,
    tertiary = Deck.lineStrongLight,
    onTertiary = Deck.inkLight,
    background = Deck.paperLight,
    onBackground = Deck.inkLight,
    surface = Deck.panelLight,
    onSurface = Deck.inkLight,
    surfaceVariant = Deck.panelAltLight,
    onSurfaceVariant = Deck.dimLight,
    outline = Deck.lineLight,
    outlineVariant = Deck.lineStrongLight,
    error = Deck.inkLight,
    onError = Deck.panelLight,
    errorContainer = Deck.panelAltLight,
    onErrorContainer = Deck.inkLight,
)

/**
 * @param paperMode true = kertas terang, false = panel gelap (bawaan).
 */
@Composable
fun RetrobitTheme(
    paperMode: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = if (paperMode) PaperScheme else DeckScheme
    val activity = LocalContext.current as? Activity
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = paperMode
        }
    }
    MaterialTheme(colorScheme = scheme, typography = deckTypography(), shapes = DeckShapes, content = content)
}
