package com.windy.phonemouse.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TextSyncSessionTest {
    @Test fun deletingAGeneratedMarkerAdoptsTheStructuralCaretAndKeepsNewerBodyInput() {
        for (newer in listOf(false, true)) {
            val session = readySession()
            session.begin(1020, session.nextRequest()!!)
            session.result(1020, snapshot(text = "• \n", start = 2), null)
            session.localEdit("•\n", 1, 1, composing = false)
            session.begin(1021, session.nextRequest()!!)
            if (newer) session.localEdit("•正文\n", 3, 3, composing = false)
            session.result(1021, snapshot(revision = 2, text = "", start = 0)
                .copy(editNormalized = true, editCaret = 0), null)
            assertFalse(session.state.paused)
            assertEquals(if (newer) "正文" else "", session.state.text)
            assertEquals(if (newer) 2 else 0, session.state.selectionStart)
            assertEquals(if (newer) "text_edit" else "text_read", session.nextRequest()!!.type)
        }
    }
    @Test fun structuralCaretIsParsedAndCannotSplitAnEmoji() {
        val json = JSONObject().put("target", 7).put("revision", 2).put("status", "editable")
            .put("text", "😀").put("selection_start", 2).put("selection_end", 2)
            .put("edit_normalized", true).put("edit_caret", 2)
        assertEquals(2, RemoteTextSnapshot.parse(json)!!.editCaret)
        json.put("edit_caret", 1)
        assertNull(RemoteTextSnapshot.parse(json))
    }
    @Test fun shiftEnterWaitsForTextAndCaretAcknowledgementsAndUsesTheirRevision() {
        val session = readySession()
        session.localEdit("你好！", 3, 3, composing = false)
        session.requestShiftEnter()
        val edit = session.nextRequest()!!
        assertEquals("text_edit", edit.type)
        session.begin(1010, edit)
        session.result(1010, snapshot(revision = 2, text = "你好！"), null)
        session.localEdit("你好！", 1, 1, composing = false)
        val caret = session.nextRequest()!!
        assertTrue(caret.selectionOnly)
        session.begin(1011, caret)
        session.result(1011, snapshot(revision = 3, text = "你好！", start = 1), null)
        val enter = session.nextRequest()!!
        assertEquals("text_shift_enter", enter.type)
        assertEquals(3L, enter.revision)
        assertEquals(setOf("type", "id", "target", "revision"), enter.json(1012).keys().asSequence().toSet())
        session.begin(1012, enter)
        session.result(1012, snapshot(revision = 4, text = "你\n好！", start = 2), null)
        assertEquals("你\n好！", session.state.text)
        assertEquals("text_read", session.nextRequest()!!.type)
        assertFalse(session.state.paused)
    }

    @Test fun anUnconfirmedShiftEnterIsNeverSentAgainAfterReconnect() {
        val session = readySession()
        session.requestShiftEnter()
        session.begin(1013, session.nextRequest()!!)
        session.connection(false, true)
        assertTrue(session.state.paused)
        assertEquals("unconfirmed", session.state.error)
        session.connection(true, true)
        assertNull(session.nextRequest())
        session.reload()
        assertEquals("text_read", session.nextRequest()!!.type)
        val composing = readySession()
        composing.localEdit("拼音", 2, 2, composing = true)
        composing.requestShiftEnter()
        composing.localEdit("拼音", 2, 2, composing = false)
        assertEquals("text_edit", composing.nextRequest()!!.type)
    }
    private fun listSession(): TextSyncSession = readySession().apply {
        begin(10, nextRequest()!!)
        result(10, snapshot(text = "-"), null)
        localEdit("- ", 2, 2, composing = false)
        begin(11, nextRequest()!!)
    }

    @Test fun confirmedListConversionAdoptsTheGeneratedMarkerAndCaret() {
        val session = listSession()
        session.result(11, snapshot(revision = 2, text = "• ", start = 2).copy(editNormalized = true), null)
        assertEquals("• ", session.state.text)
        assertEquals(2, session.state.selectionStart)
        assertFalse(session.state.dirty)
        assertFalse(session.state.paused)
        assertEquals("text_read", session.nextRequest()!!.type)
        session.localEdit("• 正文", 4, 4, composing = false)
        assertEquals("• 正文", session.nextRequest()!!.text)
        assertEquals(2L, session.nextRequest()!!.revision)
    }

    @Test fun conversionRebasesNewerUnicodeInputBeforeTheEmptyParagraphFiller() {
        val session = listSession()
        session.localEdit("- 😀正文", 6, 6, composing = false)
        session.result(11, snapshot(revision = 2, text = "• \n", start = 2).copy(editNormalized = true), null)
        assertEquals("• 😀正文\n", session.state.text)
        assertEquals(6, session.state.selectionStart)
        assertTrue(session.state.dirty)
        assertFalse(session.state.paused)
        val next = session.nextRequest()!!
        assertEquals("• 😀正文\n", next.text)
        assertEquals(2L, next.revision)
    }

    @Test fun conversionWaitsForTheLocalImeCommitWithoutReplacingItsBuffer() {
        val session = listSession()
        session.localEdit("- zheng", 7, 7, composing = true)
        session.result(11, snapshot(revision = 2, text = "• ", start = 2).copy(editNormalized = true), null)
        assertEquals("- zheng", session.state.text)
        assertTrue(session.state.composing)
        assertNull(session.nextRequest())
        session.localEdit("- 正", 3, 3, composing = false)
        assertEquals("• 正", session.state.text)
        assertEquals("• 正", session.nextRequest()!!.text)
        assertFalse(session.state.paused)
    }

    @Test fun conversionDoesNotDiscardOverlappingEditsOrAcceptAnUnconfirmedReply() {
        val overlap = listSession()
        overlap.localEdit("其他", 2, 2, composing = false)
        overlap.result(11, snapshot(revision = 2, text = "• ").copy(editNormalized = true), null)
        assertEquals("其他", overlap.state.text)
        assertTrue(overlap.state.paused)
        for (reply in listOf(
            snapshot(revision = 2, text = "• "),
            snapshot(target = 99, revision = 2, text = "• ").copy(editNormalized = true),
            snapshot(revision = 2, text = "• ").copy(editNormalized = true, paragraphStart = 4),
        )) {
            val session = listSession()
            session.result(11, reply, null)
            assertEquals("- ", session.state.text)
            assertTrue(session.state.paused)
        }
        val error = listSession()
        error.result(11, snapshot(text = "• ").copy(editNormalized = true), "unconfirmed")
        assertEquals("- ", error.state.text)
        assertTrue(error.state.paused)
    }

    @Test fun normalizedAckFlagIsOptionalAndParsedFromTheProtocol() {
        val json = JSONObject().put("target", 7).put("revision", 2).put("status", "editable")
            .put("text", "• ").put("selection_start", 2).put("selection_end", 2)
        assertFalse(RemoteTextSnapshot.parse(json)!!.editNormalized)
        json.put("edit_normalized", true)
        assertTrue(RemoteTextSnapshot.parse(json)!!.editNormalized)
    }

    @Test fun continuingBodyInputSurvivesTheDisappearingEmptyListFiller() {
        val session = listSession()
        session.result(11, snapshot(revision = 2, text = "• \n", start = 2).copy(editNormalized = true), null)
        session.localEdit("• 正\n", 3, 3, composing = false)
        session.begin(12, session.nextRequest()!!)
        session.localEdit("• 正文\n", 4, 4, composing = false)
        session.result(12, snapshot(revision = 3, text = "• 正", start = 3).copy(editNormalized = true), null)
        assertEquals("• 正文", session.state.text)
        assertEquals(4, session.state.selectionStart)
        assertEquals("• 正文", session.nextRequest()!!.text)
        assertFalse(session.state.paused)
    }

    @Test fun aStaleDesktopCaretCannotMoveThePhoneInsideTheGeneratedMarker() {
        val session = listSession()
        session.result(11, snapshot(revision = 2, text = "• ", start = 1).copy(editNormalized = true), null)
        assertEquals(2, session.state.selectionStart)
        assertEquals("text_read", session.nextRequest()!!.type)
        session.begin(12, session.nextRequest()!!)
        session.result(12, snapshot(revision = 2, text = "• ", start = 1), null)
        assertEquals(2, session.state.selectionStart)
        session.localEdit("• 正文", 4, 4, composing = false)
        assertEquals(4, session.nextRequest()!!.start)
    }

    @Test fun ordinaryMetadataRefreshesApplicationWithoutAdoptingTextAndSurvivesCommits() {
        val session = readySession()
        session.connection(true, true, sourceSupported = true)
        session.useOrdinaryInput(true)
        assertEquals("notepad", session.state.source)
        val source = session.nextRequest()!!
        assertEquals("text_source", source.type)
        assertEquals(setOf("type", "id"), source.json(90).keys().asSequence().toSet())
        session.begin(90, source)
        session.result(90, snapshot(target = 0, revision = 0, text = "不得显示的正文", status = "ordinary")
            .copy(source = "QQ", sourceIcon = "qq-icon", windowTitle = "聊天"), null)
        assertEquals("QQ", session.state.source)
        assertEquals("qq-icon", session.state.sourceIcon)
        assertEquals("聊天", session.state.windowTitle)
        assertEquals("", session.state.text)
        assertFalse(session.state.showEditor)
        assertFalse(session.state.editable)
        assertFalse(session.state.paused)

        session.reload() // Ordinary keyboard commit must not blank the header.
        assertEquals("QQ", session.state.source)
        assertEquals("text_source", session.nextRequest()!!.type)
        session.begin(91, session.nextRequest()!!)
        session.result(91, snapshot(status = "ordinary").copy(source = "Code"), null)
        assertEquals("Code", session.state.source)
        assertNull(session.state.sourceIcon)
        assertNull(session.state.windowTitle)
        session.begin(92, session.nextRequest()!!)
        session.result(92, null, "unconfirmed")
        assertFalse(session.state.paused)
        assertNull(session.state.error)

        session.begin(93, session.nextRequest()!!)
        session.useOrdinaryInput(false)
        session.result(93, snapshot(status = "ordinary").copy(source = "late-app"), null)
        assertEquals("Code", session.state.source)
        assertEquals("text_read", session.nextRequest()!!.type)
    }

    @Test fun ordinaryMetadataRequestsRequireReceiverSupportAndAnActiveConnection() {
        val session = readySession()
        session.useOrdinaryInput(true)
        assertNull(session.nextRequest())
        session.connection(true, true, sourceSupported = true)
        assertEquals("text_source", session.nextRequest()!!.type)
        session.connection(false, false)
        assertNull(session.nextRequest())
        session.connection(true, true)
        assertNull(session.nextRequest())
        assertTrue(session.state.ordinaryInput)
    }

    @Test fun manualOrdinaryInputCancelsLateSyncRepliesAndReadsAFreshTargetWhenReturning() {
        val session = readySession()
        session.begin(80, session.nextRequest()!!)
        assertNull(session.useOrdinaryInput(true))
        assertTrue(session.state.ordinaryInput)
        assertFalse(session.state.showEditor)
        assertFalse(session.state.editable)
        assertEquals("", session.state.text)
        assertNull(session.nextRequest())
        session.result(80, snapshot(target = 99, text = "late desktop text"), null)
        assertEquals("", session.state.text)
        assertTrue(session.state.ordinaryInput)

        assertNull(session.useOrdinaryInput(false))
        val read = session.nextRequest()!!
        assertEquals("text_read", read.type)
        session.begin(81, read)
        session.result(81, snapshot(target = 8, revision = 12, text = "新位置"), null)
        session.localEdit("新位置！", 4, 4, composing = false)
        val edit = session.nextRequest()!!
        assertEquals(8L, edit.target)
        assertEquals(12L, edit.revision)
        assertEquals("新位置！", edit.text)
    }

    @Test fun switchingToOrdinaryReturnsUnconfirmedPhoneEditsWithoutReplayingThem() {
        val session = readySession()
        session.localEdit("手机😀\n草稿", 7, 7, composing = false)
        val edit = session.nextRequest()!!
        session.begin(82, edit)
        assertEquals("手机😀\n草稿", session.useOrdinaryInput(true))
        assertEquals("", session.state.text)
        assertNull(session.nextRequest())
        session.result(82, snapshot(revision = 2, text = edit.text), null)
        assertTrue(session.state.ordinaryInput)
        assertEquals("", session.state.text)
        session.useOrdinaryInput(false)
        assertEquals("text_read", session.nextRequest()!!.type)

        val composing = readySession()
        composing.localEdit("你好拼音", 4, 4, composing = true)
        assertEquals("你好拼音", composing.useOrdinaryInput(true))
        assertFalse(composing.state.composing)
        assertNull(composing.nextRequest())
    }

    @Test fun ordinaryOverrideSurvivesCommitReloadAndConnectionLifecycleUntilUserSwitchesBack() {
        val session = readySession()
        session.useOrdinaryInput(true)
        session.reload()
        session.connection(false, false)
        session.connection(true, true)
        session.suspend()
        session.connection(true, false)
        session.connection(true, true)
        assertTrue(session.state.ordinaryInput)
        assertNull(session.nextRequest())
        session.requestEnter()
        assertNull(session.nextRequest())
        session.localEdit("不应进入同步", 0, 0, composing = false)
        assertEquals("", session.state.text)
        session.useOrdinaryInput(false)
        assertFalse(session.state.ordinaryInput)
        assertEquals("text_read", session.nextRequest()!!.type)
    }

    private fun snapshot(
        target: Long = 7,
        revision: Long = 1,
        text: String = "你好",
        status: String = "editable",
        start: Int? = text.length,
        end: Int? = start,
    ) = RemoteTextSnapshot(target, revision, status, "notepad", text, start, end)

    private fun readySession(clock: () -> Long = System::nanoTime): TextSyncSession {
        val session = TextSyncSession(clock = clock)
        session.connection(connected = true, supported = true)
        val request = session.nextRequest()!!
        assertEquals("text_read", request.type)
        session.begin(1, request)
        session.result(1, snapshot(), null)
        return session
    }

    @Test fun readAdoptsUnicodeSelectionAndBuildsFullTextEdit() {
        val session = readySession()
        assertEquals("你好", session.state.text)
        assertEquals(2, session.state.selectionStart)
        session.localEdit("你好😀", 4, 4, composing = false)

        val request = session.nextRequest()!!
        assertEquals("text_edit", request.type)
        assertEquals(7, request.target)
        assertEquals(1, request.revision)
        assertEquals("你好😀", request.text)
        assertEquals(4, request.start)
        assertEquals(4, request.end)
    }

    @Test fun editAckKeepsAnewerLocalEditInsteadOfOverwritingIt() {
        val session = readySession()
        session.localEdit("你好啊", 3, 3, composing = false)
        val request = session.nextRequest()!!
        session.begin(2, request)
        session.localEdit("你好啊！", 4, 4, composing = false)

        session.result(2, snapshot(revision = 2, text = "你好啊", start = 3, end = 3), null)
        assertEquals("你好啊！", session.state.text)
        assertTrue(session.state.dirty)
        assertFalse(session.state.paused)
        assertEquals("你好啊！", session.nextRequest()!!.text)
    }

    @Test fun mismatchedEditAckPausesAndPreservesLocalText() {
        val session = readySession()
        session.localEdit("手机文字", 4, 4, composing = false)
        val request = session.nextRequest()!!
        session.begin(3, request)

        session.result(3, snapshot(revision = 2, text = "电脑已经改了"), null)
        assertEquals("手机文字", session.state.text)
        assertTrue(session.state.paused)
        assertEquals("conflict", session.state.error)
        assertNull(session.nextRequest())
    }

    @Test fun movingOnlyThePhoneCaretDoesNotPauseWhenDesktopSelectionRaces() {
        val session = readySession()
        session.localEdit("你好", 1, 1, composing = false)
        val request = session.nextRequest()!!
        assertTrue(request.selectionOnly)
        session.begin(6, request)

        session.result(6, null, "conflict")
        assertFalse(session.state.paused)
        assertFalse(session.state.dirty)
        assertEquals(1, session.state.selectionStart)
        assertEquals("text_read", session.nextRequest()!!.type)
        session.begin(7, session.nextRequest()!!)
        session.result(7, snapshot(start = 2), null)
        assertEquals(1, session.state.selectionStart)
    }

    @Test fun lateReadCannotReplaceAPhoneCaretMove() {
        val session = readySession()
        session.begin(100, session.nextRequest()!!)
        session.localEdit("你好", 0, 1, composing = false)
        session.result(100, snapshot(start = 2), null)
        assertEquals(0, session.state.selectionStart)
        assertEquals(1, session.state.selectionEnd)
        assertTrue(session.nextRequest()!!.selectionOnly)
    }

    @Test fun richEditorCaretAckRetainsRequestedPositionDespiteCachedDesktopCaret() {
        val session = readySession()
        session.localEdit("你好", 0, 0, composing = false)
        session.begin(101, session.nextRequest()!!)
        session.result(101, snapshot(start = 2), null)
        assertEquals(0, session.state.selectionStart)
        assertFalse(session.state.paused)
        assertEquals("text_read", session.nextRequest()!!.type)
        session.begin(107, session.nextRequest()!!)
        session.result(107, snapshot(start = 2), null)
        assertEquals(0, session.state.selectionStart)
        session.begin(108, session.nextRequest()!!)
        session.result(108, snapshot(start = 0), null)
        session.begin(109, session.nextRequest()!!)
        session.result(109, snapshot(start = 1), null)
        assertEquals(1, session.state.selectionStart)
    }

    @Test fun caretOnlyDesktopRefreshRebasesCompositionWithoutPausingPhoneEdits() {
        val session = readySession()
        session.begin(102, session.nextRequest()!!)
        session.localEdit("你好ni", 4, 4, composing = true)
        session.result(102, snapshot(revision = 2, start = 0), null)
        assertFalse(session.state.paused)
        assertEquals("你好ni", session.state.text)
        session.localEdit("你好你", 3, 3, composing = false)
        assertEquals(2L, session.nextRequest()!!.revision)
    }

    @Test fun caretMoveDuringTextAckQueuesASelectionWithoutResendingText() {
        val session = readySession()
        session.localEdit("你好啊", 3, 3, composing = false)
        session.begin(103, session.nextRequest()!!)
        session.localEdit("你好啊", 1, 1, composing = false)
        session.result(103, snapshot(revision = 2, text = "你好啊"), null)
        assertFalse(session.state.dirty)
        assertEquals(1, session.state.selectionStart)
        assertTrue(session.nextRequest()!!.selectionOnly)
    }

    @Test fun explicitlyRejectedCaretRaceRetriesAgainstUnchangedTextButUnconfirmedWritePauses() {
        val session = readySession()
        session.localEdit("你好啊", 3, 3, composing = false)
        session.begin(104, session.nextRequest()!!)
        session.result(104, snapshot(revision = 2, start = 0), "conflict")
        assertFalse(session.state.paused)
        assertTrue(session.state.dirty)
        val retried = session.nextRequest()!!
        assertEquals(2L, retried.revision)
        assertEquals("你好啊", retried.text)
        session.begin(105, retried)
        session.result(105, snapshot(revision = 2, start = 0), "unconfirmed")
        assertTrue(session.state.paused)
        assertNull(session.nextRequest())
    }

    @Test fun independentBlockPreviewsNeverBecomePartOfTheEditPayload() {
        val session = readySession()
        session.begin(106, session.nextRequest()!!)
        session.result(106, snapshot().copy(contextBefore = "上一段", contextAfter = "下一段"), null)
        assertEquals("上一段", session.state.contextBefore)
        assertEquals("下一段", session.state.contextAfter)
        session.localEdit("你好啊", 3, 3, composing = false)
        assertEquals("你好啊", session.nextRequest()!!.text)
    }

    @Test fun disconnectDoesNotReplayAnUnconfirmedWriteAndReloadStartsFreshRead() {
        val session = readySession()
        session.localEdit("未确认", 3, 3, composing = false)
        val request = session.nextRequest()!!
        session.begin(4, request)
        session.connection(connected = false, supported = true)
        assertTrue(session.state.paused)
        assertEquals("未确认", session.state.text)
        assertNull(session.nextRequest())

        session.connection(connected = true, supported = true)
        assertNull(session.nextRequest())
        session.reload()
        assertFalse(session.state.paused)
        assertFalse(session.state.dirty)
        assertEquals("text_read", session.nextRequest()!!.type)
    }

    @Test fun unconfirmedComputerChangeAdoptsReturnedSnapshotWithoutPausing() {
        val session = readySession()
        val request = session.nextRequest()!!
        assertEquals("text_read", request.type)
        session.begin(8, request)

        session.result(8, snapshot(revision = 2, text = ""), "unconfirmed")

        assertFalse(session.state.paused)
        assertFalse(session.state.dirty)
        assertEquals("", session.state.text)
        assertNull(session.state.error)
    }

    @Test fun transientReadTimeoutRetriesWithoutShowingUnconfirmedWrite() {
        val session = readySession()
        val request = session.nextRequest()!!
        assertEquals("text_read", request.type)
        session.begin(9, request)

        session.result(9, null, "unconfirmed")

        assertFalse(session.state.paused)
        assertFalse(session.state.dirty)
        assertNull(session.state.error)
        assertEquals("text_read", session.nextRequest()!!.type)
    }

    @Test fun readTimeoutDuringPhoneTypingRequiresFreshReadWithoutPausingOrResending() {
        val session = readySession()
        session.begin(110, session.nextRequest()!!)
        session.localEdit("你好！", 3, 3, composing = false)
        session.result(110, null, "unconfirmed")
        assertFalse(session.state.paused)
        assertTrue(session.state.dirty)
        assertEquals("你好！", session.state.text)
        val retry = session.nextRequest()!!
        assertEquals("text_read", retry.type)
        session.begin(111, retry)
        session.result(111, snapshot(revision = 2, start = 1), null)
        val edit = session.nextRequest()!!
        assertEquals("text_edit", edit.type)
        assertEquals(2L, edit.revision)
        assertEquals("你好！", edit.text)
    }

    @Test fun unavailableReadDoesNotRetargetPendingPhoneTextToAnotherField() {
        val session = readySession()
        session.begin(112, session.nextRequest()!!)
        session.localEdit("手机草稿", 4, 4, composing = false)
        session.result(112, snapshot(target = 0, revision = 0, text = "", status = "unavailable"), "unconfirmed")
        assertFalse(session.state.paused)
        assertEquals("text_read", session.nextRequest()!!.type)
        session.begin(113, session.nextRequest()!!)
        session.result(113, snapshot(target = 9), null)
        assertTrue(session.state.paused)
        assertEquals("手机草稿", session.state.text)
        assertNull(session.nextRequest())
    }

    @Test fun readTimeoutKeepsImeCompositionUntilItFinishesThenReadsBeforeWriting() {
        val session = readySession()
        session.begin(114, session.nextRequest()!!)
        session.localEdit("你好ni", 4, 4, composing = true)
        session.result(114, null, "unconfirmed")
        assertFalse(session.state.paused)
        assertTrue(session.state.composing)
        assertNull(session.nextRequest())
        session.localEdit("你好你", 3, 3, composing = false)
        assertEquals("text_read", session.nextRequest()!!.type)
    }

    @Test fun failedCaretRequestKeepsTheOriginalTargetWhileNewPhoneTextIsPending() {
        val session = readySession()
        session.localEdit("你好", 1, 1, composing = false)
        session.begin(115, session.nextRequest()!!)
        session.localEdit("你！好", 2, 2, composing = false)
        session.result(115, null, "selection_unconfirmed")
        assertFalse(session.state.paused)
        assertEquals("text_read", session.nextRequest()!!.type)
        session.begin(116, session.nextRequest()!!)
        session.result(116, snapshot(target = 9), null)
        assertTrue(session.state.paused)
        assertEquals("你！好", session.state.text)
        assertNull(session.nextRequest())
    }

    @Test fun desktopCompositionContinuesPollingAndRestoresEditingAfterCommit() {
        val session = readySession()
        val composingRead = session.nextRequest()!!
        session.begin(10, composingRead)
        session.result(10, snapshot(revision = 2, text = "nihao", status = "composing", start = null, end = null), null)

        assertFalse(session.state.editable)
        assertFalse(session.state.composing)
        val committedRead = session.nextRequest()!!
        assertEquals("text_read", committedRead.type)
        session.begin(11, committedRead)
        session.result(11, snapshot(revision = 3, text = "你好", start = null, end = null), null)

        assertTrue(session.state.editable)
        assertFalse(session.state.paused)
        assertEquals("已同步", session.state.message)
        assertEquals("你好", session.state.text)
        session.localEdit("你好啊", 3, 3, composing = false)
        assertEquals("text_edit", session.nextRequest()!!.type)
    }

    @Test fun leavingAnInputClearsSyncedTextAndKeepsPollingWithoutAConflict() {
        val session = readySession()
        session.begin(40, session.nextRequest()!!)
        session.result(40, snapshot(target = 8, revision = 2, text = "", status = "no_input", start = null, end = null)
            .copy(source = "codex-switcher", sourceIcon = "app-icon", windowTitle = "Codex Switcher"), null)

        assertEquals("no_input", session.state.status)
        assertEquals("请先在电脑上选中输入框", session.state.message)
        assertFalse(session.state.showEditor)
        assertFalse(session.state.editable)
        assertFalse(session.state.paused)
        assertFalse(session.state.dirty)
        assertEquals("", session.state.text)
        assertEquals("codex-switcher", session.state.source)
        assertEquals("app-icon", session.state.sourceIcon)
        assertEquals("Codex Switcher", session.state.windowTitle)
        assertEquals("text_read", session.nextRequest()!!.type)
    }

    @Test fun returningToAnInputResumesSyncAutomatically() {
        val session = readySession()
        session.begin(41, session.nextRequest()!!)
        session.result(41, snapshot(target = 8, revision = 2, text = "", status = "no_input"), null)
        session.begin(42, session.nextRequest()!!)
        session.result(42, snapshot(target = 9, revision = 3, text = "重新选中的文字"), null)

        assertTrue(session.state.showEditor)
        assertTrue(session.state.editable)
        assertFalse(session.state.paused)
        assertEquals("已同步", session.state.message)
        assertEquals("重新选中的文字", session.state.text)
        val editedText = "重新选中的文字！"
        session.localEdit(editedText, editedText.length, editedText.length, composing = false)
        val edit = session.nextRequest()!!
        assertEquals("text_edit", edit.type)
        assertEquals(9, edit.target)
        assertEquals(editedText, edit.text)
    }

    @Test fun losingComputerInputFocusPreservesPendingPhoneText() {
        val session = readySession()
        session.begin(43, session.nextRequest()!!)
        session.localEdit("手机未发送的文字", 8, 8, composing = false)
        session.result(43, snapshot(target = 8, revision = 2, text = "", status = "no_input"), null)

        assertEquals("手机未发送的文字", session.state.text)
        assertTrue(session.state.showEditor)
        assertTrue(session.state.dirty)
        assertTrue(session.state.paused)
        assertEquals("conflict", session.state.error)
        assertEquals("电脑内容或输入位置已变化，手机修改已保留", session.state.message)
        assertNull(session.nextRequest())
    }

    @Test fun messagesDistinguishInputFocusFromSyncSupportAndReadFailures() {
        val state = TextSyncUiState(connected = true, supported = true)
        assertEquals("请先在电脑上选中输入框", state.copy(status = "no_input").message)
        assertEquals("普通输入 · 此输入框无法同步文字", state.copy(status = "unsupported").message)
        assertEquals("普通输入 · 无法读取当前段落", state.copy(status = "too_long").message)
        assertEquals("暂时无法读取电脑输入状态", state.copy(status = "unavailable").message)
        assertEquals("正在读取电脑输入状态…", state.message)
        assertEquals("普通实时输入 · 接收端未提供文字同步", state.copy(supported = false).message)
    }

    @Test fun unsupportedFieldsUseOrdinaryInputAndResumeSyncOnAReadableField() {
        for (status in listOf("unsupported", "too_long", "unavailable")) {
            val session = readySession()
            session.begin(50, session.nextRequest()!!)
            session.result(50, snapshot(target = 8, revision = 2, text = "", status = status, start = null, end = null)
                .copy(source = "Code", sourceIcon = "code-icon", windowTitle = "file.rs - Visual Studio Code"), null)

            assertFalse(session.state.showEditor)
            assertFalse(session.state.editable)
            assertFalse(session.state.paused)
            assertFalse(session.state.dirty)
            assertEquals("", session.state.text)
            assertEquals("Code", session.state.source)
            assertEquals("code-icon", session.state.sourceIcon)
            assertEquals("file.rs - Visual Studio Code", session.state.windowTitle)
            session.localEdit("不得作为同步替换写入", 0, 0, composing = false)
            assertEquals("text_read", session.nextRequest()!!.type)

            session.begin(51, session.nextRequest()!!)
            session.result(51, snapshot(target = 9, revision = 3, text = "可读取的当前段落")
                .copy(paragraphStart = 301, paragraphNumber = 2), null)
            assertTrue(session.state.editable)
            assertTrue(session.state.showEditor)
            assertFalse(session.state.paused)
            assertEquals("可读取的当前段落", session.state.text)
            assertEquals(301L, session.state.paragraphStart)
        }
    }

    @Test fun enterIsQueuedAfterTextEditAndClearedOnlyAfterAck() {
        val session = readySession()
        session.requestEnter()
        val request = session.nextRequest()!!
        assertEquals("text_enter", request.type)
        session.begin(5, request)
        assertNull(session.nextRequest())
        session.result(5, snapshot(revision = 2, text = "你好\n", start = 3, end = 3), null)
        // The acknowledged Enter is not replayed; the next poll is an ordinary read.
        assertEquals("text_read", session.nextRequest()!!.type)
    }

    @Test fun parserRejectsSelectionInsideSurrogatePair() {
        val json = JSONObject().put("target", 1).put("revision", 1).put("status", "editable")
            .put("source", "notepad").put("text", "A😀B")
            .put("selection_start", 2).put("selection_end", 2)
        assertNull(RemoteTextSnapshot.parse(json))
    }

    @Test fun sourceIconFollowsApplicationAndMissingIconClearsPreviousOne() {
        val session = readySession()
        val read = session.nextRequest()!!
        session.begin(20, read)
        session.result(20, snapshot(revision = 2).copy(source = "ChatGPT", sourceIcon = "first-icon"), null)
        assertEquals("ChatGPT", session.state.source)
        assertEquals("first-icon", session.state.sourceIcon)
        session.begin(21, session.nextRequest()!!)
        session.result(21, snapshot(target = 8, revision = 3).copy(source = "notepad"), null)
        assertEquals("notepad", session.state.source)
        assertNull(session.state.sourceIcon)
    }

    @Test fun parserSupportsOldSnapshotsAndIgnoresOversizedIconWithoutLosingText() {
        val json = JSONObject().put("target", 1).put("revision", 1).put("status", "editable")
            .put("source", "ChatGPT").put("text", "文字")
            .put("selection_start", 2).put("selection_end", 2)
        assertNull(RemoteTextSnapshot.parse(json)!!.sourceIcon)
        json.put("source_icon", "small-icon")
        assertEquals("small-icon", RemoteTextSnapshot.parse(json)!!.sourceIcon)
        json.put("source_icon", "x".repeat(MAX_SOURCE_ICON_BASE64 + 1))
        val snapshot = RemoteTextSnapshot.parse(json)!!
        assertEquals("文字", snapshot.text)
        assertNull(snapshot.sourceIcon)
    }

    @Test fun titleUpdatesIndependentlyAndDoesNotDiscardPendingPhoneText() {
        val session = readySession()
        session.begin(30, session.nextRequest()!!)
        session.localEdit("你好啊", 3, 3, composing = false)
        session.result(30, snapshot().copy(windowTitle = "*会议安排 - Notepad"), null)
        assertFalse(session.state.paused)
        assertTrue(session.state.dirty)
        assertEquals("你好啊", session.state.text)
        session.begin(31, session.nextRequest()!!)
        session.result(31, snapshot(revision = 2, text = "你好啊").copy(windowTitle = "*会议安排 - Notepad"), null)
        assertEquals("*会议安排 - Notepad", session.state.windowTitle)
        assertFalse(session.state.dirty)
        session.begin(32, session.nextRequest()!!)
        session.result(32, snapshot(target = 8, revision = 3).copy(source = "ChatGPT"), null)
        assertNull(session.state.windowTitle)
    }

    @Test fun optionalTitleIsBackwardCompatibleAndBoundedWithoutDroppingText() {
        val json = JSONObject().put("target", 1).put("revision", 1).put("status", "editable")
            .put("source", "notepad").put("text", "文字")
            .put("selection_start", 2).put("selection_end", 2)
        assertNull(RemoteTextSnapshot.parse(json)!!.windowTitle)
        json.put("window_title", "会议安排 - Notepad")
        assertEquals("会议安排 - Notepad", RemoteTextSnapshot.parse(json)!!.windowTitle)
        json.put("window_title", "文".repeat(MAX_WINDOW_TITLE_UNITS + 1))
        val snapshot = RemoteTextSnapshot.parse(json)!!
        assertEquals("文字", snapshot.text)
        assertNull(snapshot.windowTitle)
    }

    @Test fun movingBetweenIdenticalParagraphsPreservesAnUnsentPhoneEdit() {
        val session = readySession()
        session.begin(50, session.nextRequest()!!)
        session.result(50, snapshot(text = "相同段落").copy(paragraphStart = 100, paragraphNumber = 2), null)
        session.begin(51, session.nextRequest()!!)
        session.localEdit("相同段落！", 5, 5, composing = false)
        session.result(51, snapshot(revision = 2, text = "相同段落").copy(paragraphStart = 200, paragraphNumber = 3), null)
        assertTrue(session.state.paused)
        assertEquals("相同段落！", session.state.text)
        assertEquals(100L, session.state.paragraphStart)
        assertEquals("conflict", session.state.error)
    }

    @Test fun newRevisionWithIdenticalVisibleTextPreservesPhoneEditsInsteadOfChangingTheirTarget() {
        val session = readySession()
        session.begin(60, session.nextRequest()!!)
        session.localEdit("你好！", 3, 3, composing = false)
        session.result(60, snapshot(revision = 2), null)
        assertTrue(session.state.paused)
        assertTrue(session.state.dirty)
        assertEquals("你好！", session.state.text)
        assertEquals("conflict", session.state.error)
        assertNull(session.nextRequest())
    }

    @Test fun paragraphSelectionFollowsDesktopAfterAckAndClearsOnWholeDocument() {
        val session = readySession()
        session.begin(52, session.nextRequest()!!)
        session.result(52, snapshot(text = "段落一").copy(paragraphStart = 100, paragraphNumber = 2), null)
        assertEquals(2, session.state.paragraphNumber)
        session.begin(53, session.nextRequest()!!)
        session.result(53, snapshot(revision = 2, text = "段落二").copy(paragraphStart = 200, paragraphNumber = 3), null)
        assertEquals("段落二", session.state.text)
        assertFalse(session.state.paused)
        session.begin(54, session.nextRequest()!!)
        session.result(54, snapshot(target = 8, text = "短文"), null)
        assertNull(session.state.paragraphStart)
        assertNull(session.state.paragraphNumber)
    }

    @Test fun pagedSnapshotsPreserveOriginAndRejectInvalidLineNumbers() {
        val json = JSONObject().put("target", 7).put("revision", 1).put("status", "editable")
            .put("text", "你好").put("selection_start", 2).put("selection_end", 2)
            .put("accessibility_page_start_line", 100)
        assertEquals(100L, RemoteTextSnapshot.parse(json)!!.accessibilityPageStartLine)
        for (line in listOf(0, -1)) {
            json.put("accessibility_page_start_line", line)
            assertNull(RemoteTextSnapshot.parse(json))
        }
    }

    @Test fun identicalShortPagesNeverRebaseAnOldEditOntoANewPage() {
        for (type in listOf("text_read", "text_edit")) {
            val session = readySession()
            session.begin(200, session.nextRequest()!!)
            session.result(200, snapshot().copy(accessibilityPageStartLine = 100), null)
            if (type == "text_read") session.begin(201, session.nextRequest()!!)
            session.localEdit("你好！", 3, 3, composing = false)
            if (type == "text_edit") session.begin(201, session.nextRequest()!!)
            session.result(201, snapshot(revision = 2, start = 0)
                .copy(accessibilityPageStartLine = 200), if (type == "text_edit") "conflict" else null)
            assertTrue(session.state.paused)
            assertEquals("你好！", session.state.text)
            assertEquals(100L, session.state.accessibilityPageStartLine)
            assertNull(session.nextRequest())
        }
    }

    @Test fun pagedCaretChangesDoNotExplainDocumentRevisionConflicts() {
        val session = readySession()
        session.begin(202, session.nextRequest()!!)
        session.result(202, snapshot().copy(accessibilityPageStartLine = 100), null)
        session.localEdit("你好！", 3, 3, composing = false)
        session.begin(203, session.nextRequest()!!)
        session.result(203, snapshot(revision = 2, start = 0)
            .copy(accessibilityPageStartLine = 100), "conflict")
        assertTrue(session.state.paused)
        assertNull(session.nextRequest())
    }

    @Test fun desktopCaretResumesAfterThePhoneCaretHoldExpires() {
        var now = 0L
        val session = readySession { now }
        session.localEdit("你好", 0, 0, composing = false)
        session.begin(204, session.nextRequest()!!)
        session.result(204, snapshot(start = 2), null)
        now += PHONE_CARET_HOLD_NANOS / 2
        session.begin(205, session.nextRequest()!!)
        session.result(205, snapshot(revision = 2, start = 1), null)
        assertEquals(0, session.state.selectionStart)
        now += PHONE_CARET_HOLD_NANOS
        session.begin(206, session.nextRequest()!!)
        session.result(206, snapshot(revision = 2, start = 1), null)
        assertEquals(1, session.state.selectionStart)
        assertFalse(session.state.paused)
    }

    @Test fun computerEnterWaitsForAllPhoneEditsAndUsesTheAcknowledgedRevision() {
        val session = readySession()
        session.localEdit("你好啊", 3, 3, composing = false)
        session.requestEnter()
        session.begin(207, session.nextRequest()!!)
        session.localEdit("你好啊！", 4, 4, composing = false)
        session.result(207, snapshot(revision = 2, text = "你好啊"), null)
        val secondEdit = session.nextRequest()!!
        assertEquals("text_edit", secondEdit.type)
        session.begin(208, secondEdit)
        session.result(208, snapshot(revision = 3, text = "你好啊！"), null)
        val enter = session.nextRequest()!!
        assertEquals("text_enter", enter.type)
        assertEquals(3L, enter.revision)
        session.begin(209, enter)
        session.result(209, snapshot(target = 8, revision = 4, text = "", status = "no_input"), null)
        assertFalse(session.state.paused)
        assertEquals("text_read", session.nextRequest()!!.type)
    }
}
