package com.windy.phonemouse.app

import kotlin.math.abs

internal enum class FloatingControl(val code: String) {
    Toolbar("toolbar"), Left("left"), Middle("middle"), Right("right"), Wheel("wheelPanel"), Keyboard("keyboardPanel"),
    Gyroscope("gyroPanel")
}

/** Uses the same normalized coordinates as the existing combined island. */
data class SplitControlLayout(
    val toolbar: IslandPosition = IslandPosition(),
    val left: IslandPosition = IslandPosition(0f, 1f),
    val middle: IslandPosition = IslandPosition(.5f, 1f),
    val right: IslandPosition = IslandPosition(1f, 1f),
    val wheel: IslandPosition = IslandPosition(1f, .5f),
    val keyboard: IslandPosition = IslandPosition(0f, .5f),
    val gyroscope: IslandPosition = IslandPosition(0f, .3f),
) {
    internal operator fun get(control: FloatingControl) = when (control) {
        FloatingControl.Toolbar -> toolbar; FloatingControl.Left -> left; FloatingControl.Middle -> middle
        FloatingControl.Right -> right; FloatingControl.Wheel -> wheel; FloatingControl.Keyboard -> keyboard
        FloatingControl.Gyroscope -> gyroscope
    }
    internal fun with(control: FloatingControl, position: IslandPosition) = when (control) {
        FloatingControl.Toolbar -> copy(toolbar = position); FloatingControl.Left -> copy(left = position)
        FloatingControl.Middle -> copy(middle = position); FloatingControl.Right -> copy(right = position)
        FloatingControl.Wheel -> copy(wheel = position)
        FloatingControl.Keyboard -> copy(keyboard = position)
        FloatingControl.Gyroscope -> copy(gyroscope = position)
    }
    fun sanitized() = copy(toolbar = toolbar.sanitized(), left = left.sanitized(), middle = middle.sanitized(),
        right = right.sanitized(), wheel = wheel.sanitized(), keyboard = keyboard.sanitized(), gyroscope = gyroscope.sanitized())
    internal fun encode() = FloatingControl.entries.flatMap { listOf(get(it).x, get(it).y) }.joinToString(",")
    companion object {
        internal fun defaults() = SplitControlLayout(
            toolbar = IslandPosition(1f, .72f),
            left = IslandPosition(0f, 1f),
            middle = IslandPosition(.5f, 1f),
            right = IslandPosition(1f, 1f),
            wheel = IslandPosition(1f, .5f),
            keyboard = IslandPosition(0f, .5f),
            gyroscope = IslandPosition(0f, .3f),
        )

        internal fun decode(value: String?): SplitControlLayout? {
            val numbers = value?.split(',')?.map { it.toFloatOrNull() ?: return null } ?: return null
            // Preserve all coordinates from five/six-control layouts, appending new controls at their defaults.
            if (numbers.size !in listOf(10, 12, 14) || numbers.any { !it.isFinite() }) return null
            return FloatingControl.entries.take(numbers.size / 2).fold(defaults()) { layout, control ->
                layout.with(control, IslandPosition(numbers[control.ordinal * 2], numbers[control.ordinal * 2 + 1]))
            }.sanitized()
        }
    }
}

internal data class FloatingSize(val width: Float, val height: Float)

/** Finds the closest free rectangle, including spaces made by adjacent edges. */
internal class SplitControlsGeometry(private val width: Float, private val height: Float,
    private val sizes: Map<FloatingControl, FloatingSize>, private val margin: Float,
    private val snap: Float, private val gap: Float) {
    fun geometry(control: FloatingControl): FloatingIslandGeometry {
        val size = sizes.getValue(control)
        return FloatingIslandGeometry(width, height, size.width, size.height, margin, snap)
    }
    fun overlaps(control: FloatingControl, point: IslandOffset, other: FloatingControl, otherPoint: IslandOffset): Boolean {
        val a = sizes.getValue(control); val b = sizes.getValue(other)
        return point.x < otherPoint.x + b.width + gap - .01f && point.x + a.width + gap > otherPoint.x + .01f &&
            point.y < otherPoint.y + b.height + gap - .01f && point.y + a.height + gap > otherPoint.y + .01f
    }
    fun place(control: FloatingControl, desired: IslandOffset, occupied: Map<FloatingControl, IslandOffset>,
        fallback: IslandOffset? = null, snapToEdge: Boolean = true): IslandOffset {
        val geometry = geometry(control)
        val snapped = geometry.offset(geometry.position(desired, snap = snapToEdge))
        val size = sizes.getValue(control)
        val xs = mutableListOf(snapped.x, geometry.clamp(IslandOffset(0f, 0f)).x, geometry.clamp(IslandOffset(width, height)).x)
        val ys = mutableListOf(snapped.y, geometry.clamp(IslandOffset(0f, 0f)).y, geometry.clamp(IslandOffset(width, height)).y)
        occupied.forEach { (id, point) ->
            val other = sizes.getValue(id)
            xs += point.x - size.width - gap; xs += point.x + other.width + gap
            ys += point.y - size.height - gap; ys += point.y + other.height + gap
        }
        return xs.flatMap { x -> ys.map { y -> geometry.clamp(IslandOffset(x, y)) } }.distinct()
            .filter { candidate -> occupied.none { (id, point) -> overlaps(control, candidate, id, point) } }
            .minWithOrNull(compareBy<IslandOffset> { (it.x - snapped.x) * (it.x - snapped.x) + (it.y - snapped.y) * (it.y - snapped.y) }
                .thenBy { abs(it.y - snapped.y) }.thenBy { it.y }.thenBy { it.x })
            ?: geometry.clamp(fallback ?: desired)
    }
    fun resolve(layout: SplitControlLayout): Map<FloatingControl, IslandOffset> {
        val placed = linkedMapOf<FloatingControl, IslandOffset>()
        FloatingControl.entries.forEach { id -> placed[id] = place(id, geometry(id).offset(layout[id]), placed, snapToEdge = false) }
        return placed
    }
    fun layout(offsets: Map<FloatingControl, IslandOffset>): SplitControlLayout =
        FloatingControl.entries.fold(SplitControlLayout()) { layout, id -> layout.with(id, geometry(id).position(offsets.getValue(id), snap = false)) }
}
