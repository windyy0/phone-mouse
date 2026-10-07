package com.windy.phonemouse.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val FullscreenHelpTips = listOf(
    HelpTip("触控板单击", "单指滑动移动光标，轻点触发左键；双指轻点触发右键。"),
    HelpTip("双击与拖动", "在同一位置连续轻点两次触发双击；第二次按住并滑动可拖动。"),
    HelpTip("体感鼠标", "点体感按钮开始，转动手机移动光标；按住触摸区域归位调整，松手继续。"),
    HelpTip("进入黑屏", "悬浮菜单按钮可直接黑屏；普通页面和分裂布局需先开始体感。"),
    HelpTip("退出黑屏", "黑屏保持当前控制；三指按住约 0.6 秒，或按手机返回退出。"),
)

@Composable internal fun FullscreenHelpDialog(onClose: () -> Unit, onDisable: () -> Unit) {
    var confirmDisable by remember { mutableStateOf(false) }
    if (confirmDisable) {
        AlertDialog(onDismissRequest = { confirmDisable = false },
            containerColor = CardSurface,
            title = { Text("永久关闭说明入口？", color = Ink) },
            text = {
                Text("关闭后隐藏全屏说明入口。可在设置 → 外观与通用 → 全屏说明入口重新打开。",
                    color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
            },
            confirmButton = { TextButton(onClick = onDisable) { Text("确认关闭") } },
            dismissButton = { TextButton(onClick = { confirmDisable = false }) { Text("取消") } })
        return
    }
    AlertDialog(onDismissRequest = onClose,
        containerColor = CardSurface,
        title = { Text("全屏操作说明", color = Ink, fontSize = 18.sp, lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FullscreenHelpTips.forEach { tip ->
                    HelpTipText(tip)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } },
        dismissButton = {
            TextButton(onClick = { confirmDisable = true }, colors = ButtonDefaults.textButtonColors(contentColor = Muted)) {
                Text("永久关闭")
            }
        })
}
