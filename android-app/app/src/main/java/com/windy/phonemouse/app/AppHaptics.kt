package com.windy.phonemouse.app

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

internal class AppHaptics(context: Context) {
    private val appContext = context.applicationContext
    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    val available = vibrator?.hasVibrator() == true
    private val supportsClick = Build.VERSION.SDK_INT >= 30 &&
        vibrator?.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK) == true
    private val supportsTick = Build.VERSION.SDK_INT >= 30 &&
        vibrator?.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK) == true
    val adjustsAmplitude = supportsClick || vibrator?.hasAmplitudeControl() == true
    private val scrollGate = ScrollHapticGate()

    fun playScroll(settings: HapticSettings, delta: Double) {
        if (!available || !settings.enabled || settings.scroll == HapticPattern.Off) {
            scrollGate.resetProgress()
            return
        }
        if (scrollGate.shouldPlayScroll(delta, SystemClock.uptimeMillis())) play(settings, HapticAction.Scroll)
    }

    fun resetScrollProgress() { scrollGate.resetProgress() }

    fun play(settings: HapticSettings, action: HapticAction) {
        val motor = vibrator ?: return
        if (!available) return
        val sequence = hapticSequence(settings, action, motor.hasAmplitudeControl()) ?: return
        val now = SystemClock.uptimeMillis()
        val effect = if (supportsClick && Build.VERSION.SDK_INT >= 30) {
            val pattern = settings.pattern(action)
            val primitive = if (pattern == HapticPattern.Light && supportsTick)
                VibrationEffect.Composition.PRIMITIVE_TICK else VibrationEffect.Composition.PRIMITIVE_CLICK
            VibrationEffect.startComposition().apply {
                val scale = pattern.scale * settings.strengthScale
                addPrimitive(primitive, scale)
                if (pattern == HapticPattern.Double) addPrimitive(primitive, scale, 40)
            }.compose()
        } else {
            VibrationEffect.createWaveform(sequence.timings, sequence.amplitudes, -1)
        }
        // A phone's motor or service may reject an effect; input must still work.
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                motor.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                @Suppress("DEPRECATION")
                motor.vibrate(effect, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            }
            // Clicks, hold confirmations and previews take priority over subsequent wheel ticks.
            scrollGate.record(now, sequence.timings.sum())
        }
    }

    fun cancel() { vibrator?.cancel(); scrollGate.reset() }
}
