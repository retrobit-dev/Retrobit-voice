package dev.retrobit.voice.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import dev.retrobit.voice.core.VoiceLog

/**
 * Mencari aplikasi terpasang berdasarkan nama yang diucapkan.
 * Memakai pencocokan bertingkat supaya "wa" tetap menemukan WhatsApp dan
 * "yt" menemukan YouTube.
 */
class AppResolver(private val context: Context) {

    data class AppTarget(val label: String, val packageName: String, val intent: Intent)

    private val aliases = mapOf(
        "wa" to "whatsapp", "wa business" to "whatsapp business", "ig" to "instagram",
        "fb" to "facebook", "yt" to "youtube", "gmail" to "gmail", "email" to "gmail",
        "gmaps" to "maps", "google maps" to "maps", "playstore" to "play store",
        "play" to "play store", "shopee" to "shopee", "tokped" to "tokopedia",
        "tokopedia" to "tokopedia", "gojek" to "gojek", "grab" to "grab",
        "kasir" to "kasir", "spotify" to "spotify", "netflix" to "netflix",
        "tiktok" to "tiktok", "telegram" to "telegram", "signal" to "signal",
        "kalkulator" to "calculator", "kamera" to "camera", "galeri" to "photos",
        "browser" to "chrome", "pengaturan" to "settings", "clock" to "clock",
        "jam" to "clock", "alarm" to "clock", "kalender" to "calendar",
        "catatan" to "notes", "musik" to "music", "pesan" to "messages",
        "sms" to "messages", "telepon" to "phone", "kontak" to "contacts",
    )

    fun installedApps(): List<String> = launchable().map { it.label }.distinct().sorted()

    private fun launchable(): List<AppTarget> = try {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = pm.queryIntentActivities(main, PackageManager.MATCH_ALL)
        resolved.map { info ->
            AppTarget(
                label = info.loadLabel(pm).toString(),
                packageName = info.activityInfo.packageName,
                intent = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(info.activityInfo.packageName, info.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            )
        }
    } catch (t: Throwable) {
        VoiceLog.e("Gagal membaca daftar aplikasi", t)
        emptyList()
    }

    /** Cari aplikasi paling cocok. Mengembalikan null kalau tidak ada yang mendekati. */
    fun resolve(query: String): AppTarget? {
        val wanted = normalize(aliases[normalize(query)] ?: query)
        if (wanted.isBlank()) return null
        val apps = launchable()
        if (apps.isEmpty()) return null

        var best: AppTarget? = null
        var bestScore = 0.0

        for (app in apps) {
            val label = normalize(app.label)
            val score = when {
                label == wanted -> 1.0
                label.startsWith(wanted) || wanted.startsWith(label) -> 0.9
                label.contains(wanted) -> 0.8
                wanted.length >= 4 && levenshteinRatio(label, wanted) > 0.75 -> 0.7
                else -> 0.0
            }
            if (score > bestScore) {
                bestScore = score
                best = app
            }
        }

        // Coba juga pencocokan kata per kata ("google maps" → "maps")
        if (bestScore < 0.8) {
            val words = wanted.split(' ')
            for (app in apps) {
                val label = normalize(app.label)
                for (w in words) {
                    if (w.length >= 4 && label == w) {
                        return app
                    }
                }
            }
        }

        return if (bestScore >= 0.7) best else null
    }

    private fun normalize(s: String): String = s.lowercase()
        .replace(Regex("[^a-z0-9 ]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun levenshteinRatio(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val rows = a.length + 1
        val cols = b.length + 1
        var prev = IntArray(cols) { it }
        var curr = IntArray(cols)
        for (i in 1 until rows) {
            curr[0] = i
            for (j in 1 until cols) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        val distance = prev[cols - 1]
        return 1.0 - distance.toDouble() / maxOf(a.length, b.length)
    }
}
