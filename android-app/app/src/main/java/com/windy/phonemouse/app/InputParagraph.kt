package com.windy.phonemouse.app

/** Include one paragraph before and after the selection; wrapping does not split paragraphs. */
internal data class InputParagraph(val prefix: String, val text: String, val suffix: String) {
    val offset get() = prefix.length
    fun replace(value: String) = prefix + value + suffix
    fun retained(document: String): InputParagraph? = if (document.length >= prefix.length + suffix.length &&
        document.startsWith(prefix) && document.endsWith(suffix)) {
        copy(text = document.substring(prefix.length, document.length - suffix.length))
    } else null

    companion object {
        // The caret is bottom-aligned. Rows after it must not create empty
        // space above it; paragraph context uses the same centering as reveal().
        fun displayRows(text: String, measuredRows: Int, caretLine: Int,
            maximumRows: Int = Int.MAX_VALUE, showContext: Boolean = false): Int {
            if (text.isBlank()) return 1
            val total = measuredRows.coerceAtLeast(1)
            val line = caretLine.coerceIn(0, total - 1)
            var rows = minOf(total, maximumRows.coerceAtLeast(1))
            while (rows > line + 1 + if (showContext) minOf(rows / 2, total - 1 - line) else 0) rows--
            return rows
        }

        fun isLong(text: String) = text.length > 500 || text.count { it == '\n' } >= 5
        fun select(text: String, selectionStart: Int, selectionEnd: Int): InputParagraph {
            if (!isLong(text)) return InputParagraph("", text, "")
            val first = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
            val last = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
            val currentFrom = if (first == 0) 0 else text.lastIndexOf('\n', first - 1) + 1
            val currentTo = text.indexOf('\n', last).let { if (it < 0) text.length else it }
            val from = if (currentFrom == 0) 0 else text.lastIndexOf('\n', currentFrom - 2) + 1
            val to = if (currentTo == text.length) text.length else
                text.indexOf('\n', currentTo + 1).let { if (it < 0) text.length else it }
            return InputParagraph(text.substring(0, from), text.substring(from, to), text.substring(to))
        }
    }
}
