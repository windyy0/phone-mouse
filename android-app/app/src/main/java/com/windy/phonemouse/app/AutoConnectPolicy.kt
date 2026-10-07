package com.windy.phonemouse.app

/** Called on the main thread; the client owns retries after the first attempt. */
internal class AutoConnectPolicy {
    var settingsReady = false
    var foreground = false
    private var attempted = false

    fun rearm() { attempted = false }
    fun manualDisconnect() { attempted = true }

    fun takeCandidate(settings: ControlSettings, servers: List<DiscoveredServer>, phase: String, hasTarget: Boolean): DiscoveredServer? {
        if (!settingsReady || !foreground || attempted || !settings.autoConnectLast ||
            settings.address.isBlank() || phase != "disconnected" || hasTarget) return null
        // Discovery has no stable device ID. Match the saved address and,
        // when available, name instead of choosing another nearby computer.
        val candidate = servers.firstOrNull {
            it.endpoint == settings.address && (settings.lastComputerName.isBlank() || it.name == settings.lastComputerName)
        } ?: return null
        attempted = true
        return candidate
    }
}
