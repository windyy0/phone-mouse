package com.windy.phonemouse.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI
import kotlin.math.exp

@Composable internal fun LaserSettingsContent(settings: LaserSettings, vm: MainViewModel) {
    val ui by vm.state.collectAsState()
    SettingsHint("激光点跟随系统鼠标，点击、拖动和滚动照常使用。开启时暂时隐藏系统指针，关闭后恢复。")
    SettingsGroup("外观预览") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LaserPreview(settings, false, Modifier.weight(1f))
            LaserPreview(settings, true, Modifier.weight(1f))
        }
    }
    SettingsGroup("大小") {
        LaserSettingsSlider("激光大小", settings.sizePercent, 50..300, step = 5, unit = "%",
            onChangeFinished = vm::flushLaserSettings,
            description = "光点、光晕和拖尾粗细同步缩放。") { value ->
            vm.updateSettings { it.copy(laser = it.laser.copy(sizePercent = value)) }
        }
        if (ui.connection.connected && ui.presentation.supported && !ui.presentation.sizeSupported) {
            SettingsHint("当前接收端不支持大小调整，更新 Windows 接收端后生效。")
        } else if (ui.connection.connected && ui.presentation.sizeSupported && !ui.presentation.extendedRangeSupported && settings.sizePercent > 200) {
            SettingsHint("当前接收端最高支持 200% 大小，暂按 200% 生效；更新 Windows 接收端后支持 300%。")
        }
    }
    SettingsGroup("颜色") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LaserColor.entries.chunked(3).forEach { colors ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    colors.forEach { color ->
                        val selected = settings.color == color
                        Surface(onClick = { vm.updateSettings { it.copy(laser = it.laser.copy(color = color)) } },
                            modifier = Modifier.weight(1f).heightIn(min = 72.dp).semantics { this.selected = selected; role = Role.RadioButton },
                            color = if (selected) PaleGreen else Soft, shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (selected) Green else LocalAppColors.current.outline)) {
                            Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                val laserColor = Color(0xFF000000L or color.rgb.toLong())
                                Box(Modifier.size(28.dp).background(laserColor.copy(alpha = .18f), CircleShape), contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(12.dp).background(laserColor, CircleShape))
                                }
                                Text(color.label, fontSize = 13.sp, color = Ink)
                            }
                        }
                    }
                }
            }
        }
    }
    SettingsGroup("拖尾与淡出") {
        SettingsToggle("显示拖尾", settings.trail, description = "快移拖尾更长，慢移更短，停止后逐渐消失。") { enabled ->
            vm.updateSettings { it.copy(laser = it.laser.copy(trail = enabled)) }
        }
        SettingsDivider()
        LaserSettingsSlider("最长长度", settings.trailLengthPx, 20..300, step = 10, unit = " px", enabled = settings.trail,
            onChangeFinished = vm::flushLaserSettings,
            description = "限制拖尾最长长度，实际长度仍随移动速度变化。") { value ->
            vm.updateSettings { it.copy(laser = it.laser.copy(trailLengthPx = value)) }
        }
        if (ui.connection.connected && ui.presentation.supported && !ui.presentation.trailLengthSupported) {
            SettingsHint("当前接收端不支持拖尾长度调整，更新 Windows 接收端后生效。")
        } else if (ui.connection.connected && ui.presentation.trailLengthSupported && !ui.presentation.extendedRangeSupported && settings.trailLengthPx > 200) {
            SettingsHint("当前接收端最长支持 200 px 拖尾，暂按 200 px 生效；更新 Windows 接收端后支持 300 px。")
        }
        SettingsDivider()
        SettingsSlider("淡出时长（秒）", settings.fadeMs / 1000f, .1f..3f, enabled = settings.trail,
            description = "只淡出拖尾，激光点始终可见。") { seconds ->
            vm.updateSettings { it.copy(laser = it.laser.copy(fadeMs = (seconds * 10).roundToInt() * 100)) }
        }
    }
}

@Composable private fun LaserSettingsSlider(label: String, value: Int, range: IntRange, step: Int, unit: String,
    description: String, enabled: Boolean = true, onChangeFinished: () -> Unit, onChange: (Int) -> Unit) {
    var dragValue by remember { mutableStateOf<Float?>(null) }
    fun rounded(raw: Float) = ((raw / step).roundToInt() * step).coerceIn(range)
    LaunchedEffect(enabled) { if (!enabled) dragValue = null }
    SettingsSlider(label, dragValue ?: value.toFloat(), range.first.toFloat()..range.last.toFloat(), enabled = enabled,
        valueLabel = "$value$unit", description = description, onValueChangeFinished = {
            dragValue?.let { onChange(rounded(it)) }
            dragValue = null
            onChangeFinished()
        }) { raw ->
        // Keep the thumb under the finger; rounded settings must not reset its drag position.
        dragValue = raw
        val next = rounded(raw)
        if (next != value) onChange(next)
    }
}

@Composable private fun LaserPreview(settings: LaserSettings, dark: Boolean, modifier: Modifier) {
    Surface(modifier, color = if (dark) Color(0xFF20252B) else Color(0xFFF7F8FA),
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, LocalAppColors.current.outline)) {
        Column(Modifier.padding(10.dp)) {
            Text(if (dark) "深色" else "浅色", fontSize = 12.sp,
                color = if (dark) Color(0xFFCAD0D7) else Color(0xFF626B75))
            Canvas(Modifier.fillMaxWidth().height(70.dp)) {
                val factor = settings.sizePercent / 100f
                val color = Color(0xFF000000L or settings.color.rgb.toLong())
                val halo = 10.dp.toPx() * factor
                val head = Offset(size.width - halo - 4.dp.toPx(), size.height / 2f)
                if (settings.trail) {
                    val startX = (head.x - settings.trailLengthPx.dp.toPx()).coerceAtLeast(4.dp.toPx())
                    fun at(t: Float) = Offset(startX + (head.x - startX) * t,
                        head.y + sin(t * PI).toFloat() * 12.dp.toPx())
                    fun ribbon(width: Float): Path {
                        val points = (0..48).map { at(it / 48f) }
                        val offsets = points.indices.map { index ->
                            val tangent = points[(index + 1).coerceAtMost(48)] - points[(index - 1).coerceAtLeast(0)]
                            val norm = tangent.getDistance().coerceAtLeast(1f)
                            val t = index / 48f
                            Offset(-tangent.y / norm, tangent.x / norm) *
                                (1.5.dp.toPx() * factor * sqrt(t) * width)
                        }
                        return Path().apply {
                            val first = points[0] + offsets[0]
                            moveTo(first.x, first.y)
                            points.indices.drop(1).forEach { index ->
                                val p = points[index] + offsets[index]; lineTo(p.x, p.y)
                            }
                            points.indices.reversed().forEach { index ->
                                val p = points[index] - offsets[index]; lineTo(p.x, p.y)
                            }
                            close()
                        }
                    }
                    drawPath(ribbon(2.4f), Brush.linearGradient(0f to Color.Transparent,
                        1f to color.copy(alpha = .42f), start = at(0f), end = head))
                    drawPath(ribbon(1f), Brush.linearGradient(0f to Color.Transparent,
                        .4f to color.copy(alpha = .7f), 1f to color, start = at(0f), end = head))
                }
                val stops = (0..40).map { index ->
                    val d = index / 4f
                    val body = (d - 1.2f) / 4f
                    val gaussian = if (d <= 1.2f) 1f else exp(-body * body)
                    val edge = ((d - 4.5f) / 3f).coerceIn(0f, 1f)
                    val alpha = gaussian * (1f - edge * edge * (3f - 2f * edge))
                    val t = ((d - .2f) / 1.5f).coerceIn(0f, 1f)
                    val light = 1f - t * t * (3f - 2f * t)
                    val glowColor = Color(color.red + (1f - color.red) * light,
                        color.green + (1f - color.green) * light, color.blue + (1f - color.blue) * light,
                        alpha)
                    index / 40f to glowColor
                }.toTypedArray()
                drawCircle(Brush.radialGradient(*stops, center = head, radius = halo), radius = halo, center = head)
            }
        }
    }
}
