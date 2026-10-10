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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Shared by the settings page and fullscreen tools, with the same edit target. */
@Composable internal fun GyroActionSettingsEditor(settings: ControlSettings, enabled: Boolean,
    displayRotation: Int, onSettingsChange: ((ControlSettings) -> ControlSettings) -> Unit,
    modifier: Modifier = Modifier, landscape: Boolean = false) {
    var orientation by rememberSaveable { mutableStateOf(GyroOrientation.fromRotation(displayRotation)) }
    var showActionHelp by rememberSaveable { mutableStateOf(false) }
    val action = settings.gyroAction(orientation)
    @Composable fun SplitToggle() {
        SettingsToggle("横竖屏单独设置", settings.gyroActionsSplit, enabled,
            compact = true, hint = { SettingsHintButton(onClick = { showActionHelp = true }) }) { split ->
            if (split) orientation = GyroOrientation.fromRotation(displayRotation)
            onSettingsChange { it.withGyroActionsSplit(split) }
        }
    }
    @Composable fun OrientationTabs() {
        if (settings.gyroActionsSplit) {
            Box(Modifier.fillMaxWidth()) {
                HorizontalDivider(Modifier.align(Alignment.BottomCenter),
                    color = LocalAppColors.current.outline.copy(alpha = .25f))
                Row(Modifier.fillMaxWidth().selectableGroup()) {
                    GyroOrientation.entries.forEach { option ->
                        val selected = orientation == option
                        Box(Modifier.weight(1f).height(48.dp)
                            .selectable(selected, role = Role.Tab, onClick = { orientation = option }),
                            contentAlignment = Alignment.Center) {
                            Text(option.label, color = if (selected) Green else Muted,
                                fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
                            if (selected) Box(Modifier.align(Alignment.BottomCenter).width(32.dp).height(2.dp)
                                .background(Green))
                        }
                    }
                }
            }
        }
    }
    @Composable fun Modes() {
        SettingsSegmentedChoices(GyroDirectionMode.entries, action.directionMode, { it.label }, { enabled }) { mode ->
            onSettingsChange { it.updateGyroAction(orientation) { action -> action.copy(directionMode = mode) } }
        }
    }
    @Composable fun ReverseX() {
        SettingsToggle("左右反向", action.reverseX, enabled, compact = true) { value ->
            onSettingsChange { it.updateGyroAction(orientation) { action -> action.copy(reverseX = value) } }
        }
    }
    @Composable fun ReverseY() {
        SettingsToggle("上下反向", action.reverseY, enabled, compact = true) { value ->
            onSettingsChange { it.updateGyroAction(orientation) { action -> action.copy(reverseY = value) } }
        }
    }
    @Composable fun ReverseControls() {
        val fontScale = LocalDensity.current.fontScale
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 280.dp && fontScale <= 1.3f) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) { ReverseX() }
                    Column(Modifier.weight(1f)) { ReverseY() }
                }
            } else Column { ReverseX(); ReverseY() }
        }
    }
    if (landscape) {
        Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (settings.gyroActionsSplit) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1.1f)) { SplitToggle() }
                    Column(Modifier.weight(.9f)) { OrientationTabs() }
                }
            } else SplitToggle()
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1.1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Modes()
                    ReverseControls()
                }
                Column(Modifier.weight(.9f)) {
                    GyroDirectionPreview(action.directionMode)
                }
            }
        }
    } else Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SplitToggle()
        OrientationTabs()
        if (!settings.gyroActionsSplit) SettingsDivider()
        Modes()
        GyroDirectionPreview(action.directionMode)
        ReverseControls()
    }
    if (showActionHelp) GyroActionHelpDialog(onClose = { showActionHelp = false })
}

@Composable private fun GyroActionHelpDialog(onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().pointerInput(onClose) { detectTapGestures { onClose() } })
            GyroActionHelpContent(onClose)
        }
    }
}

@Composable private fun GyroActionHelpContent(onClose: () -> Unit) {
    // Measure against the dialog window, including its safe area, rather than
    // the anchor's remaining space. Only the body scrolls; close stays reachable.
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(20.dp),
        contentAlignment = Alignment.Center) {
        Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight),
            color = CardSurface, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 20.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("手机动作说明", color = Ink, fontSize = 18.sp, lineHeight = 24.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_close), localize("关闭"), tint = Muted,
                            modifier = Modifier.size(22.dp))
                    }
                }
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val fontScale = LocalDensity.current.fontScale
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        if (maxWidth >= 440.dp && fontScale <= 1.3f) {
                            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                GyroActionHelpCard(GyroDirectionMode.Tilt, Modifier.weight(1f).fillMaxHeight())
                                GyroActionHelpCard(GyroDirectionMode.Twist, Modifier.weight(1f).fillMaxHeight())
                            }
                        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            GyroActionHelpCard(GyroDirectionMode.Tilt)
                            GyroActionHelpCard(GyroDirectionMode.Twist)
                        }
                    }
                    SettingsDivider()
                    val splitTitle = localized("横竖屏单独设置")
                    val splitDescription = localized("开启后，横屏和竖屏分别保存动作与反向设置；关闭时使用共用设置。")
                    val separator = if (LocalAppLanguage.current == AppLanguage.English) ": " else "："
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(color = Ink, fontWeight = FontWeight.SemiBold)) {
                            append(splitTitle)
                            append(separator)
                        }
                        append(splitDescription)
                    }, color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                }
            }
        }
    }
}

@Composable private fun GyroActionHelpCard(mode: GyroDirectionMode, modifier: Modifier = Modifier) {
    val pointing = mode == GyroDirectionMode.Tilt
    Surface(modifier.fillMaxWidth(), color = Soft, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(gyroDirectionIcon(mode)),
                    null, tint = Green, modifier = Modifier.size(24.dp))
                Text(mode.label, color = Green, fontSize = 15.sp, lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold)
            }
            Text(if (pointing) "像激光笔一样指向目标。" else "向左或向右倾斜手机。",
                color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
            Text(if (pointing) "左右转动 → 光标左右移动" else "左倾向左，右倾向右",
                color = Ink, fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("上下俯仰 → 光标上下移动", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
}

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

internal fun gyroDirectionIcon(mode: GyroDirectionMode) = when (mode) {
    GyroDirectionMode.Tilt -> R.drawable.ic_gyro_pointing
    GyroDirectionMode.Twist -> R.drawable.ic_gyro_side_tilt
}

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
                Box(Modifier.padding(start = 6.dp, top = 11.dp, end = 6.dp, bottom = 6.dp).fillMaxSize()
                    .background(screenColor, RoundedCornerShape(7.dp)))
                Box(Modifier.padding(top = 5.dp).size(12.dp, 3.dp)
                    .background(mutedColor, RoundedCornerShape(2.dp)))
            }
        }
    }
}

@Composable internal fun GyroControlPanel(state: UiState, vm: MainViewModel,
    modifier: Modifier = Modifier, compact: Boolean = false, showBlackScreenButton: Boolean = true) {
    val action = state.effectiveGyroSettings
    val actionIcon = gyroDirectionIcon(action.directionMode)
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
            Icon(painterResource(actionIcon), localize(action.directionMode.label),
                tint = Green, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text("体感鼠标", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(status, color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
            }
            Controls()
        } else Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
            Icon(painterResource(actionIcon), localize(action.directionMode.label),
                tint = Green, modifier = Modifier.size(40.dp))
            Text(status, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
