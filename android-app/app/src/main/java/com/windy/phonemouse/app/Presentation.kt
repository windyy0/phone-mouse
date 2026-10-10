package com.windy.phonemouse.app

enum class PresentationMode(val code: String) { Mouse("mouse"), Laser("laser") }

enum class LaserColor(val code: String, val label: String, val rgb: Int) {
    Red("red", "红色", 0xFF3030), Orange("orange", "橙色", 0xFF9020), Yellow("yellow", "黄色", 0xFFE030),
    Green("green", "绿色", 0x30DD70), Blue("blue", "蓝色", 0x30A0FF), Purple("purple", "紫色", 0xC060FF);
    companion object { fun fromCode(code: String?) = entries.firstOrNull { it.code == code } ?: Red }
}

data class LaserSettings(val color: LaserColor = LaserColor.Red, val trail: Boolean = true, val fadeMs: Int = 800,
    val sizePercent: Int = 110, val trailLengthPx: Int = 180) {
    fun sanitized() = copy(fadeMs = fadeMs.coerceIn(100, 3000), sizePercent = sizePercent.coerceIn(50, 300),
        trailLengthPx = trailLengthPx.coerceIn(20, 300))
}

data class PresentationState(
    val supported: Boolean = false,
    val mode: PresentationMode = PresentationMode.Mouse,
    val switching: Boolean = false,
    val inputEpoch: Long = 0,
    val sizeSupported: Boolean = false,
    val trailLengthSupported: Boolean = false,
    val extendedRangeSupported: Boolean = false,
) { val laser get() = mode == PresentationMode.Laser }

/** Worker-owned mode acknowledgements; a fresh epoch cancels old gestures. */
internal class PresentationSession {
    var state = PresentationState(); private set
    var udpToken: ByteArray? = null; private set
    var pendingId: Long? = null; private set
    fun ready(token: ByteArray, supported: Boolean, sizeSupported: Boolean = false, trailLengthSupported: Boolean = false,
        extendedRangeSupported: Boolean = false) {
        pendingId = null; udpToken = token.copyOf()
        state = PresentationState(supported = supported, sizeSupported = supported && sizeSupported,
            trailLengthSupported = supported && trailLengthSupported,
            extendedRangeSupported = supported && extendedRangeSupported,
            inputEpoch = state.inputEpoch + 1)
    }
    fun begin(id: Long): Boolean {
        if (!state.supported || pendingId != null) return false
        pendingId = id; state = state.copy(switching = true, inputEpoch = state.inputEpoch + 1)
        return true
    }
    fun acknowledge(id: Long?, mode: PresentationMode, token: ByteArray, forced: Boolean): Boolean {
        if (id != null && id != pendingId) return false
        if (id == null && !forced && pendingId != null) return false
        if (id == null && !forced && udpToken?.contentEquals(token) == true && state.mode == mode) return false
        pendingId = null; udpToken = token.copyOf()
        state = state.copy(mode = mode, switching = false, inputEpoch = state.inputEpoch + 1)
        return true
    }
    fun reject(id: Long): Boolean {
        if (id != pendingId) return false
        pendingId = null; state = state.copy(switching = false, inputEpoch = state.inputEpoch + 1)
        return true
    }
    fun reset() {
        pendingId = null; udpToken = null
        state = PresentationState(inputEpoch = state.inputEpoch + 1)
    }
}
