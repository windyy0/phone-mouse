package com.windy.phonemouse.app

/** X is horizontal travel; Y is the panel centre in the usable viewport. */
data class IslandPosition(val x: Float = 1f, val y: Float = .72f) {
    fun sanitized() = IslandPosition(
        if (x.isFinite()) x.coerceIn(0f, 1f) else 1f,
        if (y.isFinite()) y.coerceIn(0f, 1f) else .72f,
    )
}

internal data class IslandOffset(val x: Float, val y: Float)

internal class FloatingIslandGeometry(
    width: Float, height: Float, private val panelWidth: Float, private val panelHeight: Float,
    margin: Float, private val snapDistance: Float,
) {
    private val left = margin.coerceAtMost((width - panelWidth).coerceAtLeast(0f) / 2f)
    private val top = margin.coerceAtMost((height - panelHeight).coerceAtLeast(0f) / 2f)
    private val right = (width - left - panelWidth).coerceAtLeast(left)
    private val bottom = (height - top - panelHeight).coerceAtLeast(top)
    private val centreTravel = (height - top * 2f).coerceAtLeast(1f)

    fun offset(position: IslandPosition): IslandOffset {
        val p = position.sanitized()
        return clamp(IslandOffset(left + p.x * (right - left), top + p.y * centreTravel - panelHeight / 2f))
    }

    fun clamp(offset: IslandOffset) = IslandOffset(offset.x.coerceIn(left, right), offset.y.coerceIn(top, bottom))

    fun position(offset: IslandOffset, snap: Boolean = true): IslandPosition {
        val p = clamp(offset)
        val x = when {
            right == left -> 1f
            snap && p.x - left <= snapDistance -> 0f
            snap && right - p.x <= snapDistance -> 1f
            else -> (p.x - left) / (right - left)
        }
        return IslandPosition(x, (p.y + panelHeight / 2f - top) / centreTravel).sanitized()
    }
}
