package com.windy.phonemouse.app

/** Only motion and axis direction vary with screen orientation. */
data class GyroActionSettings(
    val directionMode: GyroDirectionMode = GyroDirectionMode.Tilt,
    val reverseX: Boolean = false,
    val reverseY: Boolean = false,
) {
    internal fun applyTo(shared: GyroSettings) = shared.copy(
        directionMode = directionMode, reverseX = reverseX, reverseY = reverseY)
}

internal enum class GyroOrientation(val label: String) {
    Portrait("竖屏"), Landscape("横屏");

    companion object {
        fun fromRotation(rotation: Int) = if (rotation == 1 || rotation == 3) Landscape else Portrait
    }
}

internal fun GyroSettings.actionSettings() = GyroActionSettings(directionMode, reverseX, reverseY)

internal fun ControlSettings.gyroAction(orientation: GyroOrientation): GyroActionSettings {
    val separate = if (orientation == GyroOrientation.Portrait) portraitGyroAction else landscapeGyroAction
    return if (gyroActionsSplit) separate ?: gyro.actionSettings() else gyro.actionSettings()
}

internal fun ControlSettings.withGyroActionsSplit(enabled: Boolean) = copy(
    gyroActionsSplit = enabled,
    portraitGyroAction = portraitGyroAction ?: if (enabled) gyro.actionSettings() else null,
    landscapeGyroAction = landscapeGyroAction ?: if (enabled) gyro.actionSettings() else null,
)

internal fun ControlSettings.updateGyroAction(orientation: GyroOrientation,
    transform: (GyroActionSettings) -> GyroActionSettings): ControlSettings {
    val action = transform(gyroAction(orientation))
    return if (!gyroActionsSplit) copy(gyro = action.applyTo(gyro))
        else if (orientation == GyroOrientation.Portrait) copy(portraitGyroAction = action)
        else copy(landscapeGyroAction = action)
}

internal fun ControlSettings.effectiveGyroSettings(rotation: Int) =
    gyroAction(GyroOrientation.fromRotation(rotation)).applyTo(gyro)

/** Use display orientation, never gravity or the phone's transient leaning angle. */
internal fun ControlSettings.effectiveGyroRotation(displayRotation: Int,
    fullscreenVisible: Boolean = fullscreenMode): Int =
    if (fullscreenVisible && fullscreenRotationMode == FullscreenRotationMode.Manual)
        when (fullscreenRotation) { 90 -> 1; 180 -> 2; 270 -> 3; else -> 0 }
    else displayRotation.coerceIn(0, 3)
