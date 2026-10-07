package com.windy.phonemouse.app

/** Owned by the network worker; drag movement shares the button's TCP stream. */
internal class MotionDeliveryQueue(
    private val sendMotion: (MotionPacket, reliable: Boolean) -> Unit,
    private val sendButton: (String, down: Boolean) -> Boolean,
) {
    private val motion = MotionAccumulator()
    private val heldButtons = mutableMapOf<String, MutableSet<String>>()

    fun move(dx: Double, dy: Double) = motion.move(dx, dy)
    fun scroll(delta: Double) = motion.scroll(delta)
    fun pending() = motion.pending()
    fun clearPointerMotion() = motion.clearPointerMotion()
    fun held(button: String) = heldButtons.containsKey(button)

    fun flush(reliable: Boolean = false, all: Boolean = false) {
        if (!motion.pending()) return
        do {
            sendMotion(motion.drain(), reliable || heldButtons.isNotEmpty())
        } while (all && motion.pending())
    }

    fun buttonDown(button: String, source: String = "touchpad") {
        heldButtons[button]?.let { it.add(source); return }
        flush(reliable = true, all = true)
        if (sendButton(button, true)) heldButtons[button] = mutableSetOf(source)
    }

    fun buttonUp(button: String, source: String = "touchpad") {
        val sources = heldButtons[button] ?: return
        if (!sources.remove(source) || sources.isNotEmpty()) return
        flush(reliable = true, all = true)
        sendButton(button, false)
        heldButtons.remove(button)
    }

    fun reset() { motion.clear(); heldButtons.clear() }
}
