package dev.retrobit.voice.dictation.insert

/** Pure text-editing logic shared by direct accessibility insertion and JVM tests. */
object TextInsertionPlan {

    data class Result(val text: String, val cursor: Int)

    /** Replace the active selection, or append at the end if selection is unavailable. */
    fun insert(existing: String, selectionStart: Int, selectionEnd: Int, insertion: String): Result {
        val hasSelection = selectionStart in 0..existing.length && selectionEnd in 0..existing.length
        val start = if (hasSelection) minOf(selectionStart, selectionEnd) else existing.length
        val end = if (hasSelection) maxOf(selectionStart, selectionEnd) else existing.length
        val text = buildString(existing.length + insertion.length) {
            append(existing, 0, start)
            append(insertion)
            append(existing, end, existing.length)
        }
        return Result(text = text, cursor = start + insertion.length)
    }
}
