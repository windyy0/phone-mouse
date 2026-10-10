package com.windy.phonemouse.app

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp

@Composable internal fun LaserToggleButton(state: UiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val presentation = state.presentation
    val available = state.connection.connected && presentation.supported
    val enabled = available && !presentation.switching
    val label = when {
        !state.connection.connected -> "请先连接电脑"
        !presentation.supported -> "接收端不支持激光笔，请更新 Windows 接收端。"
        presentation.switching -> "正在切换演示模式"
        presentation.laser -> "关闭激光笔"
        else -> "开启激光笔"
    }
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.semantics { selected = presentation.laser }) {
        Icon(painterResource(R.drawable.ic_laser), localize(label), modifier = Modifier.size(22.dp),
            tint = if (!available) Muted else if (presentation.laser) Color(0xFF000000L or state.settings.laser.color.rgb.toLong()) else Green)
    }
}
