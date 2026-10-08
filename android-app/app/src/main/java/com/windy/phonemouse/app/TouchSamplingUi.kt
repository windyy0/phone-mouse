package com.windy.phonemouse.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale

@Composable internal fun TouchSamplingContent(vm: MainViewModel) {
    val colors = LocalAppColors.current
    val appLanguage = LocalAppLanguage.current
    val state by vm.touchSampling.state.collectAsState()
    val settings by vm.state.collectAsState()
    DisposableEffect(Unit) { onDispose { vm.touchSampling.cancel() } }
    val active = state.phase == SamplingPhase.Armed || state.phase == SamplingPhase.Running
    SettingsGroup("测试") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsHint("用一根手指在测试区连续画圈或来回滑动 5 秒。触屏后开始计时，抬手后可继续。")
            Text(when (state.phase) {
                SamplingPhase.Ready -> "点击开始测试，再在下方滑动。"
                SamplingPhase.Armed -> "已准备好，触摸测试区开始。"
                SamplingPhase.Running -> "正在测试 · 剩余 ${state.secondsLeft} 秒"
                SamplingPhase.Finished -> if (state.result?.sufficient == true) "测试完成" else "有效滑动不足，请重新测试。"
                SamplingPhase.Cancelled -> "测试已取消，可以重新开始。"
            }, color = Green, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
            AndroidView(
                factory = { context -> TouchSamplingView(context).also { it.onSample = vm.touchSampling::onTouch } },
                update = { it.setColors(colors); it.setLanguage(appLanguage); it.setTesting(active) },
                modifier = Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(12.dp)),
            )
            Button(onClick = { if (active) vm.touchSampling.cancel() else vm.touchSampling.arm() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
                Text(if (active) "取消测试" else if (state.phase == SamplingPhase.Ready) "开始测试" else "重新测试")
            }
            SettingsHint("测试仅在手机本地进行，不会移动电脑光标。多指、抬手间隔及超过 100 ms 的停顿不计入测量。")
        }
    }
    state.result?.takeIf { state.phase == SamplingPhase.Finished }?.let { result ->
        SettingsGroup("测试结果") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (result.sufficient) {
                    Text("约 ${hz(result.sampleHz)} Hz", color = Green, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    SettingsHint("触摸样本频率（含系统合并的历史触点）")
                    SettingsDivider()
                    Text("平均采样间隔  ${String.format(Locale.US, "%.2f", result.averageIntervalMs)} ms", color = Ink, fontSize = 16.sp)
                    Text("事件送达频率  约 ${hz(result.deliveryHz)} Hz", color = Ink, fontSize = 16.sp)
                }
                SettingsHint("移动触点 ${result.samples} 个 · 有效采样时长 ${String.format(Locale.US, "%.1f", result.activeNanos / 1_000_000_000.0)} 秒")
                if (result.sufficient) {
                    SettingsHint("建议先用 ${result.suggestedRate.label}。选择档位主要参考事件送达频率，更高档位不会增加新触点。")
                    OutlinedButton(onClick = { vm.updateSettings { it.copy(reportRate = result.suggestedRate) } },
                        enabled = settings.settings.reportRate != result.suggestedRate,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
                        Text(if (settings.settings.reportRate == result.suggestedRate) "已使用 ${result.suggestedRate.label}" else "使用建议档位 ${result.suggestedRate.label}")
                    }
                } else SettingsHint("需要至少 2 秒有效采样和 100 个采样间隔，请用单指保持滑动后再试。")
                if (result.skippedMultipleFingers) SettingsHint("检测到多指，该段已跳过；可用单指再测一次。")
                if (result.coarseTimestamps) SettingsHint("当前 Android 仅提供毫秒触摸时间戳，结果为区间估算。")
            }
        }
    }
    SettingsHint("这里测量应用收到的触摸数据，不代表触屏芯片的最高采样率、屏幕刷新率或鼠标实际回报率。结果可能受省电模式、系统负载和手机温度影响。")
}

private fun hz(value: Double) = String.format(Locale.US, "%.0f", value)
/** Match the normal touchpad's buffered dispatch and historical-point handling. */
internal class TouchSamplingView(context: Context) : View(context) {
    var onSample: (MotionEvent) -> Unit = {}
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trail = Path()
    private var testing = false
    private var points = 0
    private val density = resources.displayMetrics.density
    private var colors = AppColors.Light
    private var appLanguage = systemLanguage()

    internal fun setColors(value: AppColors) {
        if (colors == value) return
        colors = value
        invalidate()
    }

    internal fun setLanguage(value: AppLanguage) {
        if (appLanguage == value) return
        appLanguage = value
        contentDescription = Localization.text("触摸采样测试区", appLanguage)
        invalidate()
    }

    init { contentDescription = localize("触摸采样测试区") }

    fun setTesting(value: Boolean) {
        if (testing == value) return
        testing = value; trail.reset(); points = 0; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor((if (colors.dark) colors.soft else colors.paleGreen).toArgb())
        paint.color = colors.muted.toArgb(); paint.style = Paint.Style.FILL
        paint.textSize = 15f * density; paint.textAlign = Paint.Align.CENTER
        canvas.drawText(Localization.text(if (testing) "在这里连续滑动" else "触摸测试区", appLanguage), width / 2f, height / 2f, paint)
        paint.color = colors.green.toArgb(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f * density
        canvas.drawPath(trail, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
            trail.reset(); points = 0; trail.moveTo(event.x, event.y)
        }
        onSample(event)
        if (testing && event.actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount == 1) {
            if (points > 300) { trail.reset(); trail.moveTo(event.x, event.y); points = 0 }
            for (index in 0 until event.historySize) { trail.lineTo(event.getHistoricalX(0, index), event.getHistoricalY(0, index)); points++ }
            trail.lineTo(event.x, event.y); points++
            postInvalidateOnAnimation()
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
            parent?.requestDisallowInterceptTouchEvent(false)
        return true
    }
}
