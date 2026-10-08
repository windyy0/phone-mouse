package com.windy.phonemouse.app

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

internal val ScrollWheelTickLengthsDp = listOf(12f, 16f, 20f, 24f, 30f, 24f, 20f, 16f, 12f)
internal val ScrollWheelTickAlphas = listOf(.12f, .16f, .20f, .24f, .30f, .24f, .20f, .16f, .12f)

/** Input owns one finger; decorative motion never generates input. */
@Composable internal fun FloatingScrollWheel(modifier: Modifier, enabled: Boolean,
    onScroll: (Double) -> Unit, onScrollHold: () -> Unit, onActiveChanged: (Boolean) -> Unit,
    vertical: Boolean = false, onControlBounds: (String, Rect) -> Unit = { _, _ -> }) {
    val controller = remember { ScrollGestureController() }
    val latestScroll by rememberUpdatedState(onScroll)
    val latestHold by rememberUpdatedState(onScrollHold)
    val latestActiveChanged by rememberUpdatedState(onActiveChanged)
    var active by remember { mutableStateOf(false) }
    var continuous by remember { mutableStateOf(false) }
    var owner by remember { mutableStateOf<Long?>(null) }
    var phase by remember { mutableFloatStateOf(0f) }
    val arrowMotion = remember { Animatable(0f) }
    var arrowJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val canScroll = enabled && LocalWindowInfo.current.isWindowFocused
    val lifecycleOwner = LocalLifecycleOwner.current
    fun emit(delta: Double) {
        if (delta != 0.0) { phase = (phase - (delta / 120.0 * 12.0).toFloat()) % 8f; latestScroll(delta) }
    }
    fun stop() {
        controller.stop(); owner = null; continuous = false
        arrowJob?.cancel(); arrowJob = null
        if (active) { active = false; latestActiveChanged(false) }
    }
    fun arrow(delta: Double) {
        latestScroll(delta)
        arrowJob?.cancel()
        arrowJob = scope.launch {
            arrowMotion.snapTo(0f)
            arrowMotion.animateTo(if (delta > 0) -12f else 12f, tween(180))
            phase = (phase + arrowMotion.value) % 8f
            arrowMotion.snapTo(0f); arrowJob = null
        }
    }
    DisposableEffect(controller, canScroll, lifecycleOwner) {
        if (!canScroll) stop()
        val observer = object : DefaultLifecycleObserver { override fun onStop(owner: LifecycleOwner) { stop() } }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); stop() }
    }
    LaunchedEffect(active, canScroll) {
        if (active && canScroll) {
            delay(ScrollGestureController.HOLD_MS)
            if (controller.tryStartContinuous(SystemClock.uptimeMillis())) {
                continuous = true; latestHold()
                while (active) withFrameNanos { emit(controller.frame(SystemClock.uptimeMillis())) }
            }
        }
    }
    val background by animateColorAsState(if (active || arrowJob != null) PaleGreen else if (vertical) CardSurface else Soft, tween(140))
    val tickColor = Green
    val tickAlphaScale = if (canScroll) 1f else .35f
    val centreModifier = Modifier.onGloballyPositioned { onControlBounds("wheel", it.boundsInRoot()) }
        .semantics(mergeDescendants = true) {
            contentDescription = localize("滚轮，上下滑动")
            stateDescription = localize(if (continuous) "持续滚动" else if (active) "正在滚动" else "已停止")
            if (canScroll) scrollBy { _, y -> emit(-y / density * (120.0 / 18.0)); true } else disabled()
        }.pointerInput(canScroll, density) {
            if (!canScroll) return@pointerInput
            try {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        for (change in event.changes) {
                            if (owner == change.id.value) {
                                if (!change.pressed) stop()
                                else emit(controller.move(change.position.x / density, change.position.y / density))
                                change.consume()
                            } else if (owner == null && change.pressed && !change.previousPressed && !change.isConsumed &&
                                change.position.x >= 0f && change.position.x < size.width && change.position.y >= 0f && change.position.y < size.height) {
                                arrowJob?.cancel(); arrowJob = null
                                owner = change.id.value
                                controller.start(change.position.x / density, change.position.y / density, SystemClock.uptimeMillis(), allowContinuous = true)
                                active = true; latestActiveChanged(true); change.consume()
                            }
                        }
                    }
                }
            } finally { stop() }
        }
    @Composable fun centre(modifier: Modifier) {
        Box(modifier.then(centreModifier), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val step = (if (vertical) 8.dp else 3.dp).toPx()
                val motion = (phase + if (arrowJob != null) arrowMotion.value else 0f) * density
                val period = step * ScrollWheelTickLengthsDp.size
                val centreY = size.height / 2f
                ScrollWheelTickLengthsDp.indices.forEach { index ->
                    val relative = (index - ScrollWheelTickLengthsDp.size / 2) * step + motion
                    val wrapped = ((relative + period / 2f) % period + period) % period - period / 2f
                    val y = centreY + wrapped
                    val length = ScrollWheelTickLengthsDp[index].dp.toPx().coerceAtMost(size.width - 8.dp.toPx())
                    val stroke = 2.dp.toPx()
                    drawRoundRect(
                        color = tickColor.copy(alpha = ScrollWheelTickAlphas[index] * tickAlphaScale),
                        topLeft = Offset((size.width - length) / 2f, y - stroke / 2f),
                        size = Size(length, stroke),
                        cornerRadius = CornerRadius(stroke / 2f, stroke / 2f),
                    )
                }
            }
            Text(if (continuous) { if (vertical) "持续" else "持续滚动" } else "滚轮",
                Modifier.background(background.copy(alpha = .88f), RoundedCornerShape(4.dp)).padding(horizontal = 3.dp),
                color = Muted.copy(alpha = if (canScroll) 1f else .4f), fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1)
        }
    }
    @Composable fun arrowButton(up: Boolean) {
        val interactions = remember(up, vertical) { MutableInteractionSource() }
        val pressAlpha = remember(up, vertical) { Animatable(0f) }
        val arrowEnabled = canScroll && !active
        LaunchedEffect(interactions, arrowEnabled) {
            if (!arrowEnabled) pressAlpha.snapTo(0f)
            else interactions.interactions.filterIsInstance<PressInteraction>().collectLatest { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> pressAlpha.snapTo(.12f)
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressAlpha.animateTo(0f, tween(160))
                    else -> Unit
                }
            }
        }
        val label = if (up) "向上滚动" else "向下滚动"
        val shape = RoundedCornerShape(8.dp)
        Box(Modifier.size(if (vertical) 40.dp else 32.dp)
                .clip(shape)
                .background(Color.Black.copy(alpha = pressAlpha.value))
                .clickable(interactionSource = interactions, indication = null, enabled = arrowEnabled,
                    role = androidx.compose.ui.semantics.Role.Button) { arrow(if (up) 120.0 else -120.0) }
                .onGloballyPositioned { onControlBounds(if (up) "wheelUp" else "wheelDown", it.boundsInRoot()) },
            contentAlignment = Alignment.Center) {
            Icon(painterResource(if (up) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down), localize(label),
                modifier = Modifier.size(16.dp), tint = Green.copy(alpha = if (arrowEnabled) 1f else .35f))
        }
    }
    Surface(modifier.height(if (vertical) 160.dp else 32.dp), color = background,
        shape = RoundedCornerShape(if (vertical) 14.dp else 10.dp), shadowElevation = if (vertical) 3.dp else 0.dp,
        border = if (vertical) BorderStroke(1.dp, LocalAppColors.current.outline.copy(alpha = .5f)) else null) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (vertical) 40.dp else 32.dp) {
            if (vertical) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                arrowButton(true); centre(Modifier.weight(1f).fillMaxWidth()); arrowButton(false)
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                arrowButton(true); centre(Modifier.weight(1f).fillMaxHeight()); arrowButton(false)
            }
        }
    }
}
