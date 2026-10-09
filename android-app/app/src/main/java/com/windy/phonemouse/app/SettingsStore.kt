package com.windy.phonemouse.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.controls by preferencesDataStore(name = "controls")

enum class FullscreenRotationMode(val code: String, val label: String) {
    FollowSystem("system", "跟随系统"), Manual("manual", "手动锁定");

    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code } ?: FollowSystem
    }
}

data class ControlSettings(
    val sensitivity: Float = 2.5f,
    val scrollSpeed: Float = 1f,
    val reverseScroll: Boolean = false,
    val tapToClick: Boolean = true,
    val keepAwake: Boolean = true,
    val address: String = "",
    val haptics: HapticSettings = HapticSettings(),
    val inputMode: TextInputMode = TextInputMode.Draft,
    val longPressDrag: Boolean = false,
    val doubleTapHoldDrag: Boolean = true,
    val reverseTwoFingerScroll: Boolean = true,
    val reportRate: MouseReportRate = MouseReportRate.Hz250,
    val autoConnectLast: Boolean = false,
    val lastComputerName: String = "",
    val themeMode: ThemeMode = ThemeMode.System,
    val fullscreenMode: Boolean = false,
    val fullscreenRotationMode: FullscreenRotationMode = FullscreenRotationMode.FollowSystem,
    val fullscreenRotation: Int = 0,
    val islandCollapsed: Boolean = false,
    val portraitIsland: IslandPosition = IslandPosition(),
    val landscapeIsland: IslandPosition = IslandPosition(),
    val controlsSplit: Boolean = false,
    val portraitControls: SplitControlLayout? = null,
    val landscapeControls: SplitControlLayout? = null,
    val portraitToolbarCollapsed: Boolean = false,
    val landscapeToolbarCollapsed: Boolean = false,
    val mouseControlMode: MouseControlMode = MouseControlMode.Touchpad,
    val gyro: GyroSettings = GyroSettings(),
    val touchpadAcceleration: PointerAccelerationSettings = PointerAccelerationSettings(),
    val fullscreenHelpEnabled: Boolean = true,
    val languageMode: LanguageMode = LanguageMode.System,
    val updateCheckFrequency: UpdateCheckFrequency = UpdateCheckFrequency.Daily,
) {
    internal fun scrollDelta(delta: Double, twoFinger: Boolean = false): Double {
        val reversed = if (twoFinger) reverseTwoFingerScroll else reverseScroll
        return delta * scrollSpeed * if (reversed) -1 else 1
    }

    fun sanitized() = copy(
        sensitivity = if (sensitivity.isFinite()) sensitivity.coerceIn(.1f, 10f) else 2.5f,
        scrollSpeed = if (scrollSpeed.isFinite()) scrollSpeed.coerceIn(.3f, 3f) else 1f,
        fullscreenRotation = when (fullscreenRotation) { 0, 90, 180, 270 -> fullscreenRotation else -> 0 },
        portraitIsland = portraitIsland.sanitized(),
        landscapeIsland = landscapeIsland.sanitized(),
        portraitControls = portraitControls?.sanitized(),
        landscapeControls = landscapeControls?.sanitized(),
        gyro = gyro.sanitized(),
        touchpadAcceleration = touchpadAcceleration.sanitized(),
        haptics = haptics.sanitized(),
    )
}

class SettingsStore internal constructor(private val preferences: DataStore<Preferences>) {
    constructor(context: Context) : this(context.controls)

    private val sensitivity = floatPreferencesKey("sensitivity")
    private val scrollSpeed = floatPreferencesKey("scroll_speed")
    private val reverse = booleanPreferencesKey("reverse_scroll")
    private val tap = booleanPreferencesKey("tap_to_click")
    private val awake = booleanPreferencesKey("keep_awake")
    private val address = stringPreferencesKey("address")
    private val hapticEnabled = booleanPreferencesKey("haptic_enabled")
    private val hapticStrength = stringPreferencesKey("haptic_strength")
    private val hapticCustomAmplitudePercent = intPreferencesKey("haptic_custom_amplitude_percent")
    private val hapticCustomPulseMs = intPreferencesKey("haptic_custom_pulse_ms")
    private val hapticLeft = stringPreferencesKey("haptic_left")
    private val hapticRight = stringPreferencesKey("haptic_right")
    private val hapticScroll = stringPreferencesKey("haptic_scroll")
    private val hapticScrollHold = stringPreferencesKey("haptic_scroll_hold")
    private val hapticKeyboard = stringPreferencesKey("haptic_keyboard")
    private val hapticBackspace = stringPreferencesKey("haptic_backspace")
    private val inputMode = stringPreferencesKey("input_mode")
    private val longPressDrag = booleanPreferencesKey("long_press_drag")
    private val doubleTapHoldDrag = booleanPreferencesKey("double_tap_hold_drag")
    private val reverseTwoFingerScroll = booleanPreferencesKey("reverse_two_finger_scroll")
    private val reportRate = intPreferencesKey("mouse_report_rate_hz")
    private val autoConnectLast = booleanPreferencesKey("auto_connect_last")
    private val lastComputerName = stringPreferencesKey("last_computer_name")
    private val themeMode = stringPreferencesKey("theme_mode")
    private val fullscreenMode = booleanPreferencesKey("fullscreen_mode")
    private val fullscreenRotationMode = stringPreferencesKey("fullscreen_rotation_mode")
    private val fullscreenRotation = intPreferencesKey("fullscreen_rotation")
    private val fullscreenHelpEnabled = booleanPreferencesKey("fullscreen_help_enabled")
    private val languageMode = stringPreferencesKey("language_mode")
    private val updateCheckFrequency = stringPreferencesKey("update_check_frequency")
    private val updateLastCheckAt = longPreferencesKey("update_last_check_at")
    private val islandCollapsed = booleanPreferencesKey("island_collapsed")
    private val portraitX = floatPreferencesKey("island_portrait_x")
    private val portraitY = floatPreferencesKey("island_portrait_y")
    private val landscapeX = floatPreferencesKey("island_landscape_x")
    private val landscapeY = floatPreferencesKey("island_landscape_y")
    private val controlsSplit = booleanPreferencesKey("controls_split")
    private val portraitControls = stringPreferencesKey("controls_portrait_positions")
    private val landscapeControls = stringPreferencesKey("controls_landscape_positions")
    private val portraitToolbarCollapsed = booleanPreferencesKey("toolbar_portrait_collapsed")
    private val landscapeToolbarCollapsed = booleanPreferencesKey("toolbar_landscape_collapsed")
    private val mouseControlMode = stringPreferencesKey("mouse_control_mode")
    private val gyroSensitivity = floatPreferencesKey("gyro_sensitivity")
    private val gyroDirectionMode = stringPreferencesKey("gyro_direction_mode")
    private val gyroSmoothing = booleanPreferencesKey("gyro_smoothing")
    private val gyroReverseX = booleanPreferencesKey("gyro_reverse_x")
    private val gyroReverseY = booleanPreferencesKey("gyro_reverse_y")
    private val touchpadAccelerationMode = stringPreferencesKey("touchpad_acceleration_mode")
    private val touchpadAccelerationStrength = stringPreferencesKey("touchpad_acceleration_strength")
    private val gyroAccelerationMode = stringPreferencesKey("gyro_acceleration_mode")
    private val gyroAccelerationStrength = stringPreferencesKey("gyro_acceleration_strength")
    private val touchpadCustomSpeed = stringPreferencesKey("touchpad_acceleration_custom_speed")
    private val touchpadCustomDistance = stringPreferencesKey("touchpad_acceleration_custom_distance")
    private val gyroCustomSpeed = stringPreferencesKey("gyro_acceleration_custom_speed")
    private val gyroCustomDistance = stringPreferencesKey("gyro_acceleration_custom_distance")
    private val data = preferences.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }
    internal val lastUpdateCheckAt = data.map { p -> p[updateLastCheckAt]?.takeIf { it >= 0 } }
    val settings = data.map { p -> ControlSettings(p[sensitivity] ?: 2.5f, p[scrollSpeed] ?: 1f,
        p[reverse] ?: false, p[tap] ?: true, p[awake] ?: true, p[address] ?: "",
        HapticSettings(
            enabled = p[hapticEnabled] ?: true,
            strength = HapticStrength.fromCode(p[hapticStrength]),
            leftClick = HapticPattern.fromCode(p[hapticLeft], HapticPattern.Light),
            rightClick = HapticPattern.fromCode(p[hapticRight], HapticPattern.Double),
            scrollHold = HapticPattern.fromCode(p[hapticScrollHold], HapticPattern.Light),
            keyboard = HapticPattern.fromCode(p[hapticKeyboard], HapticPattern.Light),
            backspace = HapticPattern.fromCode(p[hapticBackspace], HapticPattern.Light),
            customAmplitudePercent = p[hapticCustomAmplitudePercent] ?: 75,
            customPulseMs = p[hapticCustomPulseMs] ?: 15,
            scroll = HapticPattern.fromCode(p[hapticScroll], HapticPattern.Light),
        ), TextInputMode.fromCode(p[inputMode]),
        longPressDrag = p[longPressDrag] ?: false,
        doubleTapHoldDrag = p[doubleTapHoldDrag] ?: true,
        reverseTwoFingerScroll = p[reverseTwoFingerScroll] ?: true,
        reportRate = MouseReportRate.fromHz(p[reportRate]),
        autoConnectLast = p[autoConnectLast] ?: false,
        lastComputerName = p[lastComputerName] ?: "",
        themeMode = ThemeMode.fromCode(p[themeMode]),
        fullscreenMode = p[fullscreenMode] ?: false,
        fullscreenRotationMode = FullscreenRotationMode.fromCode(p[fullscreenRotationMode]),
        fullscreenRotation = p[fullscreenRotation] ?: 0,
        fullscreenHelpEnabled = p[fullscreenHelpEnabled] ?: true,
        islandCollapsed = p[islandCollapsed] ?: false,
        portraitIsland = IslandPosition(p[portraitX] ?: 1f, p[portraitY] ?: .72f),
        landscapeIsland = IslandPosition(p[landscapeX] ?: 1f, p[landscapeY] ?: .72f),
        controlsSplit = p[controlsSplit] ?: false,
        portraitControls = SplitControlLayout.decode(p[portraitControls]),
        landscapeControls = SplitControlLayout.decode(p[landscapeControls]),
        portraitToolbarCollapsed = p[portraitToolbarCollapsed] ?: false,
        landscapeToolbarCollapsed = p[landscapeToolbarCollapsed] ?: false,
        mouseControlMode = MouseControlMode.fromCode(p[mouseControlMode]),
        gyro = GyroSettings(p[gyroSensitivity] ?: 2.5f, p[gyroSmoothing] ?: true,
            p[gyroReverseX] ?: false, p[gyroReverseY] ?: false,
            PointerAccelerationSettings(PointerAccelerationMode.fromCode(p[gyroAccelerationMode]),
                PointerAccelerationStrength.fromCode(p[gyroAccelerationStrength]),
                PointerAccelerationCurve.decode(p[gyroCustomSpeed]), PointerAccelerationCurve.decode(p[gyroCustomDistance])),
            GyroDirectionMode.fromCode(p[gyroDirectionMode])),
        touchpadAcceleration = PointerAccelerationSettings(PointerAccelerationMode.fromCode(p[touchpadAccelerationMode]),
            PointerAccelerationStrength.fromCode(p[touchpadAccelerationStrength]),
            PointerAccelerationCurve.decode(p[touchpadCustomSpeed]), PointerAccelerationCurve.decode(p[touchpadCustomDistance])),
        languageMode = LanguageMode.fromCode(p[languageMode]),
        updateCheckFrequency = UpdateCheckFrequency.fromCode(p[updateCheckFrequency]),
    ).sanitized() }

    internal suspend fun recordUpdateCheck(checkedAt: Long) {
        preferences.edit { it[updateLastCheckAt] = checkedAt }
    }

    suspend fun save(value: ControlSettings) {
        val settings = value.sanitized()
        preferences.edit {
            it[sensitivity] = settings.sensitivity; it[scrollSpeed] = settings.scrollSpeed
            it[reverse] = settings.reverseScroll; it[tap] = settings.tapToClick
            it[awake] = settings.keepAwake; it[address] = settings.address
            it[hapticEnabled] = settings.haptics.enabled
            it[hapticStrength] = settings.haptics.strength.code
            it[hapticCustomAmplitudePercent] = settings.haptics.customAmplitudePercent
            it[hapticCustomPulseMs] = settings.haptics.customPulseMs
            it[hapticLeft] = settings.haptics.leftClick.code
            it[hapticRight] = settings.haptics.rightClick.code
            it[hapticScroll] = settings.haptics.scroll.code
            it[hapticScrollHold] = settings.haptics.scrollHold.code
            it[hapticKeyboard] = settings.haptics.keyboard.code
            it[hapticBackspace] = settings.haptics.backspace.code
            it[inputMode] = settings.inputMode.code
            it[longPressDrag] = settings.longPressDrag
            it[doubleTapHoldDrag] = settings.doubleTapHoldDrag
            it[reverseTwoFingerScroll] = settings.reverseTwoFingerScroll
            it[reportRate] = settings.reportRate.hz
            it[autoConnectLast] = settings.autoConnectLast
            it[lastComputerName] = settings.lastComputerName
            it[themeMode] = settings.themeMode.code
            it[fullscreenMode] = settings.fullscreenMode
            it[fullscreenRotationMode] = settings.fullscreenRotationMode.code
            it[fullscreenRotation] = settings.fullscreenRotation
            it[fullscreenHelpEnabled] = settings.fullscreenHelpEnabled
            it[languageMode] = settings.languageMode.code
            it[updateCheckFrequency] = settings.updateCheckFrequency.code
            it[islandCollapsed] = settings.islandCollapsed
            it[portraitX] = settings.portraitIsland.x; it[portraitY] = settings.portraitIsland.y
            it[landscapeX] = settings.landscapeIsland.x; it[landscapeY] = settings.landscapeIsland.y
            it[controlsSplit] = settings.controlsSplit
            settings.portraitControls?.let { value -> it[portraitControls] = value.encode() } ?: it.remove(portraitControls)
            settings.landscapeControls?.let { value -> it[landscapeControls] = value.encode() } ?: it.remove(landscapeControls)
            it[portraitToolbarCollapsed] = settings.portraitToolbarCollapsed
            it[landscapeToolbarCollapsed] = settings.landscapeToolbarCollapsed
            it[mouseControlMode] = settings.mouseControlMode.code
            it[gyroSensitivity] = settings.gyro.sensitivity
            it[gyroDirectionMode] = settings.gyro.directionMode.code
            it[gyroSmoothing] = settings.gyro.smoothing
            it[gyroReverseX] = settings.gyro.reverseX; it[gyroReverseY] = settings.gyro.reverseY
            it[touchpadAccelerationMode] = settings.touchpadAcceleration.mode.code
            it[touchpadAccelerationStrength] = settings.touchpadAcceleration.strength.code
            it[gyroAccelerationMode] = settings.gyro.acceleration.mode.code
            it[gyroAccelerationStrength] = settings.gyro.acceleration.strength.code
            settings.touchpadAcceleration.customSpeed?.let { curve -> it[touchpadCustomSpeed] = curve.encode() } ?: it.remove(touchpadCustomSpeed)
            settings.touchpadAcceleration.customDistance?.let { curve -> it[touchpadCustomDistance] = curve.encode() } ?: it.remove(touchpadCustomDistance)
            settings.gyro.acceleration.customSpeed?.let { curve -> it[gyroCustomSpeed] = curve.encode() } ?: it.remove(gyroCustomSpeed)
            settings.gyro.acceleration.customDistance?.let { curve -> it[gyroCustomDistance] = curve.encode() } ?: it.remove(gyroCustomDistance)
        }
    }
}
