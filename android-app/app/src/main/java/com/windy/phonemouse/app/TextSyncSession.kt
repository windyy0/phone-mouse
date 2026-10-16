package com.windy.phonemouse.app

import org.json.JSONObject

data class RemoteTextSnapshot(
    val target: Long, val revision: Long, val status: String, val source: String,
    val text: String, val selectionStart: Int?, val selectionEnd: Int?,
    val sourceIcon: String? = null,
    val windowTitle: String? = null,
    val paragraphStart: Long? = null,
    val paragraphNumber: Int? = null,
    val contextBefore: String? = null,
    val contextAfter: String? = null,
    val accessibilityPageStartLine: Long? = null,
    val editNormalized: Boolean = false,
    val editCaret: Int? = null,
    val independentBlock: Boolean = false,
    val contentRevision: Long? = null,
) {
    internal val scope get() = RemoteTextScope(target, paragraphStart, accessibilityPageStartLine)
    companion object {
        fun parse(json: JSONObject): RemoteTextSnapshot? = runCatching {
            val text = json.getString("text")
            require(text.length <= MAX_SYNC_TEXT_UNITS)
            fun selection(name: String) = if (json.isNull(name)) null else json.getInt(name).also {
                require(isTextBoundary(text, it))
            }
            RemoteTextSnapshot(json.getLong("target"), json.getLong("revision"), json.getString("status"),
                json.optString("source"), text, selection("selection_start"), selection("selection_end"),
                json.optString("source_icon").takeIf { !json.isNull("source_icon") && it.isNotEmpty() && it.length <= MAX_SOURCE_ICON_BASE64 },
                json.optString("window_title").takeIf { !json.isNull("window_title") && it.isNotBlank() && it.length <= MAX_WINDOW_TITLE_UNITS },
                if (json.isNull("paragraph_start")) null else json.getLong("paragraph_start").also { require(it >= 0) },
                if (json.isNull("paragraph_number")) null else json.getInt("paragraph_number").also { require(it > 0) },
                json.optString("context_before").takeIf { !json.isNull("context_before") && it.length <= 1_000 },
                json.optString("context_after").takeIf { !json.isNull("context_after") && it.length <= 1_000 },
                if (json.isNull("accessibility_page_start_line")) null else
                    json.getLong("accessibility_page_start_line").also { require(it > 0) },
                json.optBoolean("edit_normalized", false), selection("edit_caret"), json.optBoolean("independent_block", false),
                if (json.isNull("content_revision")) null else json.getLong("content_revision").also { require(it > 0) })
        }.getOrNull()
    }
}

const val MAX_SYNC_TEXT_UNITS = 16_000
internal const val MAX_SOURCE_ICON_BASE64 = 16_384
internal const val MAX_WINDOW_TITLE_UNITS = 512
internal const val PHONE_CARET_HOLD_NANOS = 1_000_000_000L
internal data class RemoteTextScope(val target: Long, val paragraphStart: Long?, val pageStartLine: Long?)
internal fun isTextBoundary(text: String, offset: Int) = offset in 0..text.length &&
    !(offset > 0 && offset < text.length && Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset]))

data class TextSyncUiState(
    val supported: Boolean = false,
    val connected: Boolean = false,
    val status: String = "loading",
    val source: String = "",
    val sourceIcon: String? = null,
    val windowTitle: String? = null,
    val paragraphStart: Long? = null,
    val paragraphNumber: Int? = null,
    val accessibilityPageStartLine: Long? = null,
    val contextBefore: String? = null,
    val contextAfter: String? = null,
    val text: String = "",
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val dirty: Boolean = false,
    val paused: Boolean = false,
    val composing: Boolean = false,
    val waiting: Boolean = false,
    val error: String? = null,
    val ordinaryInput: Boolean = false,
    val sourceSupported: Boolean = false,
) {
    val showEditor get() = !ordinaryInput && (status in listOf("editable", "read_only", "composing") || dirty || paused)
    val editable get() = !ordinaryInput && connected && status == "editable" && !paused
    val message get() = when {
        !connected -> "连接已暂停，手机文字已保留"
        ordinaryInput -> "普通实时输入"
        paused -> when (error) {
            "conflict" -> "电脑内容或输入位置已变化，手机修改已保留"
            "linebreak_unsupported" -> "此输入框暂不支持同步换行，请使用电脑回车"
            "range_unsupported" -> "此输入框暂不支持该选区，手机修改已保留"
            else -> "同步结果未确认，手机修改已保留"
        }
        status == "read_only" -> "当前文字仅可查看"
        status == "composing" -> "电脑输入法正在选词，手机暂不修改"
        composing -> "选词完成后同步"
        dirty -> "同步中…"
        status == "editable" -> "已同步"
        status == "password" -> "密码框不回传文字 · 普通输入"
        status == "too_long" -> "普通输入 · 无法读取当前段落"
        !supported -> "普通实时输入 · 接收端未提供文字同步"
        status == "no_input" -> "请先在电脑上选中输入框"
        status == "loading" -> "正在读取电脑输入状态…"
        status == "unavailable" -> "暂时无法读取电脑输入状态"
        else -> "普通输入 · 此输入框无法同步文字"
    }
}

internal data class TextSyncRequest(
    val type: String, val target: Long = 0, val revision: Long = 0,
    val text: String = "", val start: Int = 0, val end: Int = 0, val localVersion: Long = 0,
    val selectionOnly: Boolean = false,
) {
    fun json(id: Long): JSONObject = JSONObject().put("type", type).put("id", id).apply {
        if (type == "text_edit" || type == "text_enter" || type == "text_shift_enter") { put("target", target); put("revision", revision) }
        if (type == "text_edit") { put("text", text); put("selection_start", start); put("selection_end", end) }
    }
}

private fun TextSyncUiState.withSnapshotMetadata(snapshot: RemoteTextSnapshot) = copy(
    status = snapshot.status,
    source = snapshot.source,
    sourceIcon = snapshot.sourceIcon,
    windowTitle = snapshot.windowTitle,
    paragraphStart = snapshot.paragraphStart,
    paragraphNumber = snapshot.paragraphNumber,
    accessibilityPageStartLine = snapshot.accessibilityPageStartLine,
    contextBefore = snapshot.contextBefore,
    contextAfter = snapshot.contextAfter,
)

private fun RemoteTextSnapshot.isCaretRefreshOf(previous: RemoteTextSnapshot?) = previous != null &&
    scope == previous.scope && status == previous.status && text == previous.text &&
    independentBlock == previous.independentBlock &&
    paragraphStart == null && previous.paragraphStart == null &&
    // A paged provider's revision also covers document identity and page
    // anchors that cannot be explained by selection offsets alone.
    accessibilityPageStartLine == null && previous.accessibilityPageStartLine == null &&
    if (contentRevision != null || previous.contentRevision != null) {
        // The receiver observed every content/target change. Caret caches can
        // disappear or return to their old offsets without changing that text.
        contentRevision != null && contentRevision == previous.contentRevision
    } else {
        selectionStart != null && selectionEnd != null && previous.selectionStart != null && previous.selectionEnd != null &&
            (selectionStart != previous.selectionStart || selectionEnd != previous.selectionEnd)
    }

private data class EnterIntent(var request: TextSyncRequest, var prepared: Boolean)
private data class InputBuffer(val text: String, val start: Int, val end: Int)

/** One request at a time. Unacknowledged writes are retained, never replayed. */
internal class TextSyncSession(
    private val clock: () -> Long = System::nanoTime,
    private val diagnose: (String) -> Unit = {},
) {
    var state = TextSyncUiState()
        private set
    private var remote: RemoteTextSnapshot? = null
    private var localVersion = 0L
    private var selectionDirty = false
    private var phoneCaretAnchor: RemoteTextSnapshot? = null
    private var phoneCaretExpiresAt = 0L
    private var pending: Pair<Long, TextSyncRequest>? = null
    private val enterRequests = ArrayDeque<EnterIntent>()
    private var caretConflictRetries = 0
    private var readRetryRequired = false
    private var deferredNormalization: Pair<TextSyncRequest, RemoteTextSnapshot>? = null
    private var deferredEnter: Pair<TextSyncRequest, RemoteTextSnapshot>? = null

    fun connection(connected: Boolean, supported: Boolean, sourceSupported: Boolean = false) {
        if (state.connected == connected && state.supported == supported && state.sourceSupported == sourceSupported) return
        if (!connected) suspend()
        state = state.copy(connected = connected, supported = supported, sourceSupported = sourceSupported,
            status = if (connected && !state.paused) "loading" else state.status)
        if (connected && !state.paused) remote = null
    }

    fun localEdit(text: String, start: Int, end: Int, composing: Boolean) {
        val wasPaused = state.paused
        val previous = remote
        applyLocalEdit(text, start, end, composing)
        reportPause(wasPaused, "local_edit", previous, null, remote, state.error)
    }

    private fun applyLocalEdit(text: String, start: Int, end: Int, composing: Boolean) {
        if (!state.editable || text.length > MAX_SYNC_TEXT_UNITS || !isTextBoundary(text, start) || !isTextBoundary(text, end)) return
        val textChanged = state.text != text
        val selectionChanged = state.selectionStart != start || state.selectionEnd != end
        val changed = textChanged || selectionChanged
        if (changed) localVersion++
        if (textChanged) { selectionDirty = false; phoneCaretAnchor = null; caretConflictRetries = 0 }
        else if (selectionChanged) {
            selectionDirty = true
            phoneCaretAnchor = remote
            phoneCaretExpiresAt = clock() + PHONE_CARET_HOLD_NANOS
        }
        state = state.copy(text = text, selectionStart = start, selectionEnd = end,
            composing = composing, dirty = state.dirty || textChanged)
        if (!composing) deferredNormalization?.let { (request, snapshot) ->
            deferredNormalization = null
            acceptNormalization(request, snapshot)
        }
        if (!composing) deferredEnter?.let { (request, snapshot) ->
            deferredEnter = null
            acceptEnter(request, snapshot)
        }
    }

    fun nextRequest(): TextSyncRequest? {
        if (!state.connected || pending != null) return null
        if (state.ordinaryInput) return if (state.sourceSupported) TextSyncRequest("text_source") else null
        if (!state.supported || state.paused || (state.composing && enterRequests.isEmpty()) ||
            deferredEnter != null || deferredNormalization != null) return null
        if (readRetryRequired) return TextSyncRequest("text_read", localVersion = localVersion)
        val snapshot = remote
        enterRequests.firstOrNull()?.let { intent ->
            if (snapshot != null && state.status == "editable") {
                // Freeze the pre-key text and caret. Later phone typing belongs
                // after Enter, even if an earlier text write is still in flight.
                return intent.request.copy(target = snapshot.target, revision = snapshot.revision,
                    type = if (intent.prepared) intent.request.type else "text_edit",
                    selectionOnly = !intent.prepared && snapshot.text == intent.request.text)
            }
        }
        if (state.dirty && snapshot != null && state.status == "editable") return TextSyncRequest("text_edit",
            snapshot.target, snapshot.revision, state.text, state.selectionStart, state.selectionEnd, localVersion)
        if (selectionDirty && snapshot != null && state.status == "editable") return TextSyncRequest("text_edit",
            snapshot.target, snapshot.revision, state.text, state.selectionStart, state.selectionEnd, localVersion,
            selectionOnly = true)
        return TextSyncRequest("text_read", localVersion = localVersion)
    }
    fun begin(id: Long, request: TextSyncRequest) {
        check(pending == null)
        pending = id to request
        state = state.copy(waiting = true)
    }
    private fun requestEnter(type: String) {
        if (state.editable && !state.composing) enterRequests.addLast(EnterIntent(
            TextSyncRequest(type, text = state.text, start = state.selectionStart,
                end = state.selectionEnd, localVersion = localVersion), !state.dirty && !selectionDirty))
    }
    fun requestEnter() = requestEnter("text_enter")
    fun requestShiftEnter() = requestEnter("text_shift_enter")

    private fun confirmEnterPreparation(request: TextSyncRequest) {
        enterRequests.firstOrNull()?.takeIf {
            it.request.localVersion == request.localVersion && it.request.text == request.text &&
                it.request.start == request.start && it.request.end == request.end
        }?.prepared = true
    }

    fun result(id: Long, snapshot: RemoteTextSnapshot?, error: String?) {
        val wasPaused = state.paused
        val previous = remote
        val request = pending?.takeIf { it.first == id }?.second
        applyResult(id, snapshot, error)
        reportPause(wasPaused, "reply", previous, request, snapshot, error)
    }

    private fun reportPause(wasPaused: Boolean, phase: String, previous: RemoteTextSnapshot?,
        request: TextSyncRequest?, snapshot: RemoteTextSnapshot?, replyError: String?) {
        if (wasPaused || !state.paused) return
        // No user text, window titles or app names enter diagnostic logs.
        diagnose("pause phase=$phase request=${request?.type} error=${state.error} replyError=$replyError " +
            "target=${previous?.target}->${snapshot?.target} revision=${previous?.revision}->${snapshot?.revision} " +
            "contentRevision=${previous?.contentRevision}->${snapshot?.contentRevision} " +
            "status=${previous?.status}->${snapshot?.status} textChanged=${previous?.text != snapshot?.text} " +
            "caret=${previous?.selectionStart}:${previous?.selectionEnd}->${snapshot?.selectionStart}:${snapshot?.selectionEnd} " +
            "scopeChanged=${previous?.scope != snapshot?.scope} block=${previous?.independentBlock}->${snapshot?.independentBlock} " +
            "dirty=${state.dirty} composing=${state.composing} localVersion=$localVersion")
    }

    private fun applyResult(id: Long, snapshot: RemoteTextSnapshot?, error: String?) {
        val request = pending?.takeIf { it.first == id }?.second ?: return
        pending = null
        state = state.copy(waiting = false)
        if (request.type == "text_source") {
            // Metadata never adopts desktop text or changes the local input state.
            if (state.ordinaryInput && snapshot != null && error == null) {
                state = state.copy(source = snapshot.source, sourceIcon = snapshot.sourceIcon, windowTitle = snapshot.windowTitle)
            }
            return
        }
        // A failed read has not attempted a replacement. Keep its original
        // target and local IME text, and require a successful fresh read before
        // any write. An unavailable placeholder snapshot cannot be a new target.
        if (request.type == "text_read" && (snapshot == null ||
                (snapshot.status == "unavailable" && (error != null || state.dirty || state.composing)))) {
            readRetryRequired = true
            state = state.copy(error = null)
            return
        }
        if (request.type == "text_read") readRetryRequired = false
        if (request.selectionOnly) {
            if (localVersion == request.localVersion) selectionDirty = false
            if (error != null || snapshot == null) {
                // A phone caret move is advisory. A desktop selection race must
                // not disable the editor or hide the Android IME.
                readRetryRequired = true
                state = state.copy(error = null)
                return
            }
            confirmEnterPreparation(request)
            val previousScope = remote?.scope
            if (snapshot.target != remote?.target || snapshot.status != "editable") enterRequests.clear()
            remote = snapshot
            if (!state.dirty && !state.composing && localVersion == request.localVersion) {
                // A native rich provider may return a stale cached caret even
                // after accepting Select(). Keep the deliberate phone position.
                val sameScope = snapshot.target == request.target && snapshot.text == request.text &&
                    snapshot.scope == previousScope
                if (sameScope && phoneCaretAnchor != null) phoneCaretExpiresAt = clock() + PHONE_CARET_HOLD_NANOS
                val start = if (sameScope) request.start else snapshot.selectionStart
                    ?: state.selectionStart.coerceAtMost(snapshot.text.length)
                val end = if (sameScope) request.end else snapshot.selectionEnd ?: start
                state = state.withSnapshotMetadata(snapshot).copy(
                    text = snapshot.text, selectionStart = start, selectionEnd = end, error = null)
            } else {
                state = state.withSnapshotMetadata(snapshot).copy(error = null)
            }
            return
        }
        if (error != null || snapshot == null) {
            // A cached desktop caret may catch up between the preceding read
            // and this write. Rebase only an explicitly rejected replacement
            // whose target, scope and original text still match exactly.
            // Unconfirmed writes and real content changes are never replayed.
            if (request.type == "text_edit" && error == "conflict" && snapshot != null &&
                snapshot.target == request.target && snapshot.status == "editable" &&
                snapshot.isCaretRefreshOf(remote) &&
                caretConflictRetries < 2) {
                caretConflictRetries++
                remote = snapshot
                state = state.copy(error = null)
                return
            }
            // A computer-side Enter or focus transition can make the target
            // change while a read is in flight. If the phone has no pending
            // local edit, the returned snapshot is authoritative and should
            // replace the stale view instead of trapping it in recovery mode.
            if (snapshot != null && !state.dirty && !state.composing) {
                remote = snapshot
                enterRequests.clear()
                val start = snapshot.selectionStart ?: if (snapshot.text == state.text) {
                    state.selectionStart.coerceAtMost(snapshot.text.length)
                } else snapshot.text.length
                val end = snapshot.selectionEnd ?: start
                state = state.withSnapshotMetadata(snapshot).copy(
                    text = snapshot.text,
                    selectionStart = start,
                    selectionEnd = end,
                    dirty = false,
                    paused = false,
                    error = null,
                )
                return
            }
            enterRequests.clear()
            state = state.copy(paused = true, error = error ?: "unconfirmed",
                dirty = state.dirty || request.type == "text_edit")
            return
        }
        if (request.type == "text_edit") {
            if (snapshot.target != request.target || snapshot.status != "editable" ||
                (snapshot.text != request.text && (!snapshot.editNormalized || snapshot.scope != remote?.scope))) {
                state = state.copy(paused = true, dirty = true, error = "conflict")
                return
            }
            confirmEnterPreparation(request)
            if (snapshot.editNormalized && snapshot.text != request.text) {
                // Let the IME finish against its existing buffer before changing
                // the generated prefix. The subsequent commit is rebased once.
                if (state.composing) {
                    deferredNormalization = request to snapshot
                    remote = snapshot
                    state = state.withSnapshotMetadata(snapshot).copy(error = null)
                } else acceptNormalization(request, snapshot)
                return
            }
            remote = snapshot
            caretConflictRetries = 0
            // Preserve edits made while this particular revision was in flight.
            if (localVersion == request.localVersion) selectionDirty = false
            state = state.withSnapshotMetadata(snapshot).copy(dirty = state.text != request.text, error = null)
            return
        }
        if (request.type == "text_enter" || request.type == "text_shift_enter") {
            enterRequests.removeFirst()
            val nextBlock = snapshot.independentBlock && remote?.independentBlock == true &&
                snapshot.source == remote?.source && snapshot.windowTitle == remote?.windowTitle
            if ((snapshot.target != request.target && !nextBlock) || snapshot.status != "editable") enterRequests.clear()
            // A block editor may create a new focused element for Enter. Keep
            // the next IME candidate in its original buffer until it commits,
            // then insert only that post-key text at the acknowledged caret.
            if (state.composing) deferredEnter = request to snapshot
            else acceptEnter(request, snapshot)
            return
        }
        val old = remote
        if (snapshot.target != old?.target || snapshot.status != "editable") enterRequests.clear()
        if ((state.dirty || state.composing) && old != null &&
            (snapshot.scope != old.scope || snapshot.text != old.text || snapshot.status != old.status ||
                (snapshot.revision != old.revision && !snapshot.isCaretRefreshOf(old)))) {
            state = state.copy(paused = true, error = "conflict")
            return
        }
        remote = snapshot
        if (!state.dirty && !state.composing) {
            if (clock() >= phoneCaretExpiresAt) phoneCaretAnchor = null
            val anchoredCaret = phoneCaretAnchor?.let { anchor ->
                snapshot.scope == anchor.scope && snapshot.text == anchor.text
            } == true
            val retainSelection = snapshot.scope == old?.scope && snapshot.text == state.text &&
                (selectionDirty || localVersion != request.localVersion) || anchoredCaret
            val start = if (retainSelection) state.selectionStart else snapshot.selectionStart
                ?: if (snapshot.text == state.text) state.selectionStart.coerceAtMost(snapshot.text.length) else snapshot.text.length
            val end = if (retainSelection) state.selectionEnd else snapshot.selectionEnd ?: start
            if (!anchoredCaret || (snapshot.selectionStart == start && snapshot.selectionEnd == end)) {
                phoneCaretAnchor = null
            }
            state = state.withSnapshotMetadata(snapshot).copy(text = snapshot.text,
                selectionStart = start, selectionEnd = end, error = null)
        }
    }

    private fun acceptEnter(request: TextSyncRequest, snapshot: RemoteTextSnapshot) {
        val unchanged = state.text == request.text && state.selectionStart == request.start && state.selectionEnd == request.end
        val sameEditor = snapshot.source == remote?.source &&
            (snapshot.target == request.target || snapshot.windowTitle == remote?.windowTitle)
        val next = if (unchanged) InputBuffer(snapshot.text,
            snapshot.selectionStart ?: snapshot.text.length, snapshot.selectionEnd ?: snapshot.selectionStart ?: snapshot.text.length)
        else if (sameEditor) afterEnter(request, snapshot, state.text, state.selectionStart, state.selectionEnd) else null
        if (next == null) {
            enterRequests.clear()
            state = state.copy(paused = true, error = "conflict")
            return
        }
        for (intent in enterRequests) {
            val rebased = afterEnter(request, snapshot, intent.request.text, intent.request.start, intent.request.end)
            if (rebased == null) {
                enterRequests.clear()
                state = state.copy(paused = true, error = "conflict")
                return
            }
            intent.request = intent.request.copy(text = rebased.text, start = rebased.start, end = rebased.end)
            intent.prepared = rebased.text == snapshot.text && rebased.start == snapshot.selectionStart && rebased.end == snapshot.selectionEnd
        }
        remote = snapshot
        selectionDirty = next.text == snapshot.text &&
            (next.start != snapshot.selectionStart || next.end != snapshot.selectionEnd)
        phoneCaretAnchor = if (selectionDirty) snapshot else null
        if (selectionDirty) phoneCaretExpiresAt = clock() + PHONE_CARET_HOLD_NANOS
        state = state.withSnapshotMetadata(snapshot).copy(text = next.text, selectionStart = next.start,
            selectionEnd = next.end, dirty = next.text != snapshot.text, error = null)
    }

    private fun acceptNormalization(request: TextSyncRequest, snapshot: RemoteTextSnapshot) {
        val change = textChange(request.text, snapshot.text)
        // The submitted natural caret is authoritative during this input-rule
        // acknowledgement; Chromium's cached selection may still precede it.
        val caret = snapshot.editCaret ?: if (request.start <= change.from) request.start else
                (request.start + change.insert.length - (change.to - change.from)).let { boundary ->
                    if (request.start == request.text.length && snapshot.text.endsWith('\n') &&
                        !request.text.endsWith('\n')) boundary - 1 else boundary
                }
        val shift = caret - request.start
        val newer = textChange(request.text, state.text)
        val hasNewerText = state.text != request.text
        val beforeChange = hasNewerText && newer.to <= change.from
        val localDelta = if (beforeChange) newer.insert.length - (newer.to - newer.from) else 0
        val fromChange = change.from + localDelta
        val toChange = change.to + localDelta
        val anchored = snapshot.editCaret != null && request.start == request.end
        fun mapped(offset: Int): Int? = when {
            !beforeChange && (anchored || change.to <= request.start) && offset >= request.start -> offset + shift
            offset <= fromChange -> offset
            offset >= toChange -> offset + change.insert.length - (change.to - change.from)
            else -> null
        }
        val rebased = if (!hasNewerText) snapshot.text else {
            // Independent edits can sit before a disappearing filler or after
            // a generated marker. Overlapping edits remain a retained draft.
            if (request.start != request.end || (!beforeChange && newer.from < change.to &&
                    !(anchored && newer.from >= request.start))) null
            else {
                val from = if (beforeChange) newer.from else mapped(newer.from)
                val to = if (beforeChange) newer.to else mapped(newer.to)
                if (from == null || to == null || !isTextBoundary(snapshot.text, from) || !isTextBoundary(snapshot.text, to)) null
                else snapshot.text.replaceRange(from, to, newer.insert)
            }
        }
        val start = if (localVersion == request.localVersion) caret else mapped(state.selectionStart)
        val end = if (localVersion == request.localVersion) caret else mapped(state.selectionEnd)
        if (rebased == null || rebased.length > MAX_SYNC_TEXT_UNITS || start == null || end == null ||
            !isTextBoundary(rebased, start) || !isTextBoundary(rebased, end)) {
            state = state.copy(paused = true, dirty = true, error = "conflict")
            return
        }
        remote = snapshot
        caretConflictRetries = 0
        phoneCaretAnchor = snapshot
        phoneCaretExpiresAt = clock() + PHONE_CARET_HOLD_NANOS
        selectionDirty = rebased == snapshot.text && (start != caret || end != caret)
        for (intent in enterRequests) {
            val queued = intent.request
            if (queued.text == request.text && queued.start == request.start && queued.end == request.end) {
                intent.request = queued.copy(text = snapshot.text, start = caret, end = caret)
                intent.prepared = true
            } else {
                val change = textChange(request.text, queued.text)
                val from = change.from + shift
                val to = change.to + shift
                if (change.from < request.start || from > to || !isTextBoundary(snapshot.text, from) || !isTextBoundary(snapshot.text, to)) {
                    state = state.copy(paused = true, dirty = true, error = "conflict")
                    return
                }
                intent.request = queued.copy(text = snapshot.text.replaceRange(from, to, change.insert),
                    start = queued.start + shift, end = queued.end + shift)
                intent.prepared = false
            }
        }
        state = state.withSnapshotMetadata(snapshot).copy(text = rebased, selectionStart = start,
            selectionEnd = end, dirty = rebased != snapshot.text, error = null)
    }

    fun suspend() {
        val uncertain = pending?.second?.let { request ->
            (request.type == "text_enter" || request.type == "text_shift_enter") || (request.type == "text_edit" && !request.selectionOnly)
        } == true
        pending = null; enterRequests.clear(); selectionDirty = false; phoneCaretAnchor = null
        deferredNormalization = null; deferredEnter = null
        readRetryRequired = false
        state = state.copy(waiting = false, paused = state.paused || state.dirty || uncertain,
            error = if (uncertain) "unconfirmed" else state.error)
    }
    fun reload() {
        pending = null; remote = null; enterRequests.clear(); selectionDirty = false; phoneCaretAnchor = null
        deferredNormalization = null; deferredEnter = null
        caretConflictRetries = 0
        readRetryRequired = false
        state = TextSyncUiState(supported = state.supported, connected = state.connected,
            ordinaryInput = state.ordinaryInput, sourceSupported = state.sourceSupported,
            source = if (state.ordinaryInput) state.source else "",
            sourceIcon = if (state.ordinaryInput) state.sourceIcon else null,
            windowTitle = if (state.ordinaryInput) state.windowTitle else null)
        localVersion++
    }
    /** Return only phone edits for local draft retention, never the clean desktop text. */
    fun useOrdinaryInput(enabled: Boolean): String? {
        if (state.ordinaryInput == enabled) return null
        val previous = state
        val draft = state.text.takeIf { (state.dirty || state.composing) && it.isNotEmpty() }
        reload()
        state = state.copy(ordinaryInput = enabled, source = previous.source,
            sourceIcon = previous.sourceIcon, windowTitle = previous.windowTitle)
        return draft
    }
}

private data class TextChange(val from: Int, val to: Int, val insert: String)
private fun afterEnter(request: TextSyncRequest, snapshot: RemoteTextSnapshot,
    text: String, start: Int, end: Int): InputBuffer? {
    val at = snapshot.selectionStart ?: if (snapshot.text.isEmpty()) 0 else return null
    val to = snapshot.selectionEnd ?: at
    if (text == request.text && start == request.start && end == request.end) return InputBuffer(snapshot.text, at, to)
    if (snapshot.status != "editable" || at != to) return null
    val left = minOf(request.start, request.end)
    val right = maxOf(request.start, request.end)
    val prefix = request.text.substring(0, left)
    val suffix = request.text.substring(right)
    if (!text.startsWith(prefix) || !text.endsWith(suffix) || text.length < prefix.length + suffix.length) return null
    val insertedEnd = text.length - suffix.length
    if (start !in left..insertedEnd || end !in left..insertedEnd) return null
    val replaced = snapshot.text.replaceRange(minOf(at, to), maxOf(at, to), text.substring(left, insertedEnd))
    val nextStart = minOf(at, to) + start - left
    val nextEnd = minOf(at, to) + end - left
    return if (replaced.length <= MAX_SYNC_TEXT_UNITS && isTextBoundary(replaced, nextStart) && isTextBoundary(replaced, nextEnd))
        InputBuffer(replaced, nextStart, nextEnd) else null
}

private fun textChange(before: String, after: String): TextChange {
    var prefix = 0
    while (prefix < minOf(before.length, after.length) && before[prefix] == after[prefix]) prefix++
    while (!isTextBoundary(before, prefix) || !isTextBoundary(after, prefix)) prefix--
    var suffix = 0
    while (suffix < minOf(before.length, after.length) - prefix &&
        before[before.lastIndex - suffix] == after[after.lastIndex - suffix]) suffix++
    while (!isTextBoundary(before, before.length - suffix) || !isTextBoundary(after, after.length - suffix)) suffix--
    return TextChange(prefix, before.length - suffix, after.substring(prefix, after.length - suffix))
}
