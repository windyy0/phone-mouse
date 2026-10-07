package com.windy.phonemouse.app

import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.sign

enum class HapticPattern(val code: String, val label: String, val pulseMs: Long, val scale: Float) {
    Off("off", "关闭", 0L, 0f),
    Light("light", "轻触", 15L, .45f),
    Double("double", "双震", 20L, .65f);

    companion object {
        fun fromCode(code: String?, fallback: HapticPattern) = when (code) {
            "click", "confirm" -> Light
            else -> entries.firstOrNull { it.code == code } ?: fallback
        }
    }
}

enum class HapticStrength(val code: String, val label: String, val scale: Float, val durationScale: Float) {
    Gentle("gentle", "轻", .5f, .6f),
    Normal("normal", "适中", .75f, 1f),
    Strong("strong", "明显", 1f, 1.35f),
    Custom("custom", "自定义", .75f, 1f);

    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code } ?: Normal
    }
}

internal enum class HapticAction { LeftClick, RightClick, Scroll, ScrollHold, Keyboard, Backspace, StrengthPreview }

data class HapticSettings(
    val enabled: Boolean = true,
    val strength: HapticStrength = HapticStrength.Normal,
    val leftClick: HapticPattern = HapticPattern.Light,
    val rightClick: HapticPattern = HapticPattern.Double,
    val scrollHold: HapticPattern = HapticPattern.Light,
    val keyboard: HapticPattern = HapticPattern.Light,
    val backspace: HapticPattern = HapticPattern.Light,
    val customAmplitudePercent: Int = 75,
    val customPulseMs: Int = 15,
    val scroll: HapticPattern = HapticPattern.Light,
) {
    fun sanitized() = copy(
        customAmplitudePercent = customAmplitudePercent.coerceIn(1, 100),
        customPulseMs = customPulseMs.coerceIn(1, 80),
    )

    internal val strengthScale: Float
        get() = if (strength == HapticStrength.Custom) customAmplitudePercent.coerceIn(1, 100) / 100f else strength.scale

    internal fun pattern(action: HapticAction) = when (action) {
        HapticAction.LeftClick -> leftClick
        HapticAction.RightClick -> rightClick
        HapticAction.Scroll -> scroll
        HapticAction.ScrollHold -> scrollHold
        HapticAction.Keyboard -> keyboard
        HapticAction.Backspace -> backspace
        HapticAction.StrengthPreview -> HapticPattern.Light
    }
}

internal data class HapticSequence(val timings: LongArray, val amplitudes: IntArray)

/** One tick per wheel notch; fast crossings are coalesced without delayed feedback. */
internal class ScrollHapticGate {
    private var availableAt = 0L
    private var partialNotch = 0.0
    private var direction = 0.0

    fun shouldPlayScroll(delta: Double, nowMs: Long): Boolean {
        if (!delta.isFinite() || delta == 0.0) return false
        val nextDirection = sign(delta)
        if (direction != nextDirection) {
            partialNotch = 0.0
            direction = nextDirection
        }
        val distance = partialNotch + abs(delta)
        val crossed = distance >= 120.0
        // Consume every complete crossing even during cooldown; never queue a late tick.
        partialNotch = distance % 120.0
        return crossed && allows(nowMs)
    }

    fun allows(nowMs: Long) = nowMs >= availableAt

    fun record(nowMs: Long, durationMs: Long) {
        availableAt = nowMs + maxOf(160L, durationMs + 40L)
    }

    fun resetProgress() { partialNotch = 0.0; direction = 0.0 }

    fun reset() { availableAt = 0L; resetProgress() }
}

/** Short, finite fallback patterns for motors without vibration primitives. */
internal fun hapticSequence(
    settings: HapticSettings,
    action: HapticAction,
    amplitudeControl: Boolean,
): HapticSequence? {
    if (!settings.enabled) return null
    val pattern = settings.pattern(action)
    if (pattern == HapticPattern.Off) return null
    val duration = when {
        amplitudeControl -> pattern.pulseMs
        settings.strength == HapticStrength.Custom -> settings.customPulseMs.coerceIn(1, 80).toLong()
        else -> (pattern.pulseMs * settings.strength.durationScale).roundToInt().toLong().coerceAtLeast(1L)
    }
    val amplitude = if (amplitudeControl) {
        (255 * pattern.scale * settings.strengthScale).roundToInt().coerceIn(1, 255)
    } else {
        -1 // Android's default amplitude; pulse duration provides the distinction.
    }
    return if (pattern == HapticPattern.Double) {
        HapticSequence(longArrayOf(0L, duration, 40L, duration), intArrayOf(0, amplitude, 0, amplitude))
    } else {
        HapticSequence(longArrayOf(0L, duration), intArrayOf(0, amplitude))
    }
}
