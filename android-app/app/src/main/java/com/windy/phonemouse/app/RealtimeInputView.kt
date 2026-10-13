package com.windy.phonemouse.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.UnderlineSpan
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

internal class RealtimeInputFocus {
    var view: RealtimeInputView? = null
    var syncView: SyncedInputView? = null
    fun hasFocus() = syncView?.hasFocus() == true || view?.hasFocus() == true
    fun clearFocus() { view?.clearFocus(); syncView?.clearFocus() }
    fun suspendInput() { view?.suspendInput(); syncView?.suspendInput() }
    fun prepareInputSwitch() {
        syncView?.reportPendingEdit()
        view?.reportPendingText()
        suspendInput()
    }
    fun requestFocus() {
        (syncView ?: view)?.let {
            it.requestFocus()
            (it.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}

/** Native IME commits are distinct from the phone's composing/candidate text. */
internal class RealtimeInputView(context: Context) : EditText(context) {
    var onCommit: (String) -> Boolean = { false }
    var onLocalText: (String) -> Unit = { }
    var onRemoteKey: (String) -> Unit = { }
    private var active = false
    private var epoch = 0L
    private var mutationDepth = 0
    private var finishedText: String? = null
    private var viewport: InputViewport? = null
    private val finishCommit = Runnable {
        val value = finishedText
        finishedText = null
        if (active && hasFocus() && isShown && value != null && !hasComposition()) emit(value)
    }
    private val localCommit = Runnable {
        if (active && hasFocus() && isShown && !hasComposition()) emit(text?.toString().orEmpty())
    }

    init {
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setBackgroundColor(Color.TRANSPARENT)
        textSize = 15f
        viewport = InputViewport(this)
        isCursorVisible = false
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (mutationDepth != 0) return
                onLocalText(s?.toString().orEmpty())
                // Hardware typing and paste do not always use InputConnection.commitText.
                removeCallbacks(localCommit)
                if (active && !hasComposition()) post(localCommit)
            }
        })
    }

    fun hintText(value: CharSequence) { viewport?.hint(value) }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (viewport != null) bringPointIntoView(selectionEnd.coerceIn(0, length()))
    }
    override fun bringPointIntoView(offset: Int): Boolean {
        // Keep native horizontal scrolling for long provisional words, then
        // restore the shared vertical baseline before the next frame draws.
        val moved = super.bringPointIntoView(offset)
        return viewport?.reveal(offset) == true || moved
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        viewport?.drawHint(canvas)
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        viewport?.accessibility(info)
    }

    fun update(value: String, enabled: Boolean) {
        if (active != enabled) {
            suspendInput()
            active = enabled
            if (hasFocus()) inputMethodManager().restartInput(this)
        }
        isEnabled = enabled
        if (text?.toString() != value) mutate {
            setText(value)
            setSelection(length())
            if (value.isNotEmpty()) text?.setSpan(UnderlineSpan(), 0, length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or Spanned.SPAN_COMPOSING)
        }
    }

    fun suspendInput() {
        epoch++
        active = false
        finishedText = null
        removeCallbacks(finishCommit)
        removeCallbacks(localCommit)
    }
    fun reportPendingText() { onLocalText(text?.toString().orEmpty()) }

    private fun inputMethodManager() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private fun hasComposition() = BaseInputConnection.getComposingSpanStart(text) >= 0
    private inline fun <T> mutate(block: () -> T): T {
        mutationDepth++
        try { return block() } finally { mutationDepth-- }
    }
    private fun cancelFinish() {
        finishedText = null
        removeCallbacks(finishCommit)
        removeCallbacks(localCommit)
    }
    private fun emit(value: String) {
        if (value.isEmpty() || !active) return
        if (onCommit(value)) {
            mutate { text?.clear() }
            onLocalText("")
        } else {
            onLocalText(text?.toString().orEmpty())
        }
    }
    private fun empty() = text.isNullOrEmpty() && !hasComposition()

    private fun sendRemoteBackspaces(count: Int = 1) {
        if (!active || hasComposition() || !empty()) return
        repeat(count.coerceIn(1, 32)) { onRemoteKey("backspace") }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val target = super.onCreateInputConnection(outAttrs) ?: return null
        outAttrs.hintText = viewport?.hintText()
        val connectionEpoch = epoch
        return object : InputConnectionWrapper(target, false) {
            private fun valid() = active && epoch == connectionEpoch
            override fun setComposingText(value: CharSequence?, newCursorPosition: Int): Boolean {
                if (!valid()) return false
                cancelFinish()
                val result = mutate { super.setComposingText(value, newCursorPosition) }
                onLocalText(text?.toString().orEmpty())
                return result
            }
            override fun setComposingRegion(start: Int, end: Int): Boolean {
                if (!valid()) return false
                cancelFinish()
                return super.setComposingRegion(start, end)
            }
            override fun commitText(value: CharSequence?, newCursorPosition: Int): Boolean {
                if (!valid()) return false
                cancelFinish()
                val committed = value?.toString().orEmpty()
                val result = mutate { super.commitText(value, newCursorPosition) }
                if (result) emit(committed)
                return result
            }
            override fun finishComposingText(): Boolean {
                if (!valid()) return false
                val composing = if (hasComposition()) text?.toString() else null
                val result = mutate { super.finishComposingText() }
                onLocalText(text?.toString().orEmpty())
                // Some IMEs finish a word before committing its candidate. Let that
                // commit replace this task; focus loss/mode changes cancel it too.
                if (composing != null) {
                    finishedText = composing
                    removeCallbacks(finishCommit)
                    postDelayed(finishCommit, 48L)
                }
                return result
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (!valid()) return false
                cancelFinish()
                if (empty() && beforeLength > 0 && afterLength == 0) {
                    sendRemoteBackspaces(beforeLength)
                    return true
                }
                return mutate { super.deleteSurroundingText(beforeLength, afterLength) }.also {
                    onLocalText(text?.toString().orEmpty())
                }
            }
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                if (!valid()) return false
                cancelFinish()
                if (empty() && beforeLength > 0 && afterLength == 0) {
                    sendRemoteBackspaces(beforeLength)
                    return true
                }
                return mutate { super.deleteSurroundingTextInCodePoints(beforeLength, afterLength) }.also {
                    onLocalText(text?.toString().orEmpty())
                }
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (!valid()) return false
                // Some keyboards use repeated key events instead of
                // deleteSurroundingText for a held backspace key.
                if (event.keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                    sendRemoteBackspaces()
                    if (empty()) return true
                }
                return super.sendKeyEvent(event)
            }
            override fun performEditorAction(editorAction: Int): Boolean {
                if (!valid()) return false
                if (hasComposition()) return super.performEditorAction(editorAction)
                finishCommit.run()
                localCommit.run()
                onRemoteKey("enter")
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (active && !hasComposition()) {
            if (keyCode == KeyEvent.KEYCODE_DEL && empty()) {
                sendRemoteBackspaces()
                return true
            }
            if (isEnter(keyCode)) {
                finishCommit.run()
                localCommit.run()
                onRemoteKey("enter")
                return true
            }
            val character = event.unicodeChar
            if (!event.isCtrlPressed && !event.isAltPressed && character in 0x20..0x10ffff) {
                val value = String(Character.toChars(character))
                cancelFinish()
                mutate { text?.append(value) }
                emit(value)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        if (active && keyCode == KeyEvent.KEYCODE_DEL && empty()) {
            sendRemoteBackspaces(repeatCount)
            return true
        }
        return super.onKeyMultiple(keyCode, repeatCount, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // TextView's single-line Enter release tries to focus another native
        // View. A Compose focus target cannot accept that request and Android
        // throws. Enter is a remote key here; consume its release even if a
        // pause/mode transition happened between down and up.
        if (isEnter(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun isEnter(keyCode: Int) = keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        if (!focused) cancelFinish()
        isCursorVisible = focused
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
    }
    override fun onDetachedFromWindow() {
        suspendInput()
        super.onDetachedFromWindow()
    }
}
