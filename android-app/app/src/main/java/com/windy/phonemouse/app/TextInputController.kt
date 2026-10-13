package com.windy.phonemouse.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Owns the text session and panel lifecycle; the transport and UI stay outside. */
internal class TextInputController(
    private val sendRequest: (TextSyncRequest) -> Long?,
    private val canPoll: () -> Boolean,
    private val publish: (TextSyncUiState) -> Unit,
) {
    private val session = TextSyncSession()
    private var panels = 0
    val state get() = session.state

    fun start(scope: CoroutineScope) = scope.launch {
        while (true) {
            delay(180)
            pump()
        }
    }

    fun connection(connected: Boolean, supported: Boolean, sourceSupported: Boolean) {
        session.connection(connected, supported, sourceSupported)
        publish(state)
    }

    fun result(id: Long, snapshot: RemoteTextSnapshot?, error: String?) {
        session.result(id, snapshot, error)
        publish(state)
    }

    fun panelOpened() { panels++; pump() }
    fun panelClosed() {
        panels = (panels - 1).coerceAtLeast(0)
        if (panels == 0) suspend()
    }

    fun pump() {
        if (panels == 0 || !canPoll()) return
        val request = session.nextRequest() ?: return
        val id = sendRequest(request) ?: return
        session.begin(id, request)
        publish(state)
    }

    fun localEdit(text: String, start: Int, end: Int, composing: Boolean) {
        session.localEdit(text, start, end, composing)
        publish(state)
    }
    fun shiftEnter() { session.requestShiftEnter(); pump() }

    fun suspend() { session.suspend(); publish(state) }
    fun reload() { session.reload(); publish(state) }
    fun useOrdinaryInput(enabled: Boolean): String? =
        session.useOrdinaryInput(enabled).also { publish(state) }
}

internal fun appendInputDraft(existing: String, vararg parts: String?): String {
    val added = parts.filterNotNull().filter { it.isNotEmpty() }.joinToString("\n")
    return existing + (if (existing.isNotEmpty() && added.isNotEmpty()) "\n" else "") + added
}
