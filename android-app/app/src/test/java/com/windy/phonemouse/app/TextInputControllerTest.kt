package com.windy.phonemouse.app

import org.junit.Assert.*
import org.junit.Test

class TextInputControllerTest {
    @Test fun closingTheLastPanelRetainsAnUnconfirmedEditAndIgnoresItsLateReply() {
        val requests = mutableListOf<TextSyncRequest>()
        val controller = TextInputController(
            sendRequest = { requests += it; requests.size.toLong() },
            canPoll = { true },
            publish = {},
        )
        controller.connection(true, true, false)
        controller.panelOpened()
        controller.panelOpened()
        controller.result(1, RemoteTextSnapshot(7, 1, "editable", "notepad", "原文", 2, 2), null)
        controller.localEdit("原文！", 3, 3, false)
        controller.pump()
        assertEquals("text_edit", requests.last().type)
        controller.panelClosed()
        assertTrue(controller.state.waiting)
        assertFalse(controller.state.paused)
        controller.panelClosed()
        assertTrue(controller.state.paused)
        controller.result(2, RemoteTextSnapshot(7, 2, "editable", "notepad", "原文！", 3, 3), null)
        controller.panelOpened()
        controller.pump()
        assertEquals(2, requests.size)
        assertEquals("原文！", controller.state.text)
        controller.reload()
        controller.pump()
        assertEquals("text_read", requests.last().type)
    }

    @Test fun pollingWaitsForForegroundAndTransportReadinessAndStopsWithoutPanels() {
        var foreground = false
        var ready = false
        val requests = mutableListOf<TextSyncRequest>()
        val controller = TextInputController(
            sendRequest = { if (ready) { requests += it; requests.size.toLong() } else null },
            canPoll = { foreground },
            publish = {},
        )
        controller.connection(true, true, true)
        controller.panelOpened()
        assertTrue(requests.isEmpty())
        foreground = true
        controller.pump()
        assertFalse(controller.state.waiting)
        ready = true
        controller.pump()
        controller.pump()
        assertEquals(1, requests.size)
        controller.result(1, RemoteTextSnapshot(7, 1, "editable", "notepad", "", 0, 0), null)
        controller.panelClosed()
        controller.pump()
        assertEquals(1, requests.size)
    }
}
