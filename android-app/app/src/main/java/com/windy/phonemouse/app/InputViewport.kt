package com.windy.phonemouse.app

import android.graphics.Canvas
import android.os.Build
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import kotlin.math.max

/** Shared geometry for synchronized documents and ordinary realtime commits. */
internal class InputViewport(private val view: EditText) {
    private val density = view.resources.displayMetrics.density
    private val topPadding = (9 * density).toInt()
    private val bottomPadding = (1 * density).toInt()
    private val hintPaint = TextPaint()
    private var placeholder: CharSequence = ""
    val rowHeight: Int
    private val rowBaseline: Int

    init {
        // Measure independently of the current document. Include CJK and emoji
        // fallback fonts so an empty/Latin line reserves the same room as Chinese.
        val sample = "Ag国😀\nAg国😀"
        val builder = StaticLayout.Builder.obtain(sample, 0, sample.length, view.paint, 4096)
            .setIncludePad(false).setLineSpacing(5 * density, 1f)
        if (Build.VERSION.SDK_INT >= 28) builder.setUseLineSpacingFromFallbacks(true)
        val reference = builder.build()
        val metrics = view.paint.fontMetricsInt
        val fontHeight = metrics.descent - metrics.ascent
        rowHeight = max(reference.getLineBaseline(1) - reference.getLineBaseline(0), fontHeight)
        rowBaseline = (rowHeight - fontHeight) / 2 - metrics.ascent
        view.gravity = Gravity.TOP or Gravity.START
        view.setPadding((8 * density).toInt(), topPadding, (10 * density).toInt(), bottomPadding)
        view.setLineSpacing((rowHeight - fontHeight).toFloat(), 1f)
        if (Build.VERSION.SDK_INT >= 28) view.setFallbackLineSpacing(false)
    }

    fun height(rows: Int) = rowHeight * rows.coerceAtLeast(1) + topPadding + bottomPadding

    // Grow above the active row, keeping its baseline at the bottom of the
    // viewport. The surrounding screen can then anchor the touchpad as well.
    fun baseline() = view.height - bottomPadding - rowHeight + rowBaseline
    fun rowTop(line: Int): Float =
        (view.totalPaddingTop + view.layout.getLineBaseline(line) - view.scrollY - rowBaseline).toFloat()

    private fun scrollForLine(line: Int) = view.totalPaddingTop + view.layout.getLineBaseline(line) - baseline()
    fun minimumScroll() = view.layout?.let { scrollForLine(0) } ?: 0
    fun maximumScroll() = view.layout?.let { scrollForLine(it.lineCount - 1) } ?: 0

    fun reveal(offset: Int, showContext: Boolean = false): Boolean {
        val lines = view.layout ?: return false
        if (view.height == 0) return false
        val line = lines.getLineForOffset(offset.coerceIn(0, view.length()))
        val visibleRows = ((view.height - topPadding - bottomPadding) / rowHeight).coerceAtLeast(1)
        val rowsBelow = if (showContext) minOf(visibleRows / 2, lines.lineCount - 1 - line) else 0
        val target = scrollForLine(line) + rowsBelow * rowHeight
        if (view.scrollY != target) view.scrollTo(view.scrollX, target)
        return true
    }

    fun hint(value: CharSequence) {
        if (placeholder == value) return
        placeholder = value
        // The native hint has its own fallback-font layout. Draw at the same
        // baseline as real text so the first committed character cannot jump.
        view.hint = null
        view.invalidate()
    }

    fun drawHint(canvas: Canvas) {
        if (!view.text.isNullOrEmpty() || placeholder.isEmpty()) return
        val save = canvas.save()
        canvas.translate(view.scrollX.toFloat(), view.scrollY.toFloat())
        canvas.clipRect(0f, 0f, view.width.toFloat(), view.height.toFloat())
        hintPaint.set(view.paint)
        hintPaint.color = view.currentHintTextColor
        canvas.drawText(placeholder.toString(), view.compoundPaddingLeft + 4 * density, baseline().toFloat(), hintPaint)
        canvas.restoreToCount(save)
    }

    fun accessibility(info: AccessibilityNodeInfo) {
        info.hintText = placeholder
        info.isShowingHintText = view.text.isNullOrEmpty()
    }

    fun hintText() = placeholder
}
