package com.windy.phonemouse.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionDeliveryQueueTest {
    private class Harness(var acceptButtons: Boolean = true) {
        val events = mutableListOf<String>()
        val queue = MotionDeliveryQueue(
            sendMotion = { packet, reliable -> events += "${if (reliable) "tcp" else "udp"}:${packet.dx},${packet.dy},${packet.wheel}" },
            sendButton = { button, down -> events += "$button:${if (down) "down" else "up"}"; acceptButtons },
        )
    }

    @Test fun ordinaryMovementKeepsUdpPath() {
        val h = Harness()
        h.queue.move(2.0, 3.0); h.queue.flush()
        assertEquals(listOf("udp:2,3,0"), h.events)
    }
    @Test fun repositionDropsPendingPointerMotionButPreservesWheelAndHeldButtons() {
        val h = Harness()
        h.queue.buttonDown("left", "button")
        h.queue.move(4200.75, -5.5); h.queue.scroll(120.5)
        h.queue.clearPointerMotion(); h.queue.flush()
        h.queue.move(.5, .5); h.queue.flush()
        h.queue.move(.5, .5); h.queue.flush()
        h.queue.buttonUp("left", "button")
        assertEquals(listOf("left:down", "tcp:0,0,120", "tcp:1,1,0", "left:up"), h.events)
    }

    @Test fun dragAndLastDeltaShareButtonStreamInOrder() {
        val h = Harness()
        h.queue.buttonDown("left")
        h.queue.move(8.0, 0.0); h.queue.flush()
        h.queue.move(3.0, 2.0); h.queue.buttonUp("left")
        h.queue.move(1.0, 0.0); h.queue.flush()
        assertEquals(listOf("left:down", "tcp:8,0,0", "tcp:3,2,0", "left:up", "udp:1,0,0"), h.events)
    }

    @Test fun pendingMovementIsFlushedReliablyBeforePress() {
        val h = Harness()
        h.queue.move(3.0, 0.0); h.queue.buttonDown("left")
        assertEquals(listOf("tcp:3,0,0", "left:down"), h.events)
    }

    @Test fun clampedOverflowCannotEscapePastRelease() {
        val h = Harness()
        h.queue.buttonDown("left")
        h.queue.move(4200.0, 0.0); h.queue.buttonUp("left"); h.queue.flush()
        assertEquals(listOf("left:down", "tcp:2000,0,0", "tcp:2000,0,0", "tcp:200,0,0", "left:up"), h.events)
    }

    @Test fun disconnectResetDropsPendingMovementAndHeldTransportState() {
        val h = Harness()
        h.queue.buttonDown("left"); h.queue.move(8.0, 0.0)
        h.queue.reset(); h.queue.flush()
        h.queue.move(2.0, 0.0); h.queue.flush()
        assertEquals(listOf("left:down", "udp:2,0,0"), h.events)
    }

    @Test fun failedPressDoesNotLeaveOrdinaryMovementOnDragTransport() {
        val h = Harness(acceptButtons = false)
        h.queue.buttonDown("left"); h.queue.move(2.0, 0.0); h.queue.flush()
        assertEquals(listOf("left:down", "udp:2,0,0"), h.events)
    }

    @Test fun buttonAndTouchpadShareOnePhysicalPressUntilBothRelease() {
        val h = Harness()
        h.queue.buttonDown("left", "button"); h.queue.buttonDown("left", "touchpad")
        h.queue.buttonDown("left", "touchpad")
        h.queue.buttonUp("left", "touchpad")
        h.queue.move(2.0, 0.0); h.queue.flush()
        h.queue.buttonUp("left", "button")
        h.queue.buttonUp("left", "button")
        assertEquals(listOf("left:down", "tcp:2,0,0", "left:up"), h.events)
    }

    @Test fun independentLeftAndRightHoldsKeepMovementOrderedUntilBothRelease() {
        val h = Harness()
        h.queue.buttonDown("left", "button"); h.queue.buttonDown("right", "button")
        h.queue.move(2.0, 0.0); h.queue.buttonUp("left", "button")
        h.queue.move(3.0, 0.0); h.queue.flush(); h.queue.buttonUp("right", "button")
        h.queue.move(1.0, 0.0); h.queue.flush()
        assertEquals(listOf("left:down", "right:down", "tcp:2,0,0", "left:up", "tcp:3,0,0", "right:up", "udp:1,0,0"), h.events)
    }

    @Test fun staleReleaseFromAnotherSourceCannotReleaseAHeldButton() {
        val h = Harness(); h.queue.buttonDown("left", "button")
        h.queue.buttonUp("left", "touchpad")
        h.queue.move(1.0, 0.0); h.queue.flush()
        h.queue.buttonUp("left", "button")
        assertEquals(listOf("left:down", "tcp:1,0,0", "left:up"), h.events)
    }

    @Test fun middleHoldAndOtherButtonsReleaseIndependently() {
        val h = Harness()
        h.queue.buttonDown("middle", "button"); h.queue.buttonDown("left", "button")
        h.queue.move(2.0, 0.0); h.queue.buttonUp("left", "button")
        h.queue.move(3.0, 0.0); h.queue.buttonUp("middle", "button")
        h.queue.move(1.0, 0.0); h.queue.flush()
        assertEquals(listOf("middle:down", "left:down", "tcp:2,0,0", "left:up",
            "tcp:3,0,0", "middle:up", "udp:1,0,0"), h.events)
    }
}
