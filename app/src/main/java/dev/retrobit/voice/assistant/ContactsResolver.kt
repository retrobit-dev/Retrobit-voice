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

    sealed interface Lookup {
        data class Found(val contact: Contact) : Lookup
        data class Ambiguous(val names: List<String>) : Lookup
        data object NotFound : Lookup
        data object PermissionRequired : Lookup
    }

    /** Resolusi unik; jangan menelepon/membuka chat ke salah satu hasil secara acak. */
    fun lookup(query: String): Lookup {
        if (!hasPermission()) return Lookup.PermissionRequired
        val candidates = search(query).distinctBy { it.displayName.lowercase() to it.number }
        if (candidates.isEmpty()) return Lookup.NotFound
        val needle = query.lowercase().trim()
        val exact = candidates.filter { it.displayName.lowercase() == needle }
        val prefix = candidates.filter { it.displayName.lowercase().startsWith(needle) }
        val selected = when {
            exact.isNotEmpty() -> exact
            prefix.isNotEmpty() -> prefix
            else -> candidates
        }.distinctBy { it.number }
        return when (selected.size) {
            0 -> Lookup.NotFound
            1 -> Lookup.Found(selected.single())
            else -> Lookup.Ambiguous(selected.map { it.displayName }.distinct().take(5))
        }
    }

    /** Ambil kandidat terbaik hanya jika nama/nomor cukup unik. */
    fun best(query: String): Contact? = (lookup(query) as? Lookup.Found)?.contact
}
