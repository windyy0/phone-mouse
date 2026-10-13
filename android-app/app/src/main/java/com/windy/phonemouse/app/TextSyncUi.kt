package com.windy.phonemouse.app

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.layout.layout

/** Content growth is consumed by scrolling above the touchpad, in layout. */
internal class InputPanelExpansion {
    var pixels = 0
    var scrollReserve = 0
    var anchoredPixels = 0
}

internal fun Modifier.anchorInputGrowth(expansion: InputPanelExpansion, scroll: ScrollState) = layout { measurable, constraints ->
    val child = measurable.measure(constraints)
    val change = expansion.pixels - expansion.anchoredPixels
    val consumed = scroll.dispatchRawDelta(change.toFloat()).toInt()
    expansion.anchoredPixels += consumed
    // A newly measured scroll range becomes available after this child is
    // measured. Offset any unconsumed portion immediately, avoiding a frame
    // where the touchpad would move before the next scroll placement.
    val remainder = expansion.pixels - expansion.anchoredPixels
    layout(child.width, child.height) { child.placeRelative(0, -remainder) }
}

@Composable internal fun RealtimeInputPanel(state: UiState, vm: MainViewModel, focus: RealtimeInputFocus, modifier: Modifier = Modifier,
    expansion: InputPanelExpansion? = null) {
    val context = LocalContext.current
    val colors = LocalAppColors.current
    val lineHighlight = if (colors.dark) colors.paleGreen else Color(0xFFEDF3E7)
    val language = LocalAppLanguage.current
    val density = LocalDensity.current.density
    // Reserve two 16sp status lines without adding empty vertical padding.
    val footerHeight = maxOf(32.dp, with(LocalDensity.current) { 32.sp.toDp() })
    val sync = state.textSync
    val synced = sync.showEditor
    val syncedView = remember { SyncedInputView(context) }
    val liveView = remember { RealtimeInputView(context) }
    var expanded by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    var contentRows by remember { mutableIntStateOf(1) }
    val carryKeyboardFocus = remember { booleanArrayOf(false) }
    val blockContextRows = if (synced) listOf(sync.contextBefore, sync.contextAfter).count { it != null } else 0
    // The native view measures rows that fit around the current caret, including
    // wrapping and paragraph context, without changing the synchronized buffer.
    val rows = if (synced) contentRows.coerceIn(1, (if (expanded) 9 else 5) - blockContextRows) else 1
    val heightPixels = syncedView.viewportHeight(rows)
    val contextRowPixels = syncedView.viewportHeight(2) - syncedView.viewportHeight(1)
    val height = heightPixels / density
    expansion?.let {
        it.pixels = heightPixels + blockContextRows * contextRowPixels - syncedView.viewportHeight(1)
        it.scrollReserve = syncedView.viewportHeight(9) - syncedView.viewportHeight(1)
    }
    val retained = state.retainedText.isNotEmpty() || (state.livePaused && state.liveText.isNotEmpty())
    val message = when {
        retained -> "有文字未确认，请先检查电脑内容。"
        synced && (sync.paused || !sync.connected || sync.status != "editable") -> sync.message
        synced -> null
        !state.connection.connected -> "未连接 · 实时发送已暂停"
        state.livePaused -> "实时发送已暂停"
        sync.ordinaryInput -> null
        else -> sync.message
    }
    val actionLabel = when {
        retained -> "加入草稿"
        synced && sync.paused && sync.connected -> if (sync.dirty) "保留草稿并重读" else "重新读取"
        !synced && state.livePaused -> "继续实时输入"
        else -> null
    }
    val onAction: (() -> Unit)? = when {
        retained -> ({ focus.suspendInput(); vm.restoreRetainedText() })
        synced && sync.paused && sync.connected -> vm::reloadSyncedText
        !synced && state.livePaused -> vm::resumeRealtime
        else -> null
    }
    DisposableEffect(synced, syncedView, liveView) {
        if (synced) focus.syncView = syncedView else focus.view = liveView
        browsing = false
        val incoming = if (synced) syncedView else liveView
        if (carryKeyboardFocus[0]) {
            carryKeyboardFocus[0] = false
            incoming.post {
                val stillCurrent = if (synced) focus.syncView === incoming else focus.view === incoming
                if (stillCurrent && incoming.isAttachedToWindow && incoming.isEnabled) focus.requestFocus()
            }
        }
        onDispose {
            val outgoing = if (synced) syncedView else liveView
            carryKeyboardFocus[0] = outgoing.hasFocus() &&
                ViewCompat.getRootWindowInsets(outgoing)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (synced) {
                syncedView.suspendInput()
                if (focus.syncView === syncedView) focus.syncView = null
            } else {
                liveView.suspendInput()
                if (focus.view === liveView) focus.view = null
            }
        }
    }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SourceAppIcon(sync.sourceIcon, Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                buildAnnotatedString {
                    append(sync.source.ifEmpty { Localization.text("电脑当前窗口", language) })
                    sourceWindowTitle(sync.source, sync.windowTitle)?.let { title ->
                        append(" · ")
                        withStyle(SpanStyle(color = colors.muted.copy(alpha = .72f))) { append(title) }
                    }
                },
                color = colors.muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!synced) {
                Text("普通输入", color = colors.muted, fontSize = 11.sp, maxLines = 1)
            } else if (!sync.paused && sync.status == "editable") {
                Text(
                    if (sync.dirty) "同步中…" else if (sync.composing) "选词中"
                    else if (sync.paragraphNumber != null) {
                        if (language == AppLanguage.English) "Line ${sync.paragraphNumber} · Synced"
                        else "第 ${sync.paragraphNumber} 行 · 已同步"
                    }
                    else if (sync.paragraphStart != null || blockContextRows > 0 || InputParagraph.isLong(sync.text)) "当前段落 · 已同步" else "已同步",
                    color = colors.green,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        if (synced) {
            sync.contextBefore?.let { preview ->
                Text(preview.substringAfterLast('\n').ifEmpty { " " }, color = colors.muted.copy(alpha = .65f),
                    fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().height((contextRowPixels / density).dp).padding(horizontal = 8.dp))
            }
            AndroidView(
                factory = { syncedView },
                modifier = Modifier.fillMaxWidth().height(height.dp),
                update = {
                    it.onEdit = vm::editSyncedText
                    it.onBrowse = { value -> browsing = value }
                    it.onRows = { value -> if (contentRows != value) contentRows = value }
                    it.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus -> if (hasFocus) vm.pauseGyro() }
                    it.setTextColor(colors.ink.toArgb())
                    it.setHintTextColor(colors.muted.toArgb())
                    it.hintText(Localization.text(if (sync.status == "no_input") "等待电脑输入焦点…" else "输入文字…", language))
                    it.colors(colors.card.toArgb(), lineHighlight.toArgb(), colors.green.toArgb())
                    it.update(sync, expanded)
                },
            )
            sync.contextAfter?.let { preview ->
                Text(preview.substringBefore('\n').ifEmpty { " " }, color = colors.muted.copy(alpha = .65f),
                    fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().height((contextRowPixels / density).dp).padding(horizontal = 8.dp))
            }
        } else {
            AndroidView(
                factory = { liveView },
                modifier = Modifier.fillMaxWidth().height(height.dp),
                update = {
                    it.onCommit = vm::realtimeCommit
                    it.onLocalText = vm::liveText
                    it.onRemoteKey = vm::key
                    it.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus -> if (hasFocus) vm.pauseGyro() }
                    it.setTextColor(colors.ink.toArgb())
                    it.setHintTextColor(colors.muted.toArgb())
                    it.hintText(Localization.text(if (sync.status == "no_input") "等待电脑输入焦点…" else "输入文字…", language))
                    it.highlightColor = colors.green.copy(alpha = .3f).toArgb()
                    if (android.os.Build.VERSION.SDK_INT >= 29) it.textCursorDrawable?.setTint(colors.green.toArgb())
                    it.update(state.liveText, state.connection.connected && !state.livePaused)
                },
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(
            Modifier.fillMaxWidth().height(footerHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (synced && browsing) {
                    TextButton(
                        onClick = { syncedView.returnToCaret() },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        modifier = Modifier.height(footerHeight),
                    ) { Text("回到光标", fontSize = 12.sp, maxLines = 1) }
                } else if (message != null) {
                    TextInputStatusLine(
                        modifier = Modifier.height(footerHeight),
                        message = message,
                        actionLabel = actionLabel,
                        onAction = onAction,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        painterResource(if (expanded) R.drawable.ic_collapse_corners else R.drawable.ic_expand_corners),
                        contentDescription = Localization.text(if (expanded) "收起" else "展开", language),
                        tint = colors.green,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable internal fun TextInputStatusLine(
    modifier: Modifier = Modifier,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            color = LocalAppColors.current.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp),
            ) { Text(actionLabel, fontSize = 11.sp, maxLines = 1) }
        }
    }
}
