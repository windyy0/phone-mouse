package com.windy.phonemouse.app

import android.view.MotionEvent
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.RequestDisallowInterceptTouchEvent
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.viewinterop.AndroidView

/** Split off fingers that started on other controls, preserving event history. */
internal class TouchRegionDispatcher {
    private var owned = 0
    private var downAt = 0L

    fun reset() { owned = 0 }

    fun dispatch(event: MotionEvent, width: Int, height: Int, target: (MotionEvent) -> Boolean): Boolean =
        dispatch(event, width, height, { _, _ -> false }, target)

    fun dispatch(event: MotionEvent, width: Int, height: Int,
        excludeStart: (Float, Float) -> Boolean, target: (MotionEvent) -> Boolean): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) owned = 0
        // Compose can synthesize CANCEL with pointer ID 0, regardless of the
        // IDs in the active sequence. Cancellation applies to every owned
        // contact and must reach the pad to clear gestures and held input.
        if (action == MotionEvent.ACTION_CANCEL) {
            if (owned == 0) return false
            try { return target(event) } finally { owned = 0 }
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            val i = event.actionIndex
            if (event.getX(i) >= 0 && event.getX(i) < width && event.getY(i) >= 0 && event.getY(i) < height &&
                !excludeStart(event.getX(i), event.getY(i)))
                owned = owned or (1 shl event.getPointerId(i))
        }
        var present = 0
        for (i in 0 until event.pointerCount) present = present or (1 shl event.getPointerId(i))
        val mask = owned and present
        if (mask == 0) return false
        val filtered = copyOwned(event, mask)
        try { return target(filtered) } finally {
            filtered.recycle()
            if (action == MotionEvent.ACTION_POINTER_UP) owned = owned and (1 shl event.getPointerId(event.actionIndex)).inv()
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) owned = 0
        }
    }

    private fun copyOwned(event: MotionEvent, mask: Int): MotionEvent {
        val indices = (0 until event.pointerCount).filter { mask and (1 shl event.getPointerId(it)) != 0 }
        val changed = indices.indexOf(event.actionIndex)
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> when {
                changed < 0 -> MotionEvent.ACTION_MOVE
                indices.size == 1 -> if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP
                else -> event.actionMasked or (changed shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            else -> event.actionMasked
        }
        if (action == MotionEvent.ACTION_DOWN) downAt = event.eventTime
        val properties = indices.map { i -> MotionEvent.PointerProperties().also { event.getPointerProperties(i, it) } }.toTypedArray()
        fun coords(history: Int? = null) = indices.map { i -> MotionEvent.PointerCoords().also {
            if (history == null) event.getPointerCoords(i, it) else event.getHistoricalPointerCoords(i, history, it)
        } }.toTypedArray()
        val history = if (action == MotionEvent.ACTION_MOVE) event.historySize else 0
        val filtered = MotionEvent.obtain(downAt, if (history > 0) event.getHistoricalEventTime(0) else event.eventTime,
            action, indices.size, properties, if (history > 0) coords(0) else coords(),
            event.metaState, event.buttonState, event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags, event.source, event.flags)
        if (history > 0) {
            for (i in 1 until history) filtered.addBatch(event.getHistoricalEventTime(i), coords(i), event.metaState)
            filtered.addBatch(event.eventTime, coords(), event.metaState)
        }
        return filtered
    }
}

/** Bypass AndroidViewHolder's missing first-down when another control is held. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable internal fun TouchpadInputView(view: TouchpadView, modifier: Modifier, update: (TouchpadView) -> Unit = {},
    excludeStart: (Float, Float) -> Boolean = { _, _ -> false }, inputEnabled: Boolean = true) {
    val latestExcludeStart by rememberUpdatedState(excludeStart)
    // A disabled first down can leave PointerInteropFilter rejecting a later
    // POINTER_DOWN. Recreate its dispatch state before accepting a new gesture.
    key(inputEnabled) {
    val region = remember(view) { TouchRegionDispatcher() }
    val disallowIntercept = remember(view) { RequestDisallowInterceptTouchEvent() }
    DisposableEffect(view) {
        onDispose { region.reset(); view.cancelInteraction(animate = false) }
    }
    AndroidView(factory = {
        // The pad itself is remembered by its host. Dispatch generations have
        // separate holders, so detach it before attaching to the new holder.
        (view.parent as? ViewGroup)?.removeView(view)
        view
    }, modifier = modifier.pointerInteropFilter(
        requestDisallowInterceptTouchEvent = disallowIntercept,
    ) { event ->
        region.dispatch(event, view.width, view.height, latestExcludeStart) { filtered ->
            val handled = view.onTouchEvent(filtered)
            if (filtered.actionMasked == MotionEvent.ACTION_DOWN && handled) disallowIntercept(true)
            if (filtered.actionMasked == MotionEvent.ACTION_UP || filtered.actionMasked == MotionEvent.ACTION_CANCEL) disallowIntercept(false)
            handled
        }
    }, update = update)
    }
}
