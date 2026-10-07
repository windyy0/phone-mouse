package com.windy.phonemouse.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun GyroModeEntry(vm: MainViewModel, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = { vm.setMouseControlMode(MouseControlMode.Gyroscope) },
        enabled = vm.gyroAvailable, modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = CardSurface, contentColor = Green)) {
        Icon(painterResource(R.drawable.ic_gyroscope), null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text("体感鼠标", fontSize = 13.sp)
    }
}

@Composable internal fun GyroControlPanel(state: UiState, vm: MainViewModel,
    modifier: Modifier = Modifier, compact: Boolean = false, showBlackScreenButton: Boolean = true) {
    val status = when {
        !vm.gyroAvailable -> "设备不支持陀螺仪"
        !state.connection.connected -> "请先连接电脑"
        state.gyroRepositioning -> "回位中，松手继续"
        state.gyroActive -> "控制中"
        state.gyroBlocked -> "其他操作期间暂停"
        else -> "已暂停"
    }
    val canToggle = vm.gyroAvailable && state.connection.connected && !state.gyroBlocked
    @Composable fun Controls() {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showBlackScreenButton) IconButton(onClick = vm::enterGyroBlackScreen, enabled = state.canEnterGyroBlackScreen,
                modifier = Modifier.size(44.dp)) {
                Icon(painterResource(R.drawable.ic_screen_off), contentDescription = "体感黑屏",
                    tint = if (state.canEnterGyroBlackScreen) Green else Muted, modifier = Modifier.size(22.dp))
            }
            Button(onClick = vm::toggleGyroPaused, enabled = canToggle,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Text(if (state.gyroActive) "暂停" else "开始", maxLines = 1)
            }
            if (!compact) {
                OutlinedButton(onClick = { vm.setMouseControlMode(MouseControlMode.Touchpad) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) { Text("触控板", maxLines = 1) }
            }
        }
    }
    val touchModifier = if (compact) modifier else modifier.gyroRepositionTouch(state.gyroRepositionEnabled, vm::setGyroRepositioning)
    Surface(touchModifier, color = if (compact) CardSurface else Soft, shape = RoundedCornerShape(18.dp),
        border = if (compact) BorderStroke(1.dp, LocalAppColors.current.outline) else null) {
        if (compact) Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_gyroscope), null, tint = Green, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text("体感鼠标", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(status, color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
            }
            Controls()
        } else Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
            Icon(painterResource(R.drawable.ic_gyroscope), null, tint = Green, modifier = Modifier.size(40.dp))
            Text(status, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("左右转动、上下俯仰控制光标", color = Muted, fontSize = 13.sp)
            Text("按住此区域立即回位，松手继续", color = Muted, fontSize = 12.sp)
            Controls()
            Text("文字输入、后台或断连后暂停；点击开始恢复。", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

/** Background presses clutch immediately; child buttons keep their own gestures. */
internal fun Modifier.gyroRepositionTouch(enabled: Boolean, onChange: (Boolean) -> Unit): Modifier =
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true)
            down.consume()
            onChange(true)
            try {
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } != 1) onChange(false)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            } finally {
                onChange(false)
            }
        }
    }
