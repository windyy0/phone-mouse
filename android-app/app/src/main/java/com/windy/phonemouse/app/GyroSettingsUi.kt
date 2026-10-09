package com.windy.phonemouse.app

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Composable internal fun GyroDirectionPreview(mode: GyroDirectionMode) {
    key(mode) {
        val transition = rememberInfiniteTransition(label = "gyroDirectionPreview")
        val motion by if (mode == GyroDirectionMode.Tilt) {
            transition.animateFloat(initialValue = 0f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart),
                label = "gyroHeadPhase")
        } else {
            transition.animateFloat(initialValue = -1f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "gyroSideMotion")
        }
        Surface(Modifier.fillMaxWidth(), color = Soft, shape = RoundedCornerShape(12.dp)) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                GyroDirectionAnimation(mode, motion)
            }
        }
    }
}

private fun gyroDirectionDescription(mode: GyroDirectionMode) = if (mode == GyroDirectionMode.Tilt)
    "像拿着激光笔一样指向目标，左右转动控制左右；上下俯仰控制上下。"
else "手机向左或向右侧边倾斜控制左右；上下俯仰控制上下。"

@Composable private fun GyroDirectionAnimation(mode: GyroDirectionMode, motion: Float) {
    val accent = Green.copy(alpha = .72f)
    val phoneColor = CardSurface
    val screenColor = PaleGreen
    val mutedColor = Muted.copy(alpha = .65f)
    val animationHeight = 112.dp
    Box(Modifier.fillMaxWidth().height(animationHeight), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            if (mode == GyroDirectionMode.Tilt) {
                val phoneWidth = with(density) { 52.dp.toPx() }
                val phoneLength = with(density) { 84.dp.toPx() }
                val pivot = Offset(center.x, size.height - with(density) { 4.dp.toPx() })
                val headCenter = Offset(pivot.x, pivot.y - phoneLength)
                val orbitWidth = with(density) { 46.dp.toPx() }
                val orbitHeight = with(density) { 14.dp.toPx() }
                val angle = -(motion * (2f * PI.toFloat())) - (PI.toFloat() / 2f)
                val head = Offset(headCenter.x + cos(angle) * orbitWidth,
                    headCenter.y + sin(angle) * orbitHeight)
                val dotRadius = with(density) { 3.dp.toPx() }
                // Blend the dot between the front and rear layers so it fades smoothly
                // as it travels behind the phone instead of popping at the midpoint.
                val rearProgress = ((sin(angle) + 1f) * .5f).coerceIn(0f, 1f)
                val rearAmount = rearProgress * rearProgress * (3f - 2f * rearProgress)
                val phoneRotation = Math.toDegrees(
                    atan2((head.x - pivot.x).toDouble(), (pivot.y - head.y).toDouble())
                ).toFloat()
                val rearStroke = Stroke(width = 1.5f, cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                val frontStroke = Stroke(width = 1.5f, cap = StrokeCap.Round)
                // The visible upper half is solid; the lower half recedes as a dashed path.
                drawArc(accent.copy(alpha = .30f), startAngle = 180f, sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(headCenter.x - orbitWidth, headCenter.y - orbitHeight),
                    size = androidx.compose.ui.geometry.Size(orbitWidth * 2f, orbitHeight * 2f),
                    style = frontStroke)
                drawArc(accent.copy(alpha = .12f), startAngle = 0f, sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(headCenter.x - orbitWidth, headCenter.y - orbitHeight),
                    size = androidx.compose.ui.geometry.Size(orbitWidth * 2f, orbitHeight * 2f),
                    style = rearStroke)
                val rearDotAlpha = .28f * rearAmount
                if (rearDotAlpha > 0f) {
                    drawCircle(accent.copy(alpha = rearDotAlpha), dotRadius, head)
                }
                withTransform({
                    rotate(phoneRotation, pivot)
                }) {
                    val corner = with(density) { 11.dp.toPx() }
                    val phoneTop = Offset(pivot.x - phoneWidth / 2f, pivot.y - phoneLength)
                    drawRoundRect(phoneColor, topLeft = phoneTop,
                        size = androidx.compose.ui.geometry.Size(phoneWidth, phoneLength),
                        cornerRadius = CornerRadius(corner, corner))
                    drawRoundRect(accent.copy(alpha = .72f), topLeft = phoneTop,
                        size = androidx.compose.ui.geometry.Size(phoneWidth, phoneLength),
                        cornerRadius = CornerRadius(corner, corner), style = Stroke(2f))
                    val screenInset = with(density) { 6.dp.toPx() }
                    drawRoundRect(screenColor,
                        topLeft = Offset(phoneTop.x + screenInset, phoneTop.y + screenInset * 1.8f),
                        size = androidx.compose.ui.geometry.Size(phoneWidth - screenInset * 2f,
                            phoneLength - screenInset * 2.6f),
                        cornerRadius = CornerRadius(corner / 1.7f, corner / 1.7f))
                    val notchWidth = with(density) { 12.dp.toPx() }
                    val notchHeight = with(density) { 3.dp.toPx() }
                    drawRoundRect(mutedColor,
                        topLeft = Offset(pivot.x - notchWidth / 2f, phoneTop.y + with(density) { 5.dp.toPx() }),
                        size = androidx.compose.ui.geometry.Size(notchWidth, notchHeight),
                        cornerRadius = CornerRadius(notchHeight, notchHeight))
                }
                val frontDotAlpha = 1f - rearAmount
                if (frontDotAlpha > 0f) {
                    drawCircle(accent.copy(alpha = frontDotAlpha), dotRadius, head)
                }
            } else {
                val left = size.width * .16f; val right = size.width * .84f
                drawLine(accent, Offset(left, center.y), Offset(right, center.y), 3f, cap = StrokeCap.Round)
                drawCircle(accent, 5f, Offset(center.x + motion * size.width * .28f, center.y))
            }
        }
        if (mode == GyroDirectionMode.Twist) {
            Box(Modifier.size(52.dp, 84.dp).graphicsLayer {
                rotationY = motion * 20f
                rotationZ = motion * 2f
            }.background(phoneColor, RoundedCornerShape(11.dp))
                .border(2.dp, Green.copy(alpha = .75f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.TopCenter) {
                Box(Modifier.padding(top = 5.dp).size(12.dp, 3.dp)
                    .background(mutedColor, RoundedCornerShape(2.dp)))
                Box(Modifier.padding(6.dp).fillMaxSize()
                    .background(screenColor, RoundedCornerShape(7.dp)))
            }
        }
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
                Icon(painterResource(R.drawable.ic_screen_off), contentDescription = localize("体感黑屏"),
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
            Text(gyroDirectionDescription(state.settings.gyro.directionMode), color = Muted, fontSize = 13.sp)
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
