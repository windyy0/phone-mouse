package com.windy.phonemouse.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt

internal enum class SettingsDestination(val title: String) {
    Home("设置"), Touchpad("触控板"), Gyroscope("体感鼠标"), Scroll("滚动与缩放"),
    Haptics("震动反馈"), Connection("连接与性能"), Appearance("外观与通用"), Sampling("触摸采样测试");

    val parent: SettingsDestination?
        get() = when (this) {
            Home -> null
            Sampling -> Connection
            else -> Home
        }
}

@Composable internal fun SettingsPage(settings: ControlSettings, vm: MainViewModel,
    destination: SettingsDestination, navigate: (SettingsDestination) -> Unit, close: () -> Unit) {
    val pages = rememberSaveableStateHolder()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val back: () -> Unit = {
        if (keyboardVisible) {
            focus.clearFocus(force = true)
            keyboard?.hide()
        } else {
            focus.clearFocus(force = true)
            destination.parent?.let(navigate) ?: close()
        }
    }
    BackHandler(onBack = back)
    pages.SaveableStateProvider(destination.name) {
        var selectedHapticAction by rememberSaveable { mutableStateOf<HapticAction?>(null) }
        val defaults = remember { ControlSettings() }
        val reset: ((ControlSettings) -> ControlSettings)? = when (destination) {
            SettingsDestination.Touchpad -> { current -> current.copy(
                sensitivity = defaults.sensitivity,
                tapToClick = defaults.tapToClick,
                doubleTapHoldDrag = defaults.doubleTapHoldDrag,
                longPressDrag = defaults.longPressDrag,
                touchpadAcceleration = defaults.touchpadAcceleration,
            ) }
            SettingsDestination.Gyroscope -> { current -> current.copy(gyro = defaults.gyro) }
            SettingsDestination.Scroll -> { current -> current.copy(
                scrollSpeed = defaults.scrollSpeed,
                reverseScroll = defaults.reverseScroll,
                reverseTwoFingerScroll = defaults.reverseTwoFingerScroll,
            ) }
            SettingsDestination.Haptics -> { current -> current.copy(haptics = defaults.haptics) }
            else -> null
        }
        SettingsScaffold(destination.title, back, onReset = reset?.let { transform -> {
            selectedHapticAction = null
            vm.updateSettings(transform)
        } }) {
            when (destination) {
                SettingsDestination.Home -> SettingsOverview(settings, vm, navigate)
                SettingsDestination.Touchpad -> TouchpadSettings(settings, vm)
                SettingsDestination.Gyroscope -> GyroscopeSettings(settings, vm)
                SettingsDestination.Scroll -> ScrollSettings(settings, vm)
                SettingsDestination.Haptics -> HapticSettingsContent(settings.haptics, vm, selectedHapticAction) { selectedHapticAction = it }
                SettingsDestination.Connection -> ConnectionSettings(settings, vm) { navigate(SettingsDestination.Sampling) }
                SettingsDestination.Appearance -> AppearanceSettings(settings, vm)
                SettingsDestination.Sampling -> TouchSamplingContent(vm)
            }
        }
    }
}

@Composable internal fun SettingsScaffold(title: String, back: () -> Unit, onReset: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Page) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxSize()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = back, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_arrow_back), "返回", tint = Ink, modifier = Modifier.size(24.dp))
                    }
                    Text(title, color = Ink, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 16.dp))
                    if (onReset != null) SettingsResetButton(onReset)
                }
                Column(Modifier.weight(1f).fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp), content = content)
            }
        }
    }
}

@Composable internal fun SettingsGroup(title: String? = null, action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Muted, fontSize = 13.sp, lineHeight = 18.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(start = 4.dp))
            action?.invoke()
        }
        Surface(Modifier.fillMaxWidth(), color = CardSurface, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), content = content)
        }
    }
}

@Composable internal fun SettingsDivider() {
    HorizontalDivider(color = LocalAppColors.current.outline.copy(alpha = .25f))
}

@Composable internal fun SettingsHint(text: String) {
    Text(text, color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
}

@Composable private fun SettingsResetButton(onReset: () -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    TextButton(onClick = {
        focus.clearFocus(force = true)
        keyboard?.hide()
        onReset()
    }, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = Green)) {
        Text("恢复默认设置", fontSize = 14.sp, maxLines = 1)
    }
}

@Composable internal fun SettingsToggle(label: String, checked: Boolean, enabled: Boolean = true,
    description: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = if (enabled) Ink else Muted, fontSize = 16.sp, lineHeight = 22.sp)
            if (description != null) SettingsHint(description)
        }
        SettingsSwitch(checked, enabled)
    }
}

@Composable private fun SettingsSwitch(checked: Boolean, enabled: Boolean) {
    val colors = LocalAppColors.current
    val offTrack = if (colors.dark) Color(0xFF3E4B42) else Color(0xFFDCE5DC)
    val trackColor by animateColorAsState(if (checked) Green else offTrack, tween(180), label = "settingsSwitchTrack")
    val thumbOffset by animateDpAsState(if (checked) 20.dp else 0.dp, tween(180), label = "settingsSwitchThumb")
    // The row owns toggle input and accessibility; this is its visual indicator.
    Box(Modifier.size(48.dp).graphicsLayer { alpha = if (enabled) 1f else .35f }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 48.dp, height = 28.dp).background(trackColor, CircleShape).padding(3.dp),
            contentAlignment = Alignment.CenterStart) {
            Box(Modifier.offset(x = thumbOffset).size(22.dp).shadow(1.dp, CircleShape)
                .background(if (colors.dark) Color(0xFFF1F5F1) else Color.White, CircleShape))
        }
    }
}

@Composable internal fun SettingsSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = if (enabled) Ink else Muted, fontSize = 16.sp, lineHeight = 22.sp, modifier = Modifier.weight(1f))
            Text(String.format(Locale.ROOT, "%.1f", value), color = if (enabled) Green else Muted,
                fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 12.dp))
        }
        SettingsValueSlider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SettingsValueSlider(value: Float, onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>, enabled: Boolean = true, onValueChangeFinished: (() -> Unit)? = null) {
    val thumbColor = if (enabled) Green else Muted.copy(alpha = .5f)
    val colors = SliderDefaults.colors(
        thumbColor = Green,
        activeTrackColor = Green,
        inactiveTrackColor = Soft,
        disabledThumbColor = Muted.copy(alpha = .5f),
        disabledActiveTrackColor = Muted.copy(alpha = .4f),
        disabledInactiveTrackColor = Muted.copy(alpha = .15f),
    )
    Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = colors,
        thumb = {
            // Center the painted capsule without inheriting the hit target's minimum size.
            Box(Modifier.size(width = 14.dp, height = 48.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 14.dp, height = 6.dp).background(thumbColor, RoundedCornerShape(3.dp)))
            }
        },
        track = { state ->
            SliderDefaults.Track(sliderState = state, modifier = Modifier.height(3.dp),
                enabled = enabled, colors = colors, drawStopIndicator = null,
                thumbTrackGapSize = 0.dp, trackInsideCornerSize = 1.5.dp)
        })
}

@Composable internal fun <T> SettingsChoices(options: List<T>, selected: T, label: (T) -> String,
    enabled: (T) -> Boolean = { true }, onSelect: (T) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(12.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { option ->
            val active = selected == option
            Surface(color = if (active) CardSurface else Color.Transparent, shape = RoundedCornerShape(9.dp),
                modifier = Modifier.weight(1f).widthIn(min = 72.dp)) {
                // The selected surface is the feedback; a ripple briefly obscures its color.
                Box(Modifier.heightIn(min = 44.dp).selectable(active,
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    enabled = enabled(option), role = Role.RadioButton,
                    onClick = { if (!active) onSelect(option) }).padding(horizontal = 8.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(label(option), color = when { !enabled(option) -> Muted; active -> Green; else -> Ink },
                        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable internal fun SettingsNavigationRow(label: String, summary: String, icon: Int? = null,
    enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = if (icon == null) 64.dp else 72.dp)
        .clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (icon != null) Icon(painterResource(icon), null, tint = Ink, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = if (enabled) Ink else Muted, fontSize = 16.sp, lineHeight = 22.sp,
                fontWeight = if (icon != null) FontWeight.Medium else FontWeight.Normal)
            SettingsHint(summary)
        }
        trailing?.invoke()
        Icon(painterResource(R.drawable.ic_settings_chevron), null, tint = Muted, modifier = Modifier.size(18.dp))
    }
}

private fun number(value: Float) = String.format(Locale.ROOT, "%.1f", value)

@Composable private fun SettingsOverview(settings: ControlSettings, vm: MainViewModel, navigate: (SettingsDestination) -> Unit) {
    SettingsGroup("控制方式") {
        SettingsChoices(MouseControlMode.entries, settings.mouseControlMode,
            { if (it == MouseControlMode.Touchpad) "触控板" else "体感鼠标" },
            { it == MouseControlMode.Touchpad || vm.gyroAvailable }, vm::setMouseControlMode)
        if (!vm.gyroAvailable) { Spacer(Modifier.height(8.dp)); SettingsHint("当前手机没有可用的陀螺仪。") }
    }
    SettingsGroup("鼠标操作") {
        SettingsNavigationRow("触控板", "灵敏度 ${number(settings.sensitivity)} · ${settings.touchpadAcceleration.mode.label}", R.drawable.ic_touchpad) { navigate(SettingsDestination.Touchpad) }
        SettingsDivider()
        SettingsNavigationRow("体感鼠标", if (vm.gyroAvailable) "灵敏度 ${number(settings.gyro.sensitivity)} · 防抖${if (settings.gyro.smoothing) "已开启" else "已关闭"}" else "当前设备不支持",
            R.drawable.ic_gyroscope) { navigate(SettingsDestination.Gyroscope) }
        SettingsDivider()
        SettingsNavigationRow("滚动与缩放", "滚动速度 ${number(settings.scrollSpeed)} · 双指手势", R.drawable.ic_settings_scroll) { navigate(SettingsDestination.Scroll) }
    }
    SettingsGroup("应用设置") {
        SettingsNavigationRow("外观与通用", "${settings.themeMode.label} · 屏幕常亮${if (settings.keepAwake) "已开启" else "已关闭"}", R.drawable.ic_settings_appearance) { navigate(SettingsDestination.Appearance) }
        SettingsDivider()
        SettingsNavigationRow("震动反馈", when { !vm.hapticsAvailable -> "当前设备不支持"; !settings.haptics.enabled -> "已关闭"; else -> "已开启 · ${settings.haptics.strength.label}" },
            R.drawable.ic_settings_haptic) { navigate(SettingsDestination.Haptics) }
        SettingsDivider()
        SettingsNavigationRow("连接与性能", "自动连接${if (settings.autoConnectLast) "已开启" else "已关闭"} · ${settings.reportRate.label}", R.drawable.ic_link) { navigate(SettingsDestination.Connection) }
    }
}

@Composable private fun TouchpadSettings(settings: ControlSettings, vm: MainViewModel) {
    SettingsGroup("移动") {
        SettingsSlider("基础灵敏度", settings.sensitivity, .4f..3.2f) { value -> vm.updateSettings { it.copy(sensitivity = value) } }
    }
    SettingsGroup("点击与拖动") {
        SettingsToggle("轻点触发左键", settings.tapToClick) { value -> vm.updateSettings { it.copy(tapToClick = value) } }
        SettingsDivider()
        SettingsToggle("双击按住拖动", settings.doubleTapHoldDrag, description = "轻点后再次按住并移动，松手结束。") { value -> vm.updateSettings { it.copy(doubleTapHoldDrag = value) } }
        SettingsDivider()
        SettingsToggle("长按拖动", settings.longPressDrag, description = "按住约 0.8 秒后移动，松手结束。") { value -> vm.updateSettings { it.copy(longPressDrag = value) } }
    }
    SettingsGroup("指针加速") {
        PointerAccelerationOptions(settings.touchpadAcceleration, gyroscope = false) { value -> vm.updateSettings { it.copy(touchpadAcceleration = value) } }
    }
}

@Composable private fun GyroscopeSettings(settings: ControlSettings, vm: MainViewModel) {
    val enabled = vm.gyroAvailable
    if (!enabled) SettingsHint("当前手机没有可用的陀螺仪，无法使用体感鼠标。")
    SettingsGroup("移动与防抖") {
        SettingsSlider("基础灵敏度", settings.gyro.sensitivity, .3f..3f, enabled) { value -> vm.updateSettings { it.copy(gyro = it.gyro.copy(sensitivity = value)) } }
        SettingsDivider()
        SettingsToggle("动态防抖", settings.gyro.smoothing, enabled, "慢动作加强防抖，快速转动减少迟滞。") { value -> vm.updateSettings { it.copy(gyro = it.gyro.copy(smoothing = value)) } }
    }
    SettingsGroup("方向") {
        SettingsToggle("左右反向", settings.gyro.reverseX, enabled) { value -> vm.updateSettings { it.copy(gyro = it.gyro.copy(reverseX = value)) } }
        SettingsDivider()
        SettingsToggle("上下反向", settings.gyro.reverseY, enabled) { value -> vm.updateSettings { it.copy(gyro = it.gyro.copy(reverseY = value)) } }
    }
    SettingsGroup("指针加速") {
        PointerAccelerationOptions(settings.gyro.acceleration, gyroscope = true, enabled = enabled) { value -> vm.updateSettings { it.copy(gyro = it.gyro.copy(acceleration = value)) } }
    }
}

@Composable private fun ScrollSettings(settings: ControlSettings, vm: MainViewModel) {
    SettingsGroup("速度与方向") {
        SettingsSlider("滚动速度", settings.scrollSpeed, .3f..3f) { value -> vm.updateSettings { it.copy(scrollSpeed = value) } }
        SettingsDivider()
        SettingsToggle("滚轮反向滚动", settings.reverseScroll) { value -> vm.updateSettings { it.copy(reverseScroll = value) } }
        SettingsDivider()
        SettingsToggle("双指反向滚动", settings.reverseTwoFingerScroll, description = "独立于滚轮方向，上下和左右滚动均适用。") { value -> vm.updateSettings { it.copy(reverseTwoFingerScroll = value) } }
    }
    SettingsGroup("双指操作") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsHint("双指上下或左右滑动滚动；本次手势锁定起步方向，换方向时抬手后重新滑动。")
            SettingsHint("双指张开放大、捏合缩小，缩放效果取决于电脑应用是否支持。")
        }
    }
}

private data class SettingsOption(val code: String, val label: String, val description: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SettingsSelectionSheet(title: String, options: List<SettingsOption>, selected: String,
    dismiss: () -> Unit, onSelect: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CardSurface, contentColor = Ink, sheetMaxWidth = 600.dp) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(title, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 12.dp))
            options.forEach { option ->
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).selectable(selected == option.code, role = Role.RadioButton,
                    onClick = { onSelect(option.code); dismiss() }).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(option.label, color = if (selected == option.code) Green else Ink, fontSize = 16.sp, lineHeight = 22.sp)
                        option.description?.let { SettingsHint(it) }
                    }
                    RadioButton(selected == option.code, onClick = null)
                }
            }
        }
    }
}

@Composable private fun HapticSettingsContent(settings: HapticSettings, vm: MainViewModel,
    selectedAction: HapticAction?, selectAction: (HapticAction?) -> Unit) {
    val enabled = settings.enabled && vm.hapticsAvailable
    val names = mapOf(HapticAction.LeftClick to "左键", HapticAction.RightClick to "右键",
        HapticAction.Scroll to "滚轮滚动", HapticAction.ScrollHold to "滚轮长按",
        HapticAction.Keyboard to "快捷键与发送", HapticAction.Backspace to "退格键")
    SettingsGroup {
        SettingsToggle("震动反馈", settings.enabled, vm.hapticsAvailable,
            if (vm.hapticsAvailable) "独立控制本应用的震动反馈。" else "当前设备无法提供震动反馈。") { value -> vm.updateHaptics { it.copy(enabled = value) } }
    }
    SettingsGroup("力度", action = {
        IconButton(onClick = vm::previewHapticStrength, enabled = enabled) {
            Icon(painterResource(R.drawable.ic_settings_preview), "试听当前力度",
                tint = if (enabled) Green else Muted, modifier = Modifier.size(22.dp))
        }
    }) {
        SettingsChoices(HapticStrength.entries, settings.strength, { it.label }, { enabled }) {
            value -> vm.updateHaptics { it.copy(strength = value) }; vm.previewHapticStrength()
        }
        if (settings.strength == HapticStrength.Custom) {
            Spacer(Modifier.height(12.dp))
            SettingsDivider()
            val amplitude = vm.hapticsAdjustAmplitude
            SettingsNumberSlider(if (amplitude) "自定义力度" else "单次震动时长",
                if (amplitude) settings.customAmplitudePercent.toDouble() else settings.customPulseMs.toDouble(),
                if (amplitude) 1.0..100.0 else 1.0..80.0, 1.0, if (amplitude) "%" else "毫秒", 0, enabled,
                onChangeFinished = vm::previewHapticStrength, compactInput = true) { value ->
                vm.updateHaptics {
                    if (amplitude) it.copy(customAmplitudePercent = value.roundToInt()) else it.copy(customPulseMs = value.roundToInt())
                }
            }
            SettingsHint(if (amplitude) "滑动或点击数字精确输入，松手后试听。" else
                "滑动或点击数字精确到 1 毫秒，松手后试听；双震的两次震动使用相同时长。")
        }
        if (vm.hapticsAvailable && !vm.hapticsAdjustAmplitude) {
            Spacer(Modifier.height(10.dp)); SettingsHint("这台手机通过震动长短区分力度，可点击试听选择手感。")
        }
    }
    SettingsGroup("操作震感") {
        names.keys.forEachIndexed { index, action ->
            if (index > 0) SettingsDivider()
            val pattern = settings.pattern(action)
            SettingsNavigationRow(names.getValue(action), pattern.label, enabled = enabled, trailing = {
                IconButton(onClick = { vm.previewHaptic(action) }, enabled = enabled && pattern != HapticPattern.Off) {
                    Icon(painterResource(R.drawable.ic_settings_preview), "试听${names.getValue(action)}震动",
                        tint = if (enabled && pattern != HapticPattern.Off) Green else Muted, modifier = Modifier.size(22.dp))
                }
            }) { selectAction(action) }
        }
        SettingsHint("每滚动一格反馈一次，快速滚动时合并反馈；长按震感单独设置。")
    }
    selectedAction?.takeIf { enabled }?.let { action ->
        SettingsSelectionSheet("${names.getValue(action)}震感", HapticPattern.entries.map { SettingsOption(it.code, it.label) },
            settings.pattern(action).code, { selectAction(null) }) { code ->
            val pattern = HapticPattern.entries.first { it.code == code }
            vm.updateHaptics { when (action) {
                HapticAction.LeftClick -> it.copy(leftClick = pattern)
                HapticAction.RightClick -> it.copy(rightClick = pattern)
                HapticAction.Scroll -> it.copy(scroll = pattern)
                HapticAction.ScrollHold -> it.copy(scrollHold = pattern)
                HapticAction.Keyboard -> it.copy(keyboard = pattern)
                HapticAction.Backspace -> it.copy(backspace = pattern)
                HapticAction.StrengthPreview -> it
            } }
        }
    }
}

@Composable private fun ConnectionSettings(settings: ControlSettings, vm: MainViewModel, sampling: () -> Unit) {
    var chooseRate by rememberSaveable { mutableStateOf(false) }
    val test by vm.touchSampling.state.collectAsState()
    SettingsGroup("连接") {
        SettingsToggle("自动连接上次电脑", settings.autoConnectLast,
            description = "发现上次成功连接的电脑后自动连接；手动断开后本次不再自动连接。") { value -> vm.updateSettings { it.copy(autoConnectLast = value) } }
        SettingsDivider()
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("上次电脑", color = Ink, fontSize = 16.sp, lineHeight = 22.sp)
            SettingsHint(if (settings.address.isBlank()) "尚未记录，先在主页连接一次。" else settings.lastComputerName.ifBlank { settings.address })
        }
    }
    SettingsGroup("性能") {
        SettingsNavigationRow("鼠标回报率", "${settings.reportRate.label} · 间隔 ${settings.reportRate.intervalLabel}") { chooseRate = true }
        SettingsDivider()
        SettingsNavigationRow("触摸采样测试", test.result?.takeIf { test.phase == SamplingPhase.Finished && it.sufficient }?.let {
            "上次约 ${String.format(Locale.ROOT, "%.0f", it.sampleHz)} Hz · 建议 ${it.suggestedRate.label}"
        } ?: "在手机本地测量，无需连接电脑", onClick = sampling)
        Spacer(Modifier.height(8.dp))
        SettingsHint("默认 250 Hz。实际更新受手机触摸事件和 Wi-Fi 影响，较高档位可能增加耗电。")
    }
    if (chooseRate) SettingsSelectionSheet("鼠标回报率", MouseReportRate.entries.map {
        SettingsOption(it.hz.toString(), it.label, "间隔 ${it.intervalLabel}${if (it == MouseReportRate.Hz250) " · 默认" else ""}")
    }, settings.reportRate.hz.toString(), { chooseRate = false }) { code -> vm.updateSettings { it.copy(reportRate = MouseReportRate.fromHz(code.toInt())) } }
}

@Composable private fun AppearanceSettings(settings: ControlSettings, vm: MainViewModel) {
    SettingsGroup("主题") {
        SettingsChoices(ThemeMode.entries, settings.themeMode, { it.label }) { value -> vm.updateSettings { it.copy(themeMode = value) } }
        Spacer(Modifier.height(10.dp))
        SettingsHint(if (settings.themeMode == ThemeMode.System) "随手机系统自动切换浅色和深色。" else "始终使用${settings.themeMode.label}主题。")
    }
    SettingsGroup("通用") {
        SettingsToggle("保持屏幕常亮", settings.keepAwake, description = "连接电脑时保持屏幕亮起。") { value -> vm.updateSettings { it.copy(keepAwake = value) } }
        SettingsDivider()
        SettingsToggle("全屏说明入口", settings.fullscreenHelpEnabled, description = "在全屏左上角显示说明按钮。") { value -> vm.updateSettings { it.copy(fullscreenHelpEnabled = value) } }
    }
}
