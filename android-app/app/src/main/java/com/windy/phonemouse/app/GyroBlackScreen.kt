package com.windy.phonemouse.app

import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner

internal val GyroBlackScreenHelpTips = listOf(
    HelpTip("进入黑屏", "悬浮菜单按钮可直接黑屏；普通页面和分裂布局需先开始体感。"),
    HelpTip("退出黑屏", "黑屏保持当前控制；三指按住约 0.6 秒，或按手机返回退出。"),
)

@Composable internal fun KeepScreenAwake(window: Window, enabled: Boolean) {
    DisposableEffect(window, enabled) {
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/** Stays in the same foreground Activity, with no persisted brightness changes. */
@Composable internal fun GyroBlackScreen(window: Window, onExit: () -> Unit,
    touchpad: @Composable (Modifier, Boolean) -> Unit) {
    val focused = LocalWindowInfo.current.isWindowFocused
    val exit by rememberUpdatedState(onExit)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(window, lifecycleOwner) {
        val originalBrightness = window.attributes.screenBrightness
        window.attributes = window.attributes.apply { screenBrightness = 0f }
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { exit() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            window.attributes = window.attributes.apply { screenBrightness = originalBrightness }
        }
    }
    LaunchedEffect(focused) { if (!focused) exit() }
    BackHandler(onBack = onExit)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        touchpad(Modifier.fillMaxSize(), focused)
    }
}
