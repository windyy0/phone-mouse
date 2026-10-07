package com.windy.phonemouse.app

/** One owning finger per button; other fingers may operate the touchpad. */
internal class MouseButtonPressController(
    private val onDown: () -> Unit,
    private val onUp: () -> Unit,
) {
    var owner: Long? = null
        private set

    fun press(pointer: Long): Boolean {
        if (owner != null) return false
        owner = pointer
        onDown()
        return true
    }

    fun release(pointer: Long) { if (owner == pointer) cancel() }

    fun cancel() {
        if (owner == null) return
        owner = null
        onUp()
    }

    fun activate(): Boolean {
        if (!press(KEY_OWNER)) return false
        try { return true } finally { release(KEY_OWNER) }
    }

    companion object { const val KEY_OWNER = Long.MIN_VALUE }
}
