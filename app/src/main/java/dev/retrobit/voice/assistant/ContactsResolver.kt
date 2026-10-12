package dev.retrobit.voice.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import dev.retrobit.voice.core.VoiceLog

/**
 * Mencari kontak berdasarkan nama yang diucapkan.
 * Membutuhkan izin READ_CONTACTS; kalau belum ada, pemanggil diminta meminta izin.
 */
class ContactsResolver(private val context: Context) {

    data class Contact(val displayName: String, val number: String)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun search(query: String): List<Contact> {
        if (!hasPermission()) return emptyList()
        val needle = query.lowercase().trim()
        if (needle.isBlank()) return emptyList()
        val results = mutableListOf<Contact>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val number = cursor.getString(numIdx) ?: continue
                    if (name.lowercase().contains(needle)) {
                        results += Contact(name, number.replace(Regex("[^0-9+]"), ""))
                        if (results.size >= 20) break
                    }
                }
            }
        } catch (t: Throwable) {
            VoiceLog.e("Query kontak gagal", t)
        }
        return results
    }

    /** Ambil kandidat terbaik untuk sebuah nama. */
    fun best(query: String): Contact? {
        val candidates = search(query)
        if (candidates.isEmpty()) return null
        val needle = query.lowercase().trim()
        return candidates.firstOrNull { it.displayName.lowercase() == needle }
            ?: candidates.firstOrNull { it.displayName.lowercase().startsWith(needle) }
            ?: candidates.first()
    }
}
