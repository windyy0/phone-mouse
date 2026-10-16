package com.windy.phonemouse.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import kotlin.math.abs
import kotlin.math.min

/** A single native document: fading never divides text into separate editors. */
internal class SyncedInputView(context: Context) : EditText(context) {
    var onEdit: (String, Int, Int, Boolean) -> Unit = { _, _, _, _ -> }
    var onEnter: (Boolean) -> Unit = {}
    var onBrowse: (Boolean) -> Unit = {}
    var onRows: (Int) -> Unit = {}
    private var initialized = false
    private var mutating = false
    private var active = false
    private var epoch = 0L
    private var expanded = false
    private var browsing = false
    private var touching = false
    private var dragging = false
    private var downY = 0f
    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var surfaceColor = Color.WHITE
    private var bandColor = Color.TRANSPARENT
    private var accentColor = Color.DKGRAY
    private val viewport: InputViewport
    private var paragraph = InputParagraph("", "", "")
    private var remoteParagraphStart: Long? = null
    private var showParagraphContext = false
    private val report = Runnable {
        reportRows()
        if (initialized && !mutating && active && hasFocus()) {
            onEdit(paragraph.replace(text?.toString().orEmpty()), paragraph.offset + selectionStart.coerceAtLeast(0), paragraph.offset + selectionEnd.coerceAtLeast(0), composing())
        }
        if (!browsing) revealCaret()
        invalidate()
    }
    private val nativeKeyListener: android.text.method.KeyListener?

    init {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setBackgroundColor(Color.TRANSPARENT)
        textSize = 15f
        viewport = InputViewport(this)
        isVerticalScrollBarEnabled = false
        filters = arrayOf(InputFilter.LengthFilter(MAX_SYNC_TEXT_UNITS))
        nativeKeyListener = keyListener
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!mutating) { setBrowsing(false); scheduleReport() }
            }
        })
        initialized = true
    }
    private fun composing() = BaseInputConnection.getComposingSpanStart(text) >= 0
    private fun scheduleReport() { removeCallbacks(report); post(report) }
    fun suspendInput() { epoch++; active = false; removeCallbacks(report) }
    fun reportPendingEdit() {
        removeCallbacks(report)
        if (initialized && active && hasFocus() && !mutating) report.run()
    }
    fun colors(surface: Int, band: Int, accent: Int) {
        surfaceColor = surface; bandColor = band; accentColor = accent
        highlightColor = (accent and 0x00ffffff) or 0x44000000
        if (android.os.Build.VERSION.SDK_INT >= 29) textCursorDrawable?.setTint(accent)
        invalidate()
    }
    fun hintText(value: CharSequence) {
        viewport.hint(value)
    }
    fun viewportHeight(rows: Int) = viewport.height(rows)
    fun update(state: TextSyncUiState, expand: Boolean) {
        showParagraphContext = state.paragraphStart != null || InputParagraph.isLong(state.text)
        val enabled = state.editable
        if (active != enabled) {
            epoch++; active = enabled
            mutating = true
            try {
                setTextIsSelectable(!enabled)
                keyListener = if (enabled) nativeKeyListener else null
                // TextView.setTextIsSelectable(false) clears touch focus and
                // its movement method. Restoring only the key listener leaves
                // the recovered editor unable to open the IME on a touch.
                if (enabled) {
                    movementMethod = android.text.method.ArrowKeyMovementMethod.getInstance()
                    isFocusableInTouchMode = true
                    isFocusable = true
                }
                showSoftInputOnFocus = enabled
            } finally { mutating = false }
            if (hasFocus()) (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(this)
        }
        val expandedChanged = expanded != expand
        expanded = expand
        val oldScroll = scrollY
        val nextParagraph = if (state.paragraphStart != null) InputParagraph("", state.text, "")
            else if (state.dirty || state.composing || composing()) paragraph.retained(state.text)
                ?: InputParagraph.select(state.text, state.selectionStart, state.selectionEnd)
            else InputParagraph.select(state.text, state.selectionStart, state.selectionEnd)
        val scopeChanged = paragraph.prefix != nextParagraph.prefix || remoteParagraphStart != state.paragraphStart
        val visibleStart = state.selectionStart - nextParagraph.offset
        val visibleEnd = state.selectionEnd - nextParagraph.offset
        val changed = text?.toString() != nextParagraph.text
        // The View owns in-progress IME composition. Echoes must not reset spans.
        if (!composing() && (changed || scopeChanged || selectionStart != visibleStart || selectionEnd != visibleEnd)) {
            mutating = true
            try {
                paragraph = nextParagraph
                remoteParagraphStart = state.paragraphStart
                filters = arrayOf(InputFilter.LengthFilter((MAX_SYNC_TEXT_UNITS - paragraph.prefix.length - paragraph.suffix.length).coerceAtLeast(0)))
                if (scopeChanged) setBrowsing(false)
                // Keep the Editable/InputConnection while the desktop text
                // changes. setText replaces the buffer and restarts the IME on
                // each keystroke or paragraph change.
                if (changed) text?.replace(0, length(), nextParagraph.text)
                setSelection(visibleStart.coerceIn(0, length()), visibleEnd.coerceIn(0, length()))
            } finally { mutating = false }
            post {
                reportRows()
                if (browsing) scrollTo(0, oldScroll.coerceIn(viewport.minimumScroll(), viewport.maximumScroll()))
                else revealCaret()
            }
        }
        if (expandedChanged) post { reportRows(); if (!browsing) revealCaret() }
        invalidate()
    }
    private fun setBrowsing(value: Boolean) {
        if (browsing == value) return
        browsing = value; onBrowse(value); reportRows(); invalidate()
    }
    private fun reportRows() {
        if (!initialized) return
        val lines = layout ?: return
        val value = text?.toString().orEmpty()
        // A text mutation can notify selection before TextView has rebuilt its
        // layout. Wait for onLayout instead of publishing those stale metrics.
        if (lines.text.toString() != value) return
        val maximumRows = if (expanded) 9 else 5
        val rows = if (browsing || hasSelection()) lines.lineCount.coerceIn(1, maximumRows)
            else InputParagraph.displayRows(value, lines.lineCount,
                lines.getLineForOffset(selectionEnd.coerceIn(0, length())), maximumRows, showParagraphContext)
        onRows(rows)
    }
    fun returnToCaret() { setBrowsing(false); revealCaret() }
    private fun revealCaret() {
        if (touching || hasSelection() || selectionEnd < 0) return
        viewport.reveal(selectionEnd, showParagraphContext)
    }
    override fun bringPointIntoView(offset: Int): Boolean {
        if (!initialized) return super.bringPointIntoView(offset)
        if (browsing || touching) return false
        if (hasSelection()) return super.bringPointIntoView(offset)
        return viewport.reveal(offset, showParagraphContext)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (initialized) {
            reportRows()
            if (!browsing) revealCaret()
        }
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (initialized) viewport.accessibility(info)
    }
    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (initialized && !mutating && !dragging && hasFocus()) scheduleReport()
    }
    private fun sendEnter(shift: Boolean = false) {
        if (!active || !hasFocus()) return
        // Enter confirms an unfinished IME word without also submitting it.
        val confirmingComposition = composing()
        if (confirmingComposition) BaseInputConnection.removeComposingSpans(text)
        removeCallbacks(report)
        reportPendingEdit()
        if (!confirmingComposition) onEnter(shift)
    }
    private fun isEnter(keyCode: Int) = keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isEnter(keyCode)) {
            sendEnter(event.isShiftPressed)
            return true
        }
        return super.onKeyDown(keyCode, event).also { reportPendingEdit() }
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isEnter(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }
    override fun onTextContextMenuItem(id: Int): Boolean =
        super.onTextContextMenuItem(id).also { reportPendingEdit() }
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!active) return null
        val target = super.onCreateInputConnection(outAttrs) ?: return null
        outAttrs.hintText = viewport.hintText()
        val token = epoch
        return object : InputConnectionWrapper(target, false) {
            private fun valid() = active && token == epoch
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!valid()) return false
                // Soft keyboards may commit a line-break character instead of
                // sending KEYCODE_ENTER. Keep multiline paste as a text edit.
                if (text?.toString() in listOf("\n", "\r", "\r\n")) {
                    sendEnter()
                    return true
                }
                // Publish the final text/selection before a queued desktop
                // reply can render. A posted report leaves a committed word
                // unprotected after its composing span has been removed.
                return super.commitText(text, newCursorPosition).also { reportPendingEdit() }
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (!valid()) return false
                if (isEnter(event.keyCode)) {
                    if (event.action == KeyEvent.ACTION_DOWN) sendEnter(event.isShiftPressed)
                    return true
                }
                return super.sendKeyEvent(event)
            }
            override fun performEditorAction(editorAction: Int): Boolean {
                if (!valid()) return false
                sendEnter()
                return true
            }
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!valid()) return false
                return super.setComposingText(text, newCursorPosition).also { reportPendingEdit() }
            }
            override fun setComposingRegion(start: Int, end: Int): Boolean {
                if (!valid()) return false
                return super.setComposingRegion(start, end).also { reportPendingEdit() }
            }
            override fun finishComposingText(): Boolean {
                if (!valid()) return false
                return super.finishComposingText().also { reportPendingEdit() }
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (!valid()) return false
                return super.deleteSurroundingText(beforeLength, afterLength).also { reportPendingEdit() }
            }
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                if (!valid()) return false
                return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength).also { reportPendingEdit() }
            }
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { touching = true; dragging = false; downY = event.y }
            MotionEvent.ACTION_MOVE -> if (abs(event.y - downY) > slop && !hasSelection()) {
                dragging = true; setBrowsing(true)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
        }
        val result = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            touching = false
            if (!dragging) { setBrowsing(false); scheduleReport() }
            dragging = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        invalidate()
        return result
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (initialized) post { if (!browsing) revealCaret() }
    }
    override fun onDraw(canvas: Canvas) {
        val lines = layout
        var caretTop = 0f
        var caretBottom = 0f
        var caretLine = -1
        if (lines != null && selectionEnd >= 0) {
            val line = lines.getLineForOffset(selectionEnd.coerceAtMost(length()))
            caretLine = line
            caretTop = viewport.rowTop(line)
            caretBottom = caretTop + viewport.rowHeight
            // The active line is a document highlight, not a separate input
            // box. Keep it visible while editing locally and remotely.
            if (!browsing) {
                val save = canvas.save()
                canvas.translate(scrollX.toFloat(), scrollY.toFloat())
                canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
                val left = compoundPaddingLeft.toFloat()
                val right = (width - compoundPaddingRight).toFloat().coerceAtLeast(left)
                if (text?.isNotEmpty() == true) {
                    paint.shader = null; paint.color = bandColor; paint.alpha = 0xff
                    canvas.drawRoundRect(0f, caretTop + density, width.toFloat(),
                        caretBottom - density, 10*density, 10*density, paint)
                }
                // Keep the desktop caret visible when the phone editor has
                // lost focus. While editing locally, let the native Android
                // caret blink so two different caret shapes are never stacked.
                if (!hasFocus()) {
                    val offset = selectionEnd.coerceIn(0, length())
                    val caretX = (compoundPaddingLeft + lines.getPrimaryHorizontal(offset))
                        .coerceIn(left, right)
                    val caretOffset = 0f
                    val caretWidth = 1.5f*density
                    val caretLeft = (caretX + caretOffset).coerceIn(left, right - caretWidth)
                    // Line height includes the extra spacing used by the
                    // editor. Align the remote caret to the actual font bounds
                    // so it does not hang below the glyphs.
                    val baseline = totalPaddingTop + lines.getLineBaseline(line) - scrollY
                    val metrics = getPaint().fontMetrics
                    val glyphTop = baseline + metrics.ascent + 1*density
                    val glyphBottom = (baseline + metrics.descent - 2*density)
                        .coerceAtLeast(glyphTop + 10*density)
                    paint.color = accentColor; paint.alpha = 0xff
                    canvas.drawRect(
                        caretLeft,
                        glyphTop,
                        caretLeft + caretWidth,
                        glyphBottom,
                        paint,
                    )
                }
                paint.alpha = 0xff
                canvas.restoreToCount(save)
            }
        }
        super.onDraw(canvas)
        viewport.drawHint(canvas)
        val accessibility = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val edge = min(height * .3f, (caretBottom - caretTop).coerceAtLeast(lineHeight.toFloat()) * 1.6f)
        if (expanded || hasSelection() || touching || browsing || accessibility.isTouchExplorationEnabled) return
        val save = canvas.save()
        canvas.translate(scrollX.toFloat(), scrollY.toFloat())
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        // Keep the caret's line clear and fade its surrounding context even
        // when the document fits without scrolling. The native editor still
        // owns every line, selection and IME span.
        if (lines != null && caretLine >= 0 && text?.isNotEmpty() == true) {
            paint.shader = null
            val firstVisibleLine = lines.getLineForVertical((scrollY - totalPaddingTop).coerceAtLeast(0))
            val lastVisibleLine = lines.getLineForVertical(scrollY + height - totalPaddingTop)
            for (line in firstVisibleLine..lastVisibleLine) {
                val top = viewport.rowTop(line)
                val bottom = top + viewport.rowHeight
                if (bottom <= 0f || top >= height || line == caretLine) continue
                val opacity = if (abs(line - caretLine) == 1) .22f else .62f
                paint.color = surfaceColor
                paint.alpha = (255 * opacity).toInt()
                canvas.drawRect(0f, top, width.toFloat(), bottom, paint)
            }
        }
        paint.alpha = 0xff
        val clear = surfaceColor and 0x00ffffff
        val contentMaxScroll = viewport.maximumScroll()
        val topFadeEnd = min(edge, caretTop)
        if (scrollY > viewport.minimumScroll() && topFadeEnd > 0f) {
            paint.shader = LinearGradient(0f,0f,0f,topFadeEnd,surfaceColor,clear,Shader.TileMode.CLAMP)
            canvas.drawRect(0f,0f,width.toFloat(),topFadeEnd,paint)
        }
        val bottomFadeStart = (height-edge).coerceAtLeast(caretBottom)
        if (scrollY < contentMaxScroll && bottomFadeStart < height) {
            paint.shader = LinearGradient(0f,bottomFadeStart,0f,height.toFloat(),clear,surfaceColor,Shader.TileMode.CLAMP)
            canvas.drawRect(0f,bottomFadeStart,width.toFloat(),height.toFloat(),paint)
        }
        paint.shader = null
        canvas.restoreToCount(save)
    }
    override fun onDetachedFromWindow() { suspendInput(); super.onDetachedFromWindow() }
}
