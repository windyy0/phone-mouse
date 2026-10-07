package com.windy.phonemouse.app

import org.junit.Assert.*
import org.junit.Test

class AutoConnectPolicyTest {
    private val last = DiscoveredServer("My desktop", "192.0.2.1", 8765)
    private val other = DiscoveredServer("Other desktop", "192.0.2.2", 8765)
    private val settings = ControlSettings(autoConnectLast = true, address = last.endpoint, lastComputerName = last.name)
    private fun ready() = AutoConnectPolicy().apply { settingsReady = true; foreground = true }
    private fun AutoConnectPolicy.pick(value: ControlSettings = settings, servers: List<DiscoveredServer> = listOf(other, last),
        phase: String = "disconnected", hasTarget: Boolean = false) = takeCandidate(value, servers, phase, hasTarget)

    @Test fun discoveryBeforeSettingsWaitsForLoad() {
        val policy = AutoConnectPolicy().apply { foreground = true }
        assertNull(policy.pick())
        policy.settingsReady = true
        assertEquals(last, policy.pick())
    }

    @Test fun settingsBeforeDiscoveryWaitsForMatchingComputer() {
        val policy = ready()
        assertNull(policy.pick(servers = emptyList()))
        assertNull(policy.pick(servers = listOf(other)))
        assertEquals(last, policy.pick())
    }

    @Test fun disabledAndNeverConnectedDoNotChooseNearbyComputer() {
        val policy = ready()
        assertFalse(ControlSettings().autoConnectLast)
        assertNull(policy.pick(settings.copy(autoConnectLast = false)))
        assertNull(policy.pick(settings.copy(address = "")))
        assertEquals(last, policy.pick())
    }

    @Test fun matchingAddressWithAnotherNameOrPortDoesNotAutoConnect() {
        val policy = ready()
        assertNull(policy.pick(servers = listOf(last.copy(name = "Replacement desktop"))))
        assertNull(policy.pick(servers = listOf(last.copy(port = 8766))))
        assertEquals(last, policy.pick())
    }

    @Test fun backgroundConnectedAndPendingTargetsAreNotReplaced() {
        val policy = ready().apply { foreground = false }
        assertNull(policy.pick())
        policy.foreground = true
        for (phase in listOf("connecting", "reconnecting", "connected")) assertNull(policy.pick(phase = phase))
        assertNull(policy.pick(hasTarget = true))
        assertEquals(last, policy.pick())
    }

    @Test fun repeatedDiscoveryDoesNotRestartClientBackoffAfterAttempt() {
        val policy = ready()
        assertEquals(last, policy.pick())
        repeat(5) { assertNull(policy.pick()) }
        policy.foreground = false; policy.foreground = true
        assertNull(policy.pick())
    }

    @Test fun manualDisconnectStaysDisconnectedUntilExplicitReenableOrNewLaunch() {
        val policy = ready(); policy.manualDisconnect()
        repeat(5) { assertNull(policy.pick()) }
        policy.foreground = false; policy.foreground = true
        assertNull(policy.pick())
        policy.rearm()
        assertEquals(last, policy.pick())
        assertEquals(last, ready().pick())
    }
}
