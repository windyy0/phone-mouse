package com.windy.phonemouse.app

enum class MouseReportRate(val hz: Int, val label: String, val intervalLabel: String) {
    Hz250(250, "250 Hz", "4 ms"),
    Hz500(500, "500 Hz", "2 ms"),
    Hz1000(1000, "1K", "1 ms"),
    Hz2000(2000, "2K", "0.5 ms"),
    Hz4000(4000, "4K", "0.25 ms");

    val intervalNanos: Long get() = 1_000_000_000L / hz

    companion object {
        fun fromHz(hz: Int?) = entries.firstOrNull { it.hz == hz } ?: Hz250

        // Leave some headroom around a nominal rate (e.g. 240 Hz -> 250 Hz).
        // Use event delivery: history points arrive together and cannot each
        // cause an independent desktop update after they have been coalesced.
        fun suggestedFor(deliveryHz: Double): MouseReportRate =
            entries.firstOrNull { it.hz >= deliveryHz * 0.95 } ?: Hz4000
    }
}
