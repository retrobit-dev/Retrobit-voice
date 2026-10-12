package dev.retrobit.voice.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.retrobit.voice.R
import dev.retrobit.voice.dictation.DictationController
import dev.retrobit.voice.ui.theme.Deck
import dev.retrobit.voice.ui.theme.RetroColors

/** Pembungkus tipis supaya pemakaian string resource ringkas. */
@Composable
fun str(id: Int): String = stringResource(id)

/**
 * Tekstur garis halus seperti panel e-paper / CRT mati.
 * Hanya garis horizontal (murah untuk digambar, tidak memicu alokasi per frame).
 */
fun Modifier.epaperTexture(
    lineColor: Color = Color.White.copy(alpha = 0.05f),
    step: Dp = 3.dp,
): Modifier = this.drawBehind {
    val gap = step.toPx().coerceAtLeast(2f)
    var y = 0f
    while (y < size.height) {
        drawLine(color = lineColor, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 1f)
        y += gap
    }
}

/**
 * Panel utama bergaya instrumen:
 *
 *   ▚ DIKTE HYBRID                          [01]
 *   ────────────────────────────────────────────
 *   isi panel…
 */
@Composable
fun Panel(
    title: String,
    code: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    inactive: Boolean = false,
    content: @Composable () -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outline
    val ink = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, outline)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // Bilah judul: ditebalkan supaya terbaca seperti label perangkat, bukan judul kartu.
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${Deck.GLYPH_BLOCK} ${title.uppercase()}",
                style = MaterialTheme.typography.labelSmall,
                color = if (inactive) dim else ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (code != null) {
                Text(code, style = MaterialTheme.typography.labelSmall, color = dim)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(outline))
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = dim)
            }
            content()
        }
    }
}

/** Nama lama dipertahankan agar layar-layar yang sudah ada tetap bekerja. */
@Composable
fun SectionCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) = Panel(title = title, subtitle = subtitle, modifier = modifier, content = content)

/** Label berkurung khas terminal: `[ ON ]`, `[ !! MIC ]`. */
@Composable
fun StatusPill(text: String, color: Color = RetroColors.accent) {
    val ink = MaterialTheme.colorScheme.onSurface
    val tint = if (color == RetroColors.accent) ink else ink.copy(alpha = 0.85f)
    Box(
        Modifier
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = "[ ${text.uppercase()} ]",
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}

/** Baris status perangkat: `MIC ●  IME ○  A11Y ○`. */
@Composable
fun StatusStrip(items: List<Pair<String, Boolean>>, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { (label, ok) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (ok) Deck.GLYPH_DOT_ON else Deck.GLYPH_DOT_OFF,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Judul layar bergaya perintah: `> RETROBIT//VOICE` dengan kode versi di kanan. */
@Composable
fun TerminalHeader(title: String, right: String? = null, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${Deck.GLYPH_PROMPT_IN} $title",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            if (right != null) {
                Text(right, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Text(
            text = Deck.RULE.repeat(46),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Baris log percakapan: `> perintah` dan `< jawaban  #Aksi`. */
@Composable
fun TerminalLogLine(
    prompt: String,
    text: String,
    meta: String? = null,
    emphasis: Boolean = false,
) {
    val outline = MaterialTheme.colorScheme.outline
    val ink = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, outline)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row {
            Text(prompt, style = MaterialTheme.typography.bodyMedium, color = dim)
            Spacer(Modifier.width(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = ink,
                fontWeight = if (emphasis) FontWeight.Bold else FontWeight.Normal,
            )
        }
        if (meta != null) {
            Text(
                meta,
                style = MaterialTheme.typography.labelSmall,
                color = dim,
                modifier = Modifier.padding(start = 14.dp, top = 2.dp),
            )
        }
    }
}

/** Langkah aktivasi dengan kotak centang 1-bit. */
@Composable
fun SetupRow(
    title: String,
    subtitle: String,
    done: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = if (done) Deck.GLYPH_BOX_ON else Deck.GLYPH_BOX_OFF,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!done) {
            BracketButton(text = actionLabel, onClick = onAction)
        }
    }
}

/** Tombol teks bergaya terminal: `[ AKTIFKAN ]`. */
@Composable
fun BracketButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    filled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outline
    val alpha = if (enabled) 1f else 0.4f

    Box(
        modifier
            .border(1.dp, outline)
            .background(if (filled) ink.copy(alpha = alpha) else Color.Transparent)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "[ ${text.uppercase()} ]",
            style = MaterialTheme.typography.labelSmall,
            color = if (filled) paper.copy(alpha = alpha) else ink.copy(alpha = alpha),
            maxLines = 1,
        )
    }
}

@Composable
fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.onSurface,
                checkedBorderColor = MaterialTheme.colorScheme.onSurface,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = Color.Transparent,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

/** Meter tingkat suara bergaya LED 1-bit. */
@Composable
fun LevelMeter(
    level: Float,
    color: Color,
    segments: Int = 18,
    modifier: Modifier = Modifier,
) {
    val filledCount = (level.coerceIn(0f, 1f) * segments).toInt()
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(segments) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .height(9.dp)
                    .background(
                        if (index < filledCount) color else color.copy(alpha = 0.16f),
                    ),
            )
        }
    }
}

/**
 * Tombol mikrofon utama: seluruh panel menjadi tombol.
 * Saat merekam, panel di-inversi (tinta ↔ kertas) — cara e-paper menandakan "aktif".
 */
@Composable
fun BigMicButton(
    state: DictationController.UiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listening = state.phase == DictationController.Phase.LISTENING
    val busy = state.isBusy
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outline

    val background = if (listening) ink else paper
    val foreground = if (listening) paper.copy(alpha = 0.92f) else ink
    val code = when {
        listening -> "REC"
        busy -> "BUSY"
        state.phase == DictationController.Phase.DONE -> "DONE"
        state.phase == DictationController.Phase.ERROR -> "ERR"
        else -> "IDLE"
    }

    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, outline)
            .background(background)
            .clickable { onClick() }
            .padding(vertical = 18.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "[ $code ]",
            style = MaterialTheme.typography.labelSmall,
            color = foreground.copy(alpha = 0.75f),
        )
        Box(
            Modifier
                .size(92.dp)
                .border(1.dp, foreground.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(if (listening) R.drawable.ic_stop else R.drawable.ic_mic),
                contentDescription = if (listening) str(R.string.action_finish) else str(R.string.home_start_dictation),
                tint = foreground,
                modifier = Modifier.size(38.dp),
            )
        }
        LevelMeter(
            level = if (listening) state.amplitude else 0f,
            color = foreground,
        )
        Text(
            text = (state.elapsedMs / 1000.0).let { "T+%05.1fs".format(it) },
            style = MaterialTheme.typography.labelSmall,
            color = foreground.copy(alpha = 0.6f),
        )
    }
}

/**
 * Pemilih opsi dua kolom: sel terpilih di-inversi dan diberi penanda ▚.
 */
@Composable
fun <T> PillsColumn(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outline
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { (value, label) ->
                    val isSelected = value == selected
                    Box(
                        Modifier
                            .weight(1f)
                            .border(1.dp, outline)
                            .background(if (isSelected) ink else Color.Transparent)
                            .clickable { onSelect(value) }
                            .padding(horizontal = 9.dp, vertical = 9.dp),
                    ) {
                        Text(
                            text = (if (isSelected) "${Deck.GLYPH_BLOCK} " else "${Deck.GLYPH_BOX_OFF} ") + label.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) paper else dim,
                            maxLines = 2,
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun SettingTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label.uppercase(), style = MaterialTheme.typography.labelSmall) },
        placeholder = {
            if (placeholder.isNotBlank()) {
                Text(placeholder, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        textStyle = MaterialTheme.typography.bodySmall,
        singleLine = true,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.onSurface,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedLabelColor = MaterialTheme.colorScheme.onSurface,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            cursorColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Baris data: `MESIN ......... on-device`. */
@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value.ifBlank { "—" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun Divider8() = Spacer(Modifier.height(8.dp))

@Composable
fun HSpacer(w: Int) = Spacer(Modifier.width(w.dp))

/** Garis pemisah bergaya daftar: `────────────────`. */
@Composable
fun RuleLine(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
}

/** Kartu lama (BorderStroke) tetap tersedia agar layar lama tidak pecah. */
@Composable
fun OutlineBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline)), contentAlignment = Alignment.Center) {
        content()
    }
}
