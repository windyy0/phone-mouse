package com.windy.phonemouse.app

import org.junit.Assert.*
import org.junit.Test

class TextInputControllerTest {
    @Test fun startupNoInputContinuesPollingUntilTheQqEditorBecomesReady() {
        for (initialStatus in listOf("no_input", "unsupported", "unavailable")) {
            val requests = mutableListOf<TextSyncRequest>()
            var ui = UiState()
            val controller = TextInputController(
                sendRequest = { requests += it; requests.size.toLong() },
                canPoll = { true }, publish = { ui = ui.copy(textSync = it) },
            )
            controller.connection(true, true, false)
            controller.panelOpened()
            for (id in 1L..3L) {
                controller.result(id, RemoteTextSnapshot(id, id, initialStatus, "QQ", "", null, null), null)
                assertFalse(controller.state.paused)
                controller.pump()
                assertEquals("text_read", requests.last().type)
            }
            controller.result(4, RemoteTextSnapshot(4, 4, "editable", "QQ", "", 0, 0), null)
            assertTrue(ui.showSyncedInput)
            assertEquals("已同步", controller.state.message)
        }
    }
    @Test fun theFirstDesktopReadCannotReplaceThePhoneEditorWhileItsFirstWordIsComposing() {
        for (desktop in listOf("", "已有文字")) {
            var ui = UiState()
            val requests = mutableListOf<TextSyncRequest>()
            val delivery = KeyboardDeliveryQueue()
            val controller = TextInputController(
                sendRequest = { requests += it; requests.size.toLong() },
                canPoll = { ui.liveText.isEmpty() && !delivery.hasPending },
                publish = { ui = ui.copy(textSync = it) },
            )
            controller.connection(true, true, false)
            controller.panelOpened()
            assertEquals("text_read", requests.single().type)

            // The first desktop read was already in flight when the IME began
            // its first candidate. Replacing the native View invalidates it.
            ui = ui.copy(liveText = "ni")
            controller.result(1, RemoteTextSnapshot(7, 1, "editable", "Codex", desktop, desktop.length, desktop.length), null)
            assertFalse(ui.showSyncedInput)
            assertEquals("ni", ui.liveText)
            controller.pump()
            assertEquals(1, requests.size)

            // Follow realtimeCommit: invalidate the old read, enqueue the
            // committed word, then clear the phone's provisional buffer.
            controller.reload()
            assertTrue(delivery.enqueue(listOf(KeyboardOperation.Text("你", draft = false))))
            assertEquals(KeyboardOperation.Text("你", draft = false), delivery.next())
            ui = ui.copy(liveText = "")
            assertFalse(ui.showSyncedInput)
            controller.pump()
            assertEquals(1, requests.size)

            delivery.acknowledge()
            controller.pump()
            assertEquals("text_read", requests.last().type)
            controller.result(2, RemoteTextSnapshot(7, 2, "editable", "Codex", desktop + "你", desktop.length + 1, desktop.length + 1), null)
            assertTrue(ui.showSyncedInput)
            assertEquals(desktop + "你", ui.textSync.text)
            assertFalse(ui.textSync.paused)
        }
    }

    @Test fun typingInAnEstablishedSyncEditorDoesNotSwitchBackToOrdinaryInput() {
        var ui = UiState()
        val controller = TextInputController(
            sendRequest = { 1L }, canPoll = { true },
            publish = { ui = ui.copy(textSync = it) },
        )
        controller.connection(true, true, false)
        controller.panelOpened()
        controller.result(1, RemoteTextSnapshot(7, 1, "editable", "Codex", "", 0, 0), null)
        assertTrue(ui.showSyncedInput)
        controller.localEdit("ni", 2, 2, composing = true)
        assertTrue(ui.showSyncedInput)
        assertTrue(ui.textSync.composing)
        assertEquals("ni", ui.textSync.text)
    }

    @Test fun phoneEnterWaitsForCommittedTextAndUsesTheAcknowledgedRevision() {
        val requests = mutableListOf<TextSyncRequest>()
        val controller = readyController(requests)
        controller.localEdit("你好😀", 4, 4, false)
        controller.enter()
        assertEquals("text_edit", requests.last().type)
        assertEquals("你好😀", requests.last().text)
        controller.pump()
        assertEquals(2, requests.size)

        controller.result(2, RemoteTextSnapshot(7, 2, "editable", "Codex", "你好😀", 4, 4), null)
        controller.pump()
        assertEquals("text_enter", requests.last().type)
        assertEquals(7L, requests.last().target)
        assertEquals(2L, requests.last().revision)
        controller.result(3, RemoteTextSnapshot(7, 3, "editable", "Codex", "", 0, 0), null)
        assertEquals("", controller.state.text)
        assertFalse(controller.state.paused)
        controller.pump()
        assertEquals("text_read", requests.last().type)
    }

    @Test fun phoneEnterWaitsForThePhoneCaretEvenWhenAReadIsInFlight() {
        val requests = mutableListOf<TextSyncRequest>()
        val controller = readyController(requests)
        controller.pump()
        controller.localEdit("你好", 1, 1, false)
        controller.enter()
        assertEquals(2, requests.size)
        controller.result(2, RemoteTextSnapshot(7, 1, "editable", "Codex", "你好", 2, 2), null)
        controller.pump()
        assertTrue(requests.last().selectionOnly)
        assertEquals(1, requests.last().start)
        controller.result(3, RemoteTextSnapshot(7, 2, "editable", "Codex", "你好", 1, 1), null)
        controller.pump()
        assertEquals("text_enter", requests.last().type)
        assertEquals(2L, requests.last().revision)
    }

    @Test fun enteringWhileComposingDoesNotSubmitTheCandidateAfterItCommits() {
        val requests = mutableListOf<TextSyncRequest>()
        val controller = readyController(requests)
        controller.localEdit("你好ni", 4, 4, true)
        controller.enter()
        assertEquals(1, requests.size)
        controller.localEdit("你好你", 3, 3, false)
        controller.pump()
        assertEquals("text_edit", requests.last().type)
        controller.result(2, RemoteTextSnapshot(7, 2, "editable", "Codex", "你好你", 3, 3), null)
        controller.pump()
        assertEquals("text_read", requests.last().type)
    }

    @Test fun anUnconfirmedPhoneEnterIsNotReplayedAfterReconnection() {
        val requests = mutableListOf<TextSyncRequest>()
        val controller = readyController(requests)
        controller.enter()
        assertEquals("text_enter", requests.last().type)
        controller.connection(false, true, false)
        controller.connection(true, true, false)
        controller.pump()
        assertTrue(controller.state.paused)
        assertEquals("unconfirmed", controller.state.error)
        assertEquals(2, requests.size)
        controller.reload()
        controller.pump()
        assertEquals("text_read", requests.last().type)
    }

    @Test fun repeatedPhoneEntersAndShiftEnterAreDeliveredOnceInOrder() {
        val requests = mutableListOf<TextSyncRequest>()
        val controller = readyController(requests)
        controller.enter()
        controller.enter()
        controller.shiftEnter()
        assertEquals(2, requests.size)
        controller.result(2, RemoteTextSnapshot(7, 2, "editable", "notepad", "你好\n", 3, 3), null)
        controller.pump()
        assertEquals("text_enter", requests.last().type)
        assertEquals(2L, requests.last().revision)
        controller.result(3, RemoteTextSnapshot(7, 3, "editable", "notepad", "你好\n\n", 4, 4), null)
        controller.pump()
        assertEquals("text_shift_enter", requests.last().type)
        assertEquals(3L, requests.last().revision)
        controller.result(4, RemoteTextSnapshot(7, 4, "editable", "notepad", "你好\n\n\n", 5, 5), null)
        controller.pump()
        assertEquals("text_read", requests.last().type)
    }

    @Test fun aPhoneEnterThatChangesFocusCancelsBufferedEnters() {
        val requests = mutableListOf<TextSyncRequest>()
        val controller = readyController(requests)
        controller.enter()
        controller.enter()
        controller.result(2, RemoteTextSnapshot(8, 2, "editable", "Codex", "另一个输入框", 7, 7), null)
        controller.pump()
        assertEquals("text_read", requests.last().type)
        assertFalse(controller.state.paused)
    }

    @Test fun aFocusChangeWhileReadingOrMovingTheCaretCancelsAnUnsentPhoneEnter() {
        for (movingCaret in listOf(false, true)) {
            val requests = mutableListOf<TextSyncRequest>()
            val controller = readyController(requests)
            if (movingCaret) controller.localEdit("你好", 1, 1, false)
            controller.pump()
            controller.enter()
            assertEquals(2, requests.size)
            controller.result(2, RemoteTextSnapshot(8, 2, "editable", "Codex", "另一个输入框", 7, 7), null)
            controller.pump()
            assertEquals("text_read", requests.last().type)
        }
    }

    private fun readyController(requests: MutableList<TextSyncRequest>) = TextInputController(
        sendRequest = { requests += it; requests.size.toLong() },
        canPoll = { true },
        publish = {},
    ).apply {
        connection(true, true, false)
        panelOpened()
        result(1, RemoteTextSnapshot(7, 1, "editable", "Codex", "你好", 2, 2), null)
    }

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
