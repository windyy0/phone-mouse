package com.windy.phonemouse.app

/** Remove only exact application labels, preserving meaningful document text. */
internal fun sourceWindowTitle(source: String, windowTitle: String?): String? {
    val name = source.trim()
    val app = if (name.endsWith(".exe", ignoreCase = true)) name.dropLast(4) else name
    var title = windowTitle?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (app.isEmpty()) return title
    val labels = listOf(app, "$app.exe")
    fun sameAppLabel(value: String) = value.filter { it.isLetterOrDigit() }.lowercase() ==
        app.filter { it.isLetterOrDigit() }.lowercase()
    val separators = listOf(" - ", " – ", " — ", " | ", " · ")
    for (label in labels) {
        if (title.equals(label, ignoreCase = true) || sameAppLabel(title)) return null
        for (separator in separators) {
            val prefix = label + separator
            val suffix = separator + label
            if (title.startsWith(prefix, ignoreCase = true)) title = title.substring(prefix.length).trim()
            if (title.endsWith(suffix, ignoreCase = true)) title = title.dropLast(suffix.length).trim()
        }
    }
    for (separator in separators) {
        val prefix = title.substringBefore(separator)
        val suffix = title.substringAfterLast(separator)
        if (title.contains(separator) && sameAppLabel(prefix)) title = title.substringAfter(separator).trim()
        if (title.contains(separator) && sameAppLabel(suffix)) title = title.substringBeforeLast(separator).trim()
    }
    return title.takeIf { it.isNotEmpty() && !sameAppLabel(it) && labels.none { label -> it.equals(label, ignoreCase = true) } }
}
