package com.windy.phonemouse.app

internal data class FloatingInputBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** A display-only offset: always start with the saved position so dismissal restores it. */
internal fun avoidFloatingInput(
    original: IslandOffset, size: FloatingSize, input: FloatingInputBounds?, keyboardTop: Float?,
    viewportTop: Float, gap: Float,
): IslandOffset {
    if (input == null || keyboardTop == null) return original
    val overlapsInput = original.x < input.right && original.x + size.width > input.left &&
        original.y < input.bottom && original.y + size.height > input.top
    val overlapsKeyboard = original.y + size.height > keyboardTop
    if (!overlapsInput && !overlapsKeyboard) return original
    val aboveInput = (input.top - gap - size.height).coerceAtLeast(viewportTop)
    return original.copy(y = minOf(original.y, aboveInput))
}
