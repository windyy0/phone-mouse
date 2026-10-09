package com.windy.phonemouse.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.round

@Composable internal fun PointerAccelerationOptions(settings: PointerAccelerationSettings, gyroscope: Boolean,
    enabled: Boolean = true, onChange: (PointerAccelerationSettings) -> Unit) {
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(gyroscope) {
        mutableStateOf(true)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PointerAccelerationModeOptions(settings, gyroscope, enabled, onChange = onChange)
        if (settings.mode != PointerAccelerationMode.Off) {
            SettingsDivider()
            PointerAccelerationStrengthOptions(settings, gyroscope, enabled,
                expanded = expanded, onExpandedChange = { expanded = it }, onChange = onChange)
        }
    }
}

@Composable internal fun PointerAccelerationModeOptions(settings: PointerAccelerationSettings, gyroscope: Boolean,
    enabled: Boolean = true, compact: Boolean = false, singleRow: Boolean = false, showHint: Boolean = true,
    onChange: (PointerAccelerationSettings) -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val selectMode: (PointerAccelerationMode) -> Unit = { mode ->
        focus.clearFocus(); keyboard?.hide()
        onChange(settings.copy(mode = mode))
    }
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
        if (singleRow) {
            SettingsSegmentedChoices(PointerAccelerationMode.entries, settings.mode, { it.label }, { enabled }, onSelect = selectMode)
        } else {
            SettingsChoices(PointerAccelerationMode.entries, settings.mode, { it.label }, { enabled }, selectMode)
        }
        if (showHint) SettingsHint(when (settings.mode) {
            PointerAccelerationMode.Off -> "按基础灵敏度保持固定移动比例。"
            PointerAccelerationMode.Speed -> "慢动作精细定位，快速动作覆盖更大范围。"
            PointerAccelerationMode.Distance -> if (gyroscope)
                "连续转动的角度越大，移动越快；停顿、反向或暂停后重新开始。"
                else "连续滑动的距离越远，移动越快；抬手、停顿或反向后重新开始。"
        })
    }
}

@Composable internal fun PointerAccelerationStrengthOptions(settings: PointerAccelerationSettings, gyroscope: Boolean,
    enabled: Boolean = true, compact: Boolean = false, expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit, onChange: (PointerAccelerationSettings) -> Unit) {
    val kind = if (gyroscope) PointerInputKind.Gyroscope else PointerInputKind.Touchpad
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val sectionSpacing = if (compact) 8.dp else 12.dp
    val customTopPadding = if (compact) 6.dp else 10.dp
    val customHorizontalPadding = if (compact) 10.dp else 12.dp
    val customVerticalPadding = if (compact) 4.dp else 8.dp
    val customRowHeight = if (compact) 44.dp else 48.dp
    Column(verticalArrangement = Arrangement.spacedBy(sectionSpacing)) {
        if (settings.mode != PointerAccelerationMode.Off) {
            Text("加速强度", color = Ink, fontSize = 16.sp, lineHeight = 22.sp)
            SettingsSegmentedChoices(PointerAccelerationStrength.entries, settings.strength, { it.label }, { enabled }) { strength ->
                focus.clearFocus(); keyboard?.hide()
                if (strength == PointerAccelerationStrength.Custom && !compact) onExpandedChange(true)
                onChange(settings.selectStrength(strength, kind))
            }
            if (settings.strength == PointerAccelerationStrength.Custom) {
                Surface(Modifier.fillMaxWidth().padding(top = customTopPadding), color = Soft, shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = customHorizontalPadding, vertical = customVerticalPadding)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = customRowHeight)
                            .clickable(enabled = enabled) {
                                focus.clearFocus(); keyboard?.hide(); onExpandedChange(!expanded)
                            }, verticalAlignment = Alignment.CenterVertically) {
                            Text("自定义曲线", color = if (enabled) Ink else Muted, fontSize = 16.sp,
                                modifier = Modifier.weight(1f))
                            Icon(painterResource(if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down),
                                if (expanded) "收起曲线参数" else "展开曲线参数", tint = Muted, modifier = Modifier.size(20.dp))
                        }
                        if (expanded) {
                            val curve = settings.curve(kind)
                            val limits = curveLimits(kind, settings.mode)
                            val distance = settings.mode == PointerAccelerationMode.Distance
                            fun display(value: Double) = if (gyroscope) Math.toDegrees(value) else value
                            fun input(value: Double) = if (gyroscope) Math.toRadians(value) else value
                            fun change(value: PointerAccelerationCurve) = onChange(settings.withCurve(value.sanitized(kind, settings.mode)))
                            SettingsHint("滑条调节，点击数字可精确输入；速度和距离模式分别记忆参数。")
                            SettingsNumberSlider(if (distance) "起步倍率" else "低速倍率", curve.minimum, .25..1.5, .05,
                                "倍", 2, enabled, compactInput = compact) { change(curve.copy(minimum = it)) }
                            SettingsDivider()
                            SettingsNumberSlider("最高倍率", curve.maximum, maxOf(1.0, curve.minimum)..6.0, .05,
                                "倍", 2, enabled, compactInput = compact) { change(curve.copy(maximum = it)) }
                            SettingsDivider()
                            val unit = if (gyroscope) (if (distance) "°" else "°/秒") else (if (distance) "dp" else "dp/秒")
                            val step = if (gyroscope && distance) .1 else 1.0
                            val startMaximum = minOf(display(limits.maximumStart), display(curve.end - limits.gap))
                            SettingsNumberSlider(if (distance) "加速起始距离" else "加速起始速度", display(curve.start),
                                0.0..startMaximum, step, unit, 2, enabled, compactInput = compact) { change(curve.copy(start = input(it))) }
                            SettingsDivider()
                            // Keep the slider's scale fixed while validating against the current start threshold.
                            SettingsNumberSlider(if (distance) "达到最高倍率的距离" else "达到最高倍率的速度", display(curve.end),
                                display(limits.gap)..display(limits.maximumEnd), step, unit, 2, enabled, compactInput = compact,
                                inputRange = display(curve.start + limits.gap)..display(limits.maximumEnd)) {
                                change(curve.copy(end = input(it)))
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable internal fun SettingsNumberSlider(label: String, value: Double, range: ClosedFloatingPointRange<Double>,
    step: Double, unit: String, decimals: Int, enabled: Boolean, onChangeFinished: (() -> Unit)? = null,
    compactInput: Boolean = false, inputRange: ClosedFloatingPointRange<Double> = range,
    onChange: (Double) -> Unit) {
    fun format(number: Double) = String.format(Locale.ROOT, "%.${decimals}f", number)
    fun parse(text: String) = text.toDoubleOrNull()?.takeIf {
        it.isFinite() && it in inputRange && (decimals > 0 || it == round(it))
    }
    var draft by remember(label, unit) { mutableStateOf(format(value)) }
    var focused by remember(label, unit) { mutableStateOf(false) }
    val currentValue by rememberUpdatedState(value)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(value, focused) { if (!focused) draft = format(value) }
    val parsed = parse(draft)
    val invalid = focused && draft.isNotEmpty() && parsed == null
    val rangeHint = "${format(inputRange.start)}–${format(inputRange.endInclusive)}"
    val inputModifier = Modifier.semantics { contentDescription = localize(label) }.onFocusChanged {
        if (focused && !it.isFocused) draft = format(currentValue)
        focused = it.isFocused
    }
    val editDraft: (String) -> Unit = { text ->
        draft = text.replace(',', '.')
        parse(draft)?.let(onChange)
    }
    val inputOptions = KeyboardOptions(
        keyboardType = if (decimals == 0) KeyboardType.Number else KeyboardType.Decimal,
        imeAction = ImeAction.Done,
    )
    val inputActions = KeyboardActions(onDone = {
        focus.clearFocus(); keyboard?.hide()
        if (parsed != null) onChangeFinished?.invoke()
    })
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), color = if (enabled) Ink else Muted,
            fontSize = if (compactInput) 14.sp else 16.sp,
            lineHeight = if (compactInput) 20.sp else 22.sp,
            maxLines = if (compactInput) 2 else Int.MAX_VALUE)
        if (compactInput) {
            val accent = if (invalid) MaterialTheme.colorScheme.error else Green
            val shape = RoundedCornerShape(10.dp)
            Column(Modifier.width(116.dp), horizontalAlignment = Alignment.End) {
                BasicTextField(value = draft, onValueChange = editDraft,
                    modifier = inputModifier.fillMaxWidth().heightIn(min = 42.dp).semantics {
                        if (invalid) error(localize("请输入 $rangeHint $unit"))
                    }, enabled = enabled, singleLine = true,
                    textStyle = TextStyle(color = if (enabled) accent else Muted, fontSize = 16.sp,
                        lineHeight = 22.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(accent), keyboardOptions = inputOptions, keyboardActions = inputActions,
                    decorationBox = { innerTextField ->
                        Box(Modifier.heightIn(min = 42.dp), contentAlignment = Alignment.Center) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 34.dp)
                                .background(if (focused) PaleGreen else Soft, shape)
                                .border(1.dp, if (focused && enabled) accent.copy(alpha = .6f) else Color.Transparent, shape)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { innerTextField() }
                                Text(unit, color = if (enabled) Muted else Muted.copy(alpha = .6f), fontSize = 12.sp)
                            }
                        }
                    })
                if (invalid) Text(rangeHint, color = MaterialTheme.colorScheme.error, fontSize = 11.sp,
                    modifier = Modifier.padding(end = 8.dp, top = 2.dp))
            }
        } else {
            OutlinedTextField(value = draft, onValueChange = editDraft, modifier = inputModifier.width(132.dp),
                enabled = enabled, singleLine = true, isError = invalid,
                shape = RoundedCornerShape(12.dp), textStyle = TextStyle(fontSize = 14.sp), suffix = { Text(unit, fontSize = 12.sp) },
                keyboardOptions = inputOptions, keyboardActions = inputActions,
                supportingText = if (invalid) { { Text(rangeHint, fontSize = 10.sp) } } else null)
        }
    }
    SettingsValueSlider(value = value.toFloat(), valueRange = range.start.toFloat()..range.endInclusive.toFloat(), enabled = enabled,
        onValueChangeFinished = onChangeFinished,
        onValueChange = { raw ->
            focus.clearFocus(); keyboard?.hide()
            onChange((round(raw / step) * step).coerceIn(inputRange.start, inputRange.endInclusive))
        })
}
