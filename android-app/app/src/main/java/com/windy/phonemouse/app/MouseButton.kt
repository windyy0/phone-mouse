package com.windy.phonemouse.app

import androidx.compose.foundation.focusable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable internal fun MouseButton(label: String, left: Boolean, modifier: Modifier = Modifier,
    enabled: Boolean = true, inputEpoch: Long = 0, visualEnabled: Boolean = enabled, onDown: () -> Unit, onUp: () -> Unit) {
    val dark = LocalAppColors.current.dark
    val icon = if (dark) {
        if (left) R.drawable.ic_mouse_left_dark else R.drawable.ic_mouse_right_dark
    } else {
        if (left) R.drawable.ic_mouse_left else R.drawable.ic_mouse_right
    }
    MousePressButton(modifier.fillMaxWidth().fillMaxHeight(), enabled, onDown, onUp,
        inputEpoch = inputEpoch, visualEnabled = visualEnabled) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(25.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 13.sp)
        }
    }
}

@Composable internal fun MiddleMouseButton(modifier: Modifier = Modifier, enabled: Boolean = true,
    compact: Boolean = false, inputEpoch: Long = 0, visualEnabled: Boolean = enabled, onDown: () -> Unit, onUp: () -> Unit) {
    val icon = if (LocalAppColors.current.dark) R.drawable.ic_mouse_middle_dark else R.drawable.ic_mouse_middle
    MousePressButton(modifier.then(if (compact) Modifier.size(44.dp) else Modifier), enabled, onDown, onUp,
        secondary = true, inputEpoch = inputEpoch, visualEnabled = visualEnabled) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = if (compact) localize("中键") else null, tint = Color.Unspecified, modifier = Modifier.size(20.dp))
            if (!compact) Text("中键", fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

internal enum class MouseKey(val wire: String, val label: String) {
    Left("left", "左键"), Middle("middle", "中键"), Right("right", "右键")
}

@Composable internal fun FloatingMouseButton(key: MouseKey, modifier: Modifier = Modifier,
    enabled: Boolean, onDown: () -> Unit, onUp: () -> Unit, standalone: Boolean = false, inputEpoch: Long = 0,
    visualEnabled: Boolean = enabled) {
    val dark = LocalAppColors.current.dark
    val icon = when (key) {
        MouseKey.Left -> if (dark) R.drawable.ic_mouse_left_dark else R.drawable.ic_mouse_left
        MouseKey.Middle -> if (dark) R.drawable.ic_mouse_middle_dark else R.drawable.ic_mouse_middle
        MouseKey.Right -> if (dark) R.drawable.ic_mouse_right_dark else R.drawable.ic_mouse_right
    }
    MousePressButton(modifier.height(52.dp), enabled, onDown, onUp, standalone = standalone,
        inputEpoch = inputEpoch, visualEnabled = visualEnabled) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(4.dp))
            Text(key.label, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1)
        }
    }
}

@Composable private fun MousePressButton(modifier: Modifier, enabled: Boolean, onDown: () -> Unit,
    onUp: () -> Unit, secondary: Boolean = false, standalone: Boolean = false, inputEpoch: Long = 0,
    visualEnabled: Boolean = enabled, content: @Composable () -> Unit) {
    val latestDown by rememberUpdatedState(onDown)
    val latestUp by rememberUpdatedState(onUp)
    var pressed by remember { mutableStateOf(false) }
    val controller = remember {
        MouseButtonPressController(
            onDown = { pressed = true; latestDown() },
            onUp = { pressed = false; latestUp() },
        )
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(controller, lifecycleOwner, enabled, inputEpoch) {
        if (!enabled) controller.cancel()
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { controller.cancel() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); controller.cancel() }
    }
    Surface(
        modifier = modifier.heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = localize(if (pressed) "已按下" else "已松开")
                if (!enabled) disabled() else onClick { controller.activate() }
            }
            .onFocusChanged { if (!it.isFocused) controller.release(MouseButtonPressController.KEY_OWNER) }
            .onKeyEvent { event ->
                if (!enabled || event.key !in listOf(Key.Enter, Key.NumPadEnter, Key.Spacebar)) false
                else {
                    if (event.type == KeyEventType.KeyDown) controller.press(MouseButtonPressController.KEY_OWNER)
                    else if (event.type == KeyEventType.KeyUp) controller.release(MouseButtonPressController.KEY_OWNER)
                    true
                }
            }.focusable(enabled)
            .pointerInput(enabled, inputEpoch) {
                if (!enabled) return@pointerInput
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            for (change in event.changes) {
                                val inside = change.position.x >= 0 && change.position.y >= 0 &&
                                    change.position.x < size.width && change.position.y < size.height
                                if (controller.owner == change.id.value) {
                                    // Only this finger owns the key. Consuming another
                                    // finger would prevent held-button touchpad dragging.
                                    if (!change.pressed || !inside) controller.release(change.id.value)
                                    change.consume()
                                } else if (inside && change.pressed && !change.previousPressed && !change.isConsumed) {
                                    if (controller.press(change.id.value)) change.consume()
                                }
                            }
                        }
                    }
                } finally { controller.cancel() }
            },
        shape = RoundedCornerShape(15.dp),
        color = if (pressed) PaleGreen else if (secondary) Color.Transparent else if (standalone) CardSurface else Soft,
        border = if (standalone) BorderStroke(1.dp, LocalAppColors.current.outline.copy(alpha = .5f)) else null,
        shadowElevation = if (standalone) 3.dp else 0.dp,
        // A mode barrier pauses input without flashing the controls' disabled colors.
        contentColor = (if (secondary) Green else Ink).copy(alpha = if (visualEnabled) 1f else .45f),
        content = content,
    )
}
