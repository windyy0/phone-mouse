package com.windy.phonemouse.app

import org.junit.Assert.*
import org.junit.Test

class PresentationSessionTest {
    private val initial = ByteArray(16) { 1 }
    private val next = ByteArray(16) { 2 }
    @Test fun trailLengthCapabilityIsIndependentAndClearedOnReconnect() {
        val session = PresentationSession()
        session.ready(initial, false, true, true)
        assertFalse(session.state.sizeSupported)
        assertFalse(session.state.trailLengthSupported)
        session.ready(initial, true, false, true)
        assertTrue(session.state.trailLengthSupported)
        assertFalse(session.state.sizeSupported)
        session.reset()
        session.ready(next, true, true)
        assertFalse(session.state.trailLengthSupported)
        assertTrue(session.state.sizeSupported)
    }
    @Test fun extendedRangeCapabilityRequiresLaserAndIsClearedOnReconnect() {
        val session = PresentationSession()
        session.ready(initial, false, true, true, true)
        assertFalse(session.state.extendedRangeSupported)
        session.ready(initial, true, true, true, true)
        assertTrue(session.state.extendedRangeSupported)
        session.reset()
        assertFalse(session.state.extendedRangeSupported)
        session.ready(next, true, true, true)
        assertFalse(session.state.extendedRangeSupported)
    }
    @Test fun oldReceiversKeepMouseModeAndCannotBeginLaser() {
        val session = PresentationSession()
        session.ready(initial, false)
        assertFalse(session.begin(1))
        assertEquals(PresentationMode.Mouse, session.state.mode)
    }
    @Test fun switchRequiresMatchingAcknowledgementAndRotatesToken() {
        val session = PresentationSession()
        session.ready(initial, true)
        val before = session.state.inputEpoch
        assertTrue(session.begin(10))
        assertTrue(session.state.switching)
        assertFalse(session.begin(11))
        assertFalse(session.acknowledge(9, PresentationMode.Laser, next, false))
        assertTrue(session.state.switching)
        assertTrue(session.acknowledge(10, PresentationMode.Laser, next, false))
        assertFalse(session.state.switching)
        assertTrue(session.state.laser)
        assertArrayEquals(next, session.udpToken)
        assertTrue(session.state.inputEpoch > before)
        assertFalse(session.acknowledge(null, PresentationMode.Laser, next, false))
    }
    @Test fun forcedExitCancelsPendingSwitchAndLateAcknowledgementCannotReenable() {
        val session = PresentationSession()
        session.ready(initial, true)
        session.begin(10)
        assertTrue(session.acknowledge(null, PresentationMode.Mouse, next, true))
        assertFalse(session.acknowledge(10, PresentationMode.Laser, initial, false))
        assertFalse(session.state.laser)
        assertFalse(session.state.switching)
    }
    @Test fun reconnectNeverRestoresLaserAndRejectedSwitchUnblocksInput() {
        val session = PresentationSession()
        session.ready(initial, true)
        session.begin(10)
        assertTrue(session.reject(10))
        assertFalse(session.state.switching)
        session.begin(11)
        session.acknowledge(11, PresentationMode.Laser, next, false)
        session.reset()
        session.ready(initial, true)
        assertFalse(session.state.laser)
        assertNull(session.pendingId)
    }
}
