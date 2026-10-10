package com.windy.phonemouse.app

import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

data class UiState(
    val presentation: PresentationState = PresentationState(),
    val settings: ControlSettings = ControlSettings(),
    val connection: ConnectionState = ConnectionState(),
    val discovered: List<DiscoveredServer> = emptyList(),
    val selectedServer: DiscoveredServer? = null,
    val text: String = "",
    val sending: Boolean = false,
    val liveText: String = "",
    val livePaused: Boolean = false,
    val retainedText: String = "",
    val notice: String? = null,
    val connectionError: String? = null,
    val gyroPaused: Boolean = true,
    val gyroActive: Boolean = false,
    val gyroRepositioning: Boolean = false,
    val gyroBlocked: Boolean = true,
    val gyroBlackScreen: Boolean = false,
    val gyroDisplayRotation: Int = 0,
) {
    internal val effectiveGyroSettings: GyroSettings
        get() = settings.effectiveGyroSettings(gyroDisplayRotation)
    internal val touchpadMotionEnabled: Boolean
        get() = settings.mouseControlMode == MouseControlMode.Touchpad || (gyroPaused && !gyroActive)
    internal val gyroRepositionEnabled: Boolean
        get() = settings.mouseControlMode == MouseControlMode.Gyroscope && connection.connected &&
            gyroActive && !gyroPaused && !gyroBlocked
    internal val canEnterGyroBlackScreen: Boolean
        get() = settings.mouseControlMode == MouseControlMode.Gyroscope && connection.connected &&
            gyroActive && !gyroPaused && !gyroBlocked
    internal val canEnterBlackScreen: Boolean
        get() = canEnterGyroBlackScreen || (settings.fullscreenMode && !settings.controlsSplit &&
            connection.connected && !gyroBlocked)
}

class MainViewModel(context: Context) : ViewModel() {
    internal val touchSampling = TouchSamplingSession(viewModelScope)
    private val store = SettingsStore(context.applicationContext)
    private val updateSettingsLoaded = CompletableDeferred<Unit>()
    private val appUpdate = AppUpdateChecker(store)
    internal val appUpdateState: StateFlow<AppUpdateState> = appUpdate.state
    internal val appUpdatePrompt: StateFlow<AppUpdateInfo?> = appUpdate.prompt
    internal fun dismissUpdatePrompt() = appUpdate.dismissPrompt()
    internal fun dismissUpdateError() = appUpdate.dismissError()
    private val haptics = AppHaptics(context.applicationContext)
    internal val hapticsAvailable get() = haptics.available
    internal val hapticsAdjustAmplitude get() = haptics.adjustsAmplitude
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var foreground = false
    private var resumeGyroAfterUiBlock = false
    private val gyroscope = GyroscopeInput(context.applicationContext, ::moveFromGyro) { message ->
        resumeGyroAfterUiBlock = false
        _state.update { it.copy(gyroPaused = true, gyroActive = false, gyroRepositioning = false, gyroBlackScreen = false, notice = message) }
    }
    internal val gyroAvailable get() = gyroscope.available
    // Menus temporarily stop the sensor, but the same control session resumes
    // afterward. Keep its orientation stable through those interruptions.
    internal val gyroRotationLocked get() = _state.value.gyroActive || resumeGyroAfterUiBlock
    private var saveJob: Job? = null
    private val keyboardDelivery = KeyboardDeliveryQueue()
    private var activeTextId: Long? = null
    private var keyboardEpoch = 0L
    private val autoConnect = AutoConnectPolicy()
    private val discovery = DiscoveryClient { servers -> viewModelScope.launch {
        _state.update { it.copy(discovered = servers) }
        connectLastIfDiscovered()
    } }
    private val client = RemoteClient(
        onState = { value -> viewModelScope.launch {
            if (!value.connected) pauseGyro()
            if (!value.connected) stopKeyboardDelivery("连接已暂停，未确认文字已保留；请检查电脑后再发送。")
            _state.update {
                it.copy(connection = value, connectionError = if (value.connected || value.phase == "connecting") null else it.connectionError)
            }
            if (value.connected) rememberConnectedComputer()
            syncGyro()
        } },
        onNotice = { value -> _state.update {
            it.copy(notice = value, connectionError = if (it.selectedServer != null && !it.connection.connected) value else it.connectionError)
        } },
        onTextResult = { id, _, error -> viewModelScope.launch { completeKeyboardText(id, error) } },
        onPresentation = { value, reason -> viewModelScope.launch {
            if (reason != null) pauseGyro()
            _state.update { it.copy(presentation = value, notice = when (reason) {
                "tray" -> "电脑已退出演示模式。"
                "lease_expired" -> "激光连接心跳超时，演示模式已关闭。"
                "overlay_error" -> "激光显示异常，已恢复系统鼠标。"
                else -> it.notice
            }) }
            syncGyro()
        } },
    )

    init {
        discovery.start()
        viewModelScope.launch {
            val value = store.settings.first()
            val lastUpdateCheckAt = store.lastUpdateCheckAt.first()
            client.setReportRate(value.reportRate)
            client.setLaserSettings(value.laser)
            _state.update { old -> old.copy(settings = value) }
            appUpdate.restoreSchedule(value.updateCheckFrequency, lastUpdateCheckAt)
            updateSettingsLoaded.complete(Unit)
            if (foreground) appUpdate.maybeCheck(viewModelScope)
            autoConnect.settingsReady = true
            connectLastIfDiscovered()
        }
    }

    fun text(value: String) { if (!_state.value.sending) _state.update { it.copy(text = value) } }
    internal fun liveText(value: String) { _state.update { it.copy(liveText = value) } }
    internal fun inputMode(mode: TextInputMode) { if (!_state.value.sending) updateSettings { it.copy(inputMode = mode) } }
    fun clearNotice() { _state.update { it.copy(notice = null) } }

    internal fun checkForUpdate() {
        viewModelScope.launch {
            updateSettingsLoaded.await()
            appUpdate.check(viewModelScope, manual = true)
        }
    }

    fun connect(server: DiscoveredServer) {
        if (_state.value.connection.phase in listOf("connecting", "reconnecting")) return
        _state.update { it.copy(selectedServer = server, connectionError = null) }
        runCatching { client.connect(server.endpoint) }
            .onFailure { error -> _state.update { it.copy(connectionError = error.message ?: "连接参数不正确。") } }
    }

    fun disconnect() {
        pauseGyro()
        autoConnect.manualDisconnect()
        client.disconnect()
        _state.update { it.copy(selectedServer = null, connectionError = null) }
    }

    private fun connectLastIfDiscovered() {
        val state = _state.value
        autoConnect.takeCandidate(state.settings, state.discovered, state.connection.phase,
            hasTarget = state.selectedServer != null)?.let(::connect)
    }

    private fun rememberConnectedComputer() {
        val state = _state.value
        val server = state.selectedServer ?: return
        if (state.settings.address == server.endpoint && state.settings.lastComputerName == server.name) return
        updateSettings { it.copy(address = server.endpoint, lastComputerName = server.name) }
    }
    fun move(dx: Double, dy: Double) {
        val value = _state.value
        if (!value.touchpadMotionEnabled) return
        client.move(dx * value.settings.sensitivity, dy * value.settings.sensitivity)
    }
    private fun moveFromGyro(dx: Double, dy: Double) {
        val value = _state.value
        if (value.gyroActive && !value.gyroRepositioning && shouldRunGyro(value.settings.mouseControlMode, value.gyroPaused,
                value.connection.connected, foreground, value.gyroBlocked)) client.move(dx, dy)
    }
    internal fun setMouseControlMode(mode: MouseControlMode) {
        if (mode == MouseControlMode.Gyroscope && !gyroAvailable) {
            _state.update { it.copy(notice = "当前手机没有可用的陀螺仪，请使用触控板。") }; return
        }
        pauseGyro()
        updateSettings { it.copy(mouseControlMode = mode) }
        if (mode == MouseControlMode.Gyroscope) {
            _state.update { it.copy(gyroPaused = !it.connection.connected || it.gyroBlocked || !foreground) }
            syncGyro()
        }
    }
    internal fun toggleGyroPaused() {
        val value = _state.value
        if (value.settings.mouseControlMode != MouseControlMode.Gyroscope || !gyroAvailable ||
            !value.connection.connected || value.gyroBlocked || !foreground) return
        setGyroRepositioning(false)
        _state.update { it.copy(gyroPaused = !it.gyroPaused) }; syncGyro()
    }
    internal fun setGyroRepositioning(value: Boolean) {
        val state = _state.value
        val active = value && foreground && state.gyroRepositionEnabled
        if (active == state.gyroRepositioning) return
        _state.update { it.copy(gyroRepositioning = active) }
        gyroscope.setRepositioning(active)
        if (active) client.clearPointerMotion()
    }
    internal fun toggleFullscreenGyro() {
        val mode = _state.value.settings.mouseControlMode
        setMouseControlMode(if (mode == MouseControlMode.Gyroscope) MouseControlMode.Touchpad else MouseControlMode.Gyroscope)
    }
    internal fun togglePresentation() = client.togglePresentation()
    internal fun pauseGyro() {
        resumeGyroAfterUiBlock = false
        stopGyro()
    }
    private fun stopGyro() {
        gyroscope.stop()
        _state.update { if (it.gyroPaused && !it.gyroActive && !it.gyroRepositioning && !it.gyroBlackScreen) it
            else it.copy(gyroPaused = true, gyroActive = false, gyroRepositioning = false, gyroBlackScreen = false) }
    }
    internal fun enterGyroBlackScreen() {
        if (!foreground) return
        _state.update { if (it.canEnterBlackScreen) it.copy(gyroBlackScreen = true) else it }
    }
    internal fun exitGyroBlackScreen() {
        _state.update { if (it.gyroBlackScreen) it.copy(gyroBlackScreen = false) else it }
    }
    internal fun setGyroUiState(blocked: Boolean, rotation: Int) {
        val before = _state.value
        if (blocked && !before.gyroBlocked) {
            // A dialog or fullscreen menu temporarily owns the UI. Preserve the
            // user's running intent so closing it can resume gyro control.
            resumeGyroAfterUiBlock = before.settings.mouseControlMode == MouseControlMode.Gyroscope &&
                !before.gyroPaused && before.connection.connected && foreground
            stopGyro()
        }
        if (!blocked && before.gyroBlocked) {
            val shouldResume = resumeGyroAfterUiBlock
            resumeGyroAfterUiBlock = false
            _state.update { value ->
                val canResume = shouldResume && value.settings.mouseControlMode == MouseControlMode.Gyroscope &&
                    value.connection.connected && foreground
                value.copy(gyroBlocked = false, gyroPaused = if (canResume) false else value.gyroPaused,
                    gyroDisplayRotation = rotation)
            }
        } else {
            _state.update { if (it.gyroBlocked == blocked && it.gyroDisplayRotation == rotation) it
                else it.copy(gyroBlocked = blocked, gyroDisplayRotation = rotation) }
        }
        syncGyro()
    }
    private fun syncGyro() {
        val value = _state.value
        gyroscope.configure(value.effectiveGyroSettings, value.gyroDisplayRotation)
        val active = if (shouldRunGyro(value.settings.mouseControlMode, value.gyroPaused,
                value.connection.connected, foreground, value.gyroBlocked) && !value.presentation.switching) gyroscope.start()
            else { gyroscope.stop(); false }
        _state.update {
            val next = it.copy(gyroActive = active, gyroRepositioning = active && it.gyroRepositioning)
            next.copy(gyroBlackScreen = it.gyroBlackScreen && next.canEnterBlackScreen)
        }
    }
    fun scroll(delta: Double) {
        val state = _state.value
        val wheelDelta = state.settings.scrollDelta(delta)
        client.scroll(wheelDelta)
        if (foreground && state.connection.connected) haptics.playScroll(state.settings.haptics, wheelDelta)
        else haptics.resetScrollProgress()
    }
    internal fun twoFingerScroll(delta: Double) { client.scroll(_state.value.settings.scrollDelta(delta, twoFinger = true)) }
    internal fun twoFingerHorizontalScroll(delta: Double) { client.touchGesture(horizontal = _state.value.settings.scrollDelta(delta, twoFinger = true)) }
    internal fun twoFingerZoom(delta: Double) { client.touchGesture(zoom = delta) }
    internal fun endTwoFingerGesture(cancelled: Boolean) { client.endTouchGesture(cancelled) }
    internal fun pointerTap() { client.click("left") }
    internal fun pointerTapFeedback() { previewHaptic(HapticAction.LeftClick) }
    fun click(button: String) {
        if (button == "left") previewHaptic(HapticAction.LeftClick)
        else if (button == "right") previewHaptic(HapticAction.RightClick)
        client.click(button)
    }
    fun buttonDown(button: String, source: String = "touchpad") {
        if (button == "left") previewHaptic(HapticAction.LeftClick)
        else if (button == "right") previewHaptic(HapticAction.RightClick)
        else if (button == "middle") previewHaptic(HapticAction.Keyboard)
        client.buttonDown(button, source)
    }
    fun buttonUp(button: String, source: String = "touchpad") { client.buttonUp(button, source) }
    fun key(key: String) {
        val state = _state.value
        if (!state.connection.connected || (state.settings.inputMode == TextInputMode.Realtime && state.livePaused)) return
        if (!keyboardDelivery.enqueue(listOf(KeyboardOperation.Key(key)))) {
            stopKeyboardDelivery("发送较慢，实时输入已暂停；请检查电脑后再发送保留文字。")
            return
        }
        previewHaptic(if (key == "backspace") HapticAction.Backspace else HapticAction.Keyboard)
        pumpKeyboardDelivery()
    }
    internal fun realtimeCommit(value: String): Boolean {
        val state = _state.value
        if (value.isEmpty()) return true
        if (state.settings.inputMode != TextInputMode.Realtime || !state.connection.connected || state.livePaused) return false
        if (!keyboardDelivery.enqueue(textChunks(value).map { KeyboardOperation.Text(it, draft = false) })) {
            stopKeyboardDelivery("发送较慢，实时输入已暂停；请检查电脑后再发送保留文字。")
            _state.update { it.copy(livePaused = true, notice = "发送较慢，实时输入已暂停；文字已保留。") }
            return false
        }
        previewHaptic(HapticAction.Keyboard)
        pumpKeyboardDelivery()
        return true
    }
    fun sendText() {
        val state = _state.value
        val value = state.text
        if (value.isEmpty() || state.sending || !state.connection.connected || state.settings.inputMode != TextInputMode.Draft) return
        if (!keyboardDelivery.enqueue(textChunks(value).map { KeyboardOperation.Text(it, draft = true) })) {
            _state.update { it.copy(notice = "文字较长或正在发送，请稍后分段发送。") }
            return
        }
        previewHaptic(HapticAction.Keyboard)
        _state.update { it.copy(sending = true) }
        pumpKeyboardDelivery()
    }
    private fun pumpKeyboardDelivery() {
        while (true) {
            when (val operation = keyboardDelivery.next() ?: return) {
                is KeyboardOperation.Key -> {
                    val epoch = keyboardEpoch
                    client.key(operation.value) { sent ->
                        if (!sent) viewModelScope.launch {
                            if (epoch == keyboardEpoch) {
                                stopKeyboardDelivery("按键未能发送，未确认文字已保留。")
                                _state.update { it.copy(livePaused = it.settings.inputMode == TextInputMode.Realtime,
                                    notice = "按键未能发送，请检查连接。") }
                            }
                        }
                    }
                }
                is KeyboardOperation.Text -> {
                    activeTextId = client.text(operation.value)
                    if (activeTextId == null) stopKeyboardDelivery("当前连接还没准备好，未发送文字已保留。")
                    return
                }
            }
        }
    }
    private fun completeKeyboardText(id: Long, error: String?) {
        if (id != activeTextId) return
        if (error != null) {
            stopKeyboardDelivery(error)
            return
        }
        activeTextId = null
        val operation = keyboardDelivery.acknowledge() ?: return
        if (operation.draft) _state.update { old ->
            old.copy(
                text = if (old.text.startsWith(operation.value)) old.text.removePrefix(operation.value) else old.text,
                sending = keyboardDelivery.hasDraft,
                notice = if (keyboardDelivery.hasDraft) old.notice else "文字已发送到电脑。",
            )
        }
        pumpKeyboardDelivery()
    }
    private fun stopKeyboardDelivery(message: String) {
        keyboardEpoch++
        val pending = keyboardDelivery.stop()
        activeTextId = null
        if (pending.isEmpty()) return
        val live = pending.filterIsInstance<KeyboardOperation.Text>().filter { !it.draft }.joinToString("") { it.value }
        _state.update { old -> old.copy(
            sending = false,
            retainedText = old.retainedText + live,
            livePaused = old.livePaused || live.isNotEmpty() || old.settings.inputMode == TextInputMode.Realtime,
            notice = message,
        ) }
    }
    internal fun resumeRealtime() {
        if (_state.value.connection.connected) _state.update { it.copy(livePaused = false) }
    }
    internal fun restoreRetainedText() {
        if (_state.value.sending) return
        _state.update { old ->
            val retained = old.retainedText + old.liveText
            old.copy(
                text = old.text + (if (old.text.isNotEmpty() && retained.isNotEmpty()) "\n" else "") + retained,
                retainedText = "", liveText = "",
                settings = old.settings.copy(inputMode = TextInputMode.Draft),
            )
        }
        saveSettings()
    }
    fun updateSettings(transform: (ControlSettings) -> ControlSettings) {
        val previous = _state.value.settings
        val wasAutoConnectEnabled = previous.autoConnectLast
        val value = transform(previous).sanitized()
        if (value.haptics.scroll != previous.haptics.scroll) haptics.resetScrollProgress()
        _state.update { it.copy(settings = value) }
        client.setReportRate(value.reportRate)
        if (value.laser != previous.laser) client.setLaserSettings(value.laser)
        syncGyro()
        if (!value.haptics.enabled) haptics.cancel()
        saveSettings()
        if (value.updateCheckFrequency != previous.updateCheckFrequency) {
            appUpdate.setFrequency(value.updateCheckFrequency)
            if (foreground) appUpdate.maybeCheck(viewModelScope)
        }
        if (value.autoConnectLast && !wasAutoConnectEnabled) {
            autoConnect.rearm()
            connectLastIfDiscovered()
        }
    }
    internal fun updateHaptics(transform: (HapticSettings) -> HapticSettings) {
        updateSettings { it.copy(haptics = transform(it.haptics)) }
    }
    internal fun flushLaserSettings() { client.flushLaserSettings() }
    internal fun previewHaptic(action: HapticAction) { haptics.play(_state.value.settings.haptics, action) }
    internal fun previewHapticStrength() { previewHaptic(HapticAction.StrengthPreview) }
    internal fun scrollHoldHaptic() { previewHaptic(HapticAction.ScrollHold) }
    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) pauseGyro()
        autoConnect.foreground = value
        if (!value) { touchSampling.cancel(); haptics.cancel(); stopKeyboardDelivery("应用已暂停，未确认文字已保留。") }
        client.setForeground(value)
        if (value) connectLastIfDiscovered()
        if (value) appUpdate.maybeCheck(viewModelScope)
        syncGyro()
    }
    private fun saveSettings() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(200)
            try { store.save(_state.value.settings) }
            catch (_: java.io.IOException) { _state.update { it.copy(notice = "设置未能保存，请检查手机存储。") } }
        }
    }

    override fun onCleared() { gyroscope.stop(); haptics.cancel(); discovery.stop(); client.destroy(); super.onCleared() }
}

class MainViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(context) as T
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = ViewModelProvider(this, MainViewModelFactory(applicationContext))[MainViewModel::class.java]
        val initialThemeMode = vm.state.value.settings.themeMode
        val initialLanguageMode = vm.state.value.settings.languageMode
        setContent {
            val themeMode by remember(vm) { vm.state.map { it.settings.themeMode }.distinctUntilChanged() }
                .collectAsState(initial = initialThemeMode)
            val languageMode by remember(vm) { vm.state.map { it.settings.languageMode }.distinctUntilChanged() }
                .collectAsState(initial = initialLanguageMode)
            val systemLocales = LocalConfiguration.current.locales
            val appLanguage = languageMode.effective(List(systemLocales.size()) { systemLocales[it] })
            SideEffect {
                Localization.setMode(languageMode, List(systemLocales.size()) { systemLocales[it] })
            }
            CompositionLocalProvider(LocalAppLanguage provides appLanguage) {
                PhoneMouseTheme(themeMode) { PhoneMouseApp(vm) }
            }
        }
    }
}

@Composable
fun PhoneMouseApp(vm: MainViewModel = viewModel(factory = MainViewModelFactory(LocalContext.current))) {
    val state by vm.state.collectAsState()
    val updatePrompt by vm.appUpdatePrompt.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = checkNotNull(LocalActivity.current)
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var settingsDestination by rememberSaveable { mutableStateOf(SettingsDestination.Home) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var showConnectionInfo by rememberSaveable { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val realtimeFocus = remember { RealtimeInputFocus() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val keyboardFocusRegions = remember { KeyboardFocusRegions() }
    val currentState by rememberUpdatedState(state)
    val touchpadGesture = remember { AtomicBoolean(false) }
    var fullscreenInputVisible by rememberSaveable { mutableStateOf(false) }
    var fullscreenInputBounds by remember { mutableStateOf<Rect?>(null) }
    var fullscreenControlsBusy by remember { mutableStateOf(false) }
    val fullscreenVisible = state.settings.fullscreenMode && !showSettings && !showHelp
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val controlsAvailable = state.connection.connected && !showConnectionInfo
    val mouseInputEnabled = controlsAvailable && !state.presentation.switching
    val displayRotation = LocalConfiguration.current.let { activity.window.decorView.display?.rotation ?: 0 }
    val effectiveRotation = state.settings.effectiveGyroRotation(displayRotation, fullscreenVisible)
    SideEffect {
        vm.setGyroUiState(showSettings || showHelp || showConnectionInfo ||
            fullscreenInputVisible || keyboardVisible || !windowFocused || (fullscreenVisible && fullscreenControlsBusy), effectiveRotation)
        activity.requestedOrientation = if (fullscreenVisible && state.settings.fullscreenRotationMode == FullscreenRotationMode.Manual) {
            when (state.settings.fullscreenRotation) {
                90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        } else if (vm.gyroRotationLocked) ActivityInfo.SCREEN_ORIENTATION_LOCKED
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    fun closeFullscreenInput() {
        fullscreenInputVisible = false
        fullscreenInputBounds = null
        realtimeFocus.view?.clearFocus()
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }
    FullscreenSystemBars(activity.window, fullscreenVisible, blackScreen = state.gyroBlackScreen)
    BackHandler(enabled = fullscreenVisible && !fullscreenInputVisible && !showConnectionInfo && !state.gyroBlackScreen) {
        vm.updateSettings { it.copy(fullscreenMode = false) }
    }
    BackHandler(enabled = showHelp) {
        showHelp = false
    }
    KeepScreenAwake(activity.window, state.gyroBlackScreen || (showSettings && settingsDestination == SettingsDestination.Sampling) ||
        (state.connection.connected && state.settings.keepAwake))
    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { vm.setForeground(true) }
            override fun onStop(owner: LifecycleOwner) { vm.setForeground(false) }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    LaunchedEffect(state.notice) {
        if (state.notice != null) kotlinx.coroutines.delay(3200); vm.clearNotice()
    }
    // Wait until controls are idle so an update never interrupts mouse input.
    if (!state.connection.connected && state.connection.phase !in listOf("connecting", "reconnecting") &&
        !showSettings && !showHelp && !showConnectionInfo && !state.gyroBlackScreen) {
        updatePrompt?.let { AppUpdateDialog(it, vm::dismissUpdatePrompt) }
    }
    if (state.gyroBlackScreen) {
        GyroBlackScreen(activity.window, vm::exitGyroBlackScreen) { modifier, enabled ->
            TouchpadViewHost(state, vm, modifier, enabled && state.canEnterBlackScreen,
                {}, fullscreenPresentation = true, onBlackScreenExit = vm::exitGyroBlackScreen)
        }
        return
    }
    if (showHelp) {
        HelpPage { showHelp = false }
        return
    }
    if (showSettings) {
        SettingsPage(state.settings, vm, settingsDestination, { settingsDestination = it },
            gyroDisplayRotation = effectiveRotation) { showSettings = false }
        return
    }
    if (showConnectionInfo) {
        ConnectionInfoDialog(state, vm, onDismiss = { showConnectionInfo = false })
    }
    if (fullscreenVisible) {
        LaunchedEffect(fullscreenVisible) {
            if (!fullscreenInputVisible) {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
            }
        }
        LaunchedEffect(fullscreenInputVisible, state.settings.inputMode) {
            if (!fullscreenInputVisible) return@LaunchedEffect
            androidx.compose.runtime.withFrameNanos { }
            if (state.settings.inputMode == TextInputMode.Realtime) realtimeFocus.requestFocus()
            else { inputFocusRequester.requestFocus(); keyboardController?.show() }
        }
        Box(Modifier.fillMaxSize()) {
            FullscreenTouchpad(state, vm, inputEnabled = mouseInputEnabled, controlsAvailable = controlsAvailable,
                onExit = { closeFullscreenInput(); vm.updateSettings { it.copy(fullscreenMode = false) } },
                displayRotation = displayRotation,
                onKeyboard = {
                    vm.pauseGyro()
                    if (!fullscreenInputVisible) fullscreenInputVisible = true
                    else if (state.settings.inputMode == TextInputMode.Realtime) realtimeFocus.requestFocus()
                    else { inputFocusRequester.requestFocus(); keyboardController?.show() }
                },
                inputPanelBounds = if (fullscreenInputVisible) fullscreenInputBounds else null,
                onMotionBlocked = { fullscreenControlsBusy = it })
            // Registered after the tool island so dismissing input takes priority over layout editing.
            BackHandler(enabled = fullscreenInputVisible && !showConnectionInfo) {
                val decor = activity.window.decorView
                if (ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                    WindowCompat.getInsetsController(activity.window, decor).hide(WindowInsetsCompat.Type.ime())
                } else closeFullscreenInput()
            }
            if (fullscreenInputVisible) {
                Box(Modifier.fillMaxSize().navigationBarsPadding().imePadding().padding(10.dp), contentAlignment = Alignment.BottomCenter) {
                    KeyboardPanel(state, vm, inputFocusRequester, realtimeFocus, keyboardFocusRegions,
                        onClose = ::closeFullscreenInput,
                        modifier = Modifier.onGloballyPositioned { fullscreenInputBounds = it.boundsInRoot() })
                }
            }
        }
        return
    }
    Surface(modifier = Modifier.fillMaxSize()
        .onGloballyPositioned { keyboardFocusRegions.rootOffset = it.positionInRoot() }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val startedInInput = keyboardFocusRegions.contains(down.position + keyboardFocusRegions.rootOffset)
                var totalDy = 0f
                var previousY = down.position.y
                var isTap = true
                var multiplePointers = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    totalDy += change.position.y - previousY
                    previousY = change.position.y
                    if (event.changes.size > 1) multiplePointers = true
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) isTap = false
                    if (!change.pressed) break
                }
                val startedInTouchpad = touchpadGesture.getAndSet(false)
                // Observe in screen-stable coordinates without consuming the
                // gesture. Blur on release so IME resizing cannot turn a tap
                // into the keyboard-opening swipe.
                if (currentState.connection.connected && !startedInTouchpad && !multiplePointers && kotlin.math.abs(totalDy) > 96f) {
                    if (currentState.settings.inputMode == TextInputMode.Realtime) realtimeFocus.requestFocus()
                    else { inputFocusRequester.requestFocus(); keyboardController?.show() }
                } else if (isTap && !multiplePointers && !startedInInput) {
                    realtimeFocus.view?.clearFocus()
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                }
            }
        }, color = Page) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(state, onHelp = { keyboardController?.hide(); showHelp = true }, onSettings = {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                settingsDestination = SettingsDestination.Home
                showSettings = true
            }, onConnection = { showConnectionInfo = true })
            KeyboardPanel(state, vm, inputFocusRequester, realtimeFocus, keyboardFocusRegions)
            TouchSurface(state, vm, inputEnabled = mouseInputEnabled, controlsAvailable = controlsAvailable) { touchpadGesture.set(true) }
            state.notice?.let { NoticeCard(it) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable private fun Header(state: UiState, onHelp: () -> Unit, onSettings: () -> Unit, onConnection: () -> Unit) {
    val connected = state.connection.connected
    val hasTargetComputer = connected || state.connection.phase in listOf("connecting", "reconnecting")
    val computerName = if (hasTargetComputer) {
        state.selectedServer?.name?.takeIf { it.isNotBlank() } ?: "Windows 电脑"
    } else {
        "未连接"
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_computer), contentDescription = null, tint = Green, modifier = Modifier.size(25.dp))
            Text(computerName, Modifier.weight(1f), color = Ink, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onHelp, modifier = Modifier.width(45.dp).height(48.dp)) {
                Icon(painterResource(R.drawable.ic_help), contentDescription = localize("帮助与技巧"), tint = Green, modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = onConnection, modifier = Modifier.width(45.dp).height(48.dp)) {
                Icon(
                    painter = painterResource(if (connected) R.drawable.ic_connect else R.drawable.ic_disconnect),
                    contentDescription = localize(if (connected) "查看当前连接" else "连接电脑"),
                    tint = if (LocalAppColors.current.dark) Green else Color.Unspecified,
                    modifier = Modifier.size(if (connected) 20.dp else 22.dp),
                )
            }
            IconButton(onClick = onSettings, modifier = Modifier.width(45.dp).height(48.dp)) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = localize("设置"), tint = Green, modifier = Modifier.size(22.dp))
            }
        }
    }
}

private class MainTouchpadHitRegions {
    var padOrigin = Offset.Zero
    var laserButton: Rect? = null
    fun excludes(x: Float, y: Float): Boolean = laserButton?.contains(Offset(x, y) + padOrigin) == true
}

@Composable private fun TouchSurface(state: UiState, vm: MainViewModel, inputEnabled: Boolean,
    controlsAvailable: Boolean, onTouchpadDown: () -> Unit) {
    val hitRegions = remember { MainTouchpadHitRegions() }
    Card(colors = CardDefaults.cardColors(containerColor = CardSurface), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compactMiddle = maxWidth < 300.dp
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.settings.mouseControlMode == MouseControlMode.Gyroscope) "体感鼠标" else "触控板",
                        Modifier.weight(1f).padding(start = 7.dp), color = Muted,
                        fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { vm.setMouseControlMode(if (state.settings.mouseControlMode == MouseControlMode.Touchpad)
                        MouseControlMode.Gyroscope else MouseControlMode.Touchpad) }, enabled = vm.gyroAvailable, modifier = Modifier.size(44.dp)) {
                        Icon(painterResource(R.drawable.ic_gyroscope),
                            contentDescription = localize(if (state.settings.mouseControlMode == MouseControlMode.Touchpad) "切换为体感鼠标" else "切回触控板"),
                            tint = if (vm.gyroAvailable) Green else Muted, modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = { vm.updateSettings { it.copy(fullscreenMode = true) } },
                        modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_fullscreen), contentDescription = localize("进入全屏触控板"), tint = Green,
                            modifier = Modifier.size(22.dp))
                    }
                    MiddleMouseButton(enabled = inputEnabled, visualEnabled = controlsAvailable,
                        compact = compactMiddle, inputEpoch = state.presentation.inputEpoch,
                        onDown = { vm.buttonDown("middle", "button") }, onUp = { vm.buttonUp("middle", "button") })
                }
            }
            Box(Modifier.fillMaxWidth().height(278.dp)) {
                if (state.settings.mouseControlMode == MouseControlMode.Gyroscope) {
                    GyroControlPanel(state, vm, Modifier.fillMaxSize())
                } else TouchpadViewHost(state, vm,
                    Modifier.fillMaxSize().onGloballyPositioned { hitRegions.padOrigin = it.positionInRoot() },
                    inputEnabled, onTouchpadDown, excludeStart = hitRegions::excludes)
                LaserToggleButton(state, vm::togglePresentation,
                    Modifier.align(Alignment.TopStart).padding(6.dp).size(48.dp)
                        .onGloballyPositioned { hitRegions.laserButton = it.boundsInRoot() })
            }
            Row(Modifier.fillMaxWidth().height(58.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MouseButton("左键", left = true, Modifier.weight(1f), enabled = inputEnabled,
                    visualEnabled = controlsAvailable, inputEpoch = state.presentation.inputEpoch,
                    onDown = { vm.buttonDown("left", "button") }, onUp = { vm.buttonUp("left", "button") })
                MouseButton("右键", left = false, Modifier.weight(1f), enabled = inputEnabled,
                    visualEnabled = controlsAvailable, inputEpoch = state.presentation.inputEpoch,
                    onDown = { vm.buttonDown("right", "button") }, onUp = { vm.buttonUp("right", "button") })
            }
        }
    }
}

@Composable internal fun TouchpadViewHost(state: UiState, vm: MainViewModel, modifier: Modifier, enabled: Boolean,
    onTouchpadDown: () -> Unit, fullscreenPresentation: Boolean = false,
    excludeStart: (Float, Float) -> Boolean = { _, _ -> false }, onBlackScreenExit: (() -> Unit)? = null) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = remember { TouchpadView(context) }
    DisposableEffect(view, lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { view.cancelInteraction(animate = false) }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view.cancelInteraction(animate = false)
        }
    }
    AndroidViewCompat(view, modifier, enabled, state.settings, state.touchpadMotionEnabled, state.gyroRepositionEnabled,
        vm::setGyroRepositioning, vm::move, vm::scroll, vm::twoFingerScroll, vm::pointerTap, vm::pointerTapFeedback, { vm.click("right") }, { vm.buttonDown("left") }, { vm.buttonUp("left") }, onTouchpadDown, vm::scrollHoldHaptic, fullscreenPresentation, excludeStart, onBlackScreenExit,
        vm::twoFingerHorizontalScroll, vm::twoFingerZoom, vm::endTwoFingerGesture, state.presentation.inputEpoch)
}

@Composable private fun AndroidViewCompat(view: TouchpadView, modifier: Modifier, enabled: Boolean, settings: ControlSettings, pointerMotionEnabled: Boolean,
    gyroRepositionEnabled: Boolean, onGyroReposition: (Boolean) -> Unit,
    move: (Double, Double) -> Unit, scroll: (Double) -> Unit, twoFingerScroll: (Double) -> Unit, click: () -> Unit, tapFeedback: () -> Unit, rightClick: () -> Unit, buttonDown: () -> Unit, buttonUp: () -> Unit, onTouchpadDown: () -> Unit, onScrollHold: () -> Unit,
    fullscreenPresentation: Boolean = false, excludeStart: (Float, Float) -> Boolean = { _, _ -> false },
    onBlackScreenExit: (() -> Unit)? = null, horizontalScroll: (Double) -> Unit = {},
    zoom: (Double) -> Unit = {}, gestureEnd: (Boolean) -> Unit = {}, inputEpoch: Long = 0) {
    val colors = LocalAppColors.current
    val appLanguage = LocalAppLanguage.current
    TouchpadInputView(view, modifier, update = {
        it.setInputEpoch(inputEpoch)
        it.setColors(colors)
        it.setLanguage(appLanguage)
        it.setFullscreenPresentation(fullscreenPresentation)
        it.setBlackScreenPresentation(onBlackScreenExit)
        it.tapToClick = settings.tapToClick; it.onMove = move; it.onScroll = scroll; it.onTap = click; it.onButtonDown = buttonDown; it.onButtonUp = buttonUp; it.onTouchStart = onTouchpadDown
        it.onScrollHold = onScrollHold
        it.onRightTap = rightClick
        it.onTwoFingerScroll = twoFingerScroll; it.onTapFeedback = tapFeedback
        it.onTwoFingerHorizontalScroll = horizontalScroll; it.onTwoFingerZoom = zoom; it.onTwoFingerGestureEnd = gestureEnd
        it.setAcceleration(settings.touchpadAcceleration)
        it.setPointerMotionEnabled(pointerMotionEnabled)
        it.onGyroReposition = onGyroReposition
        it.setGyroRepositionEnabled(gyroRepositionEnabled)
        it.setDragModes(settings.longPressDrag, settings.doubleTapHoldDrag)
        it.setControlEnabled(enabled)
    }, excludeStart = excludeStart, inputEnabled = enabled)
}

private class KeyboardFocusRegions {
    var rootOffset = Offset.Zero
    var inputBounds: Rect? = null
    var modeBounds: Rect? = null
    fun contains(position: Offset) = inputBounds?.contains(position) == true || modeBounds?.contains(position) == true
}

@Composable private fun KeyboardPanel(state: UiState, vm: MainViewModel, inputFocusRequester: FocusRequester,
    realtimeFocus: RealtimeInputFocus, focusRegions: KeyboardFocusRegions, onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier) {
    val realtime = state.settings.inputMode == TextInputMode.Realtime
    var draftFocused by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val rootView = LocalView.current
    LaunchedEffect(state.settings.inputMode) {
        if (restoreFocus) {
            androidx.compose.runtime.withFrameNanos { }
            if (realtime) realtimeFocus.requestFocus()
            else { inputFocusRequester.requestFocus(); keyboardController?.show() }
            restoreFocus = false
        }
    }
    Card(modifier = modifier.animateContentSize(tween(200, easing = FastOutSlowInEasing)),
        colors = CardDefaults.cardColors(containerColor = CardSurface), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("文字输入", Modifier.align(Alignment.CenterVertically).padding(end = 8.dp), color = Ink, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                    onClose?.let { close ->
                        IconButton(onClick = close, modifier = Modifier.size(40.dp)) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = localize("关闭文字输入"), tint = Green, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                InputModeSelector(state.settings.inputMode, enabled = !state.sending,
                    modifier = Modifier.align(Alignment.CenterVertically).onGloballyPositioned { focusRegions.modeBounds = it.boundsInRoot() }) { mode ->
                    if (state.settings.inputMode != mode) {
                        // Focus can remain after Back hides the IME. Only carry
                        // the keyboard across when it is actually visible.
                        restoreFocus = ViewCompat.getRootWindowInsets(rootView)
                            ?.isVisible(WindowInsetsCompat.Type.ime()) == true &&
                            (draftFocused || realtimeFocus.view?.hasFocus() == true)
                        realtimeFocus.view?.suspendInput()
                        if (!restoreFocus) {
                            focusManager.clearFocus(force = true)
                            realtimeFocus.view?.clearFocus()
                        }
                        vm.inputMode(mode)
                    }
                }
            }
            if (realtime) {
                RealtimeInput(state, vm, realtimeFocus, Modifier.onGloballyPositioned { focusRegions.inputBounds = it.boundsInRoot() })
            } else {
                OutlinedTextField(
                    value = state.text, onValueChange = vm::text,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).focusRequester(inputFocusRequester)
                        .onFocusChanged { draftFocused = it.isFocused; if (it.isFocused) vm.pauseGyro() }
                        .onGloballyPositioned { focusRegions.inputBounds = it.boundsInRoot() },
                    readOnly = state.sending, minLines = 1, maxLines = 4,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
                    placeholder = { Text("先编辑，确认后发送", color = Muted.copy(alpha = 0.72f), fontSize = 13.sp) },
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.None),
                )
            }
            if (!realtime) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(Modifier.padding(end = 8.dp)) {
                        Box(Modifier.matchParentSize().padding(vertical = 6.dp).background(Soft, RoundedCornerShape(10.dp)))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            KeyboardAction("Enter", enabled = state.connection.connected) { vm.key("enter") }
                            Spacer(Modifier.width(1.dp).height(14.dp).background(Muted.copy(alpha = .2f)))
                            KeyboardAction("Backspace", enabled = state.connection.connected) { vm.key("backspace") }
                        }
                    }
                    KeyboardAction(
                        if (state.sending) "发送中…" else "发送文字",
                        enabled = state.connection.connected && state.text.isNotEmpty() && !state.sending,
                        primary = true, onClick = vm::sendText,
                    )
                }
            }
            if (realtime && (!state.connection.connected || state.livePaused)) {
                Text(if (!state.connection.connected) "未连接 · 实时发送已暂停" else "实时发送已暂停", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                if (state.livePaused && state.connection.connected) TextButton(onClick = vm::resumeRealtime, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
                    Text("继续实时输入", fontSize = 12.sp, lineHeight = 18.sp)
                }
            } else if (realtime) {
                // Keep the realtime panel's height stable when the connection
                // becomes ready so the touchpad below does not jump upward.
                Spacer(Modifier.height(18.dp))
            }
            if (state.retainedText.isNotEmpty() || (state.livePaused && state.liveText.isNotEmpty())) {
                Text("有文字未确认，请先检查电脑内容。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                TextButton(onClick = { realtimeFocus.view?.suspendInput(); vm.restoreRetainedText() }, enabled = !state.sending,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
                    Text("加入草稿", fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}

@Composable private fun RealtimeInput(state: UiState, vm: MainViewModel, focus: RealtimeInputFocus, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = LocalAppColors.current
    val appLanguage = LocalAppLanguage.current
    val view = remember { RealtimeInputView(context) }
    var focused by remember { mutableStateOf(false) }
    DisposableEffect(view) {
        focus.view = view
        onDispose { view.suspendInput(); if (focus.view === view) focus.view = null }
    }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { view },
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.green else colors.outline, RoundedCornerShape(14.dp)),
        update = {
            it.onCommit = vm::realtimeCommit
            it.onLocalText = vm::liveText
            it.onRemoteKey = vm::key
            it.setTextColor(colors.ink.toArgb())
            it.setHintTextColor(colors.muted.copy(alpha = .72f).toArgb())
            it.highlightColor = colors.green.copy(alpha = .3f).toArgb()
            if (android.os.Build.VERSION.SDK_INT >= 29) it.textCursorDrawable?.setTint(colors.green.toArgb())
            it.hint = Localization.text("输入文字…", appLanguage)
            it.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                focused = hasFocus; if (hasFocus) vm.pauseGyro()
            }
            it.update(state.liveText, state.connection.connected && !state.livePaused)
        },
    )
}

@Composable private fun InputModeSelector(mode: TextInputMode, enabled: Boolean, modifier: Modifier = Modifier, onSelect: (TextInputMode) -> Unit) {
    val cardColor = CardSurface
    val segmentWidths = remember { mutableStateMapOf<TextInputMode, Int>() }
    val position by animateFloatAsState(
        targetValue = if (mode == TextInputMode.Realtime) 0f else 1f,
        animationSpec = tween(200, easing = FastOutSlowInEasing), label = "inputModePosition",
    )
    Box(modifier) {
        Box(Modifier.matchParentSize().padding(vertical = 6.dp).background(Soft, RoundedCornerShape(10.dp)))
        Box(Modifier.matchParentSize().drawBehind {
            val firstWidth = segmentWidths[TextInputMode.Realtime]?.toFloat() ?: return@drawBehind
            val secondWidth = segmentWidths[TextInputMode.Draft]?.toFloat() ?: return@drawBehind
            val selectedWidth = firstWidth + (secondWidth - firstWidth) * position
            drawRoundRect(
                color = cardColor,
                topLeft = Offset(4.dp.toPx() + firstWidth * position, 8.dp.toPx()),
                size = Size((selectedWidth - 4.dp.toPx()).coerceAtLeast(0f), (size.height - 16.dp.toPx()).coerceAtLeast(0f)),
                cornerRadius = CornerRadius(8.dp.toPx()),
            )
        })
        Row(Modifier.padding(horizontal = 2.dp)) {
            TextInputMode.entries.forEach { item ->
                val selected = item == mode
                val textColor by animateColorAsState(
                    targetValue = (if (selected) Green else Muted).copy(alpha = if (enabled) 1f else .55f),
                    animationSpec = tween(200), label = "inputModeTextColor",
                )
                val interactionSource = remember { MutableInteractionSource() }
                Box(
                    Modifier.heightIn(min = 48.dp).onSizeChanged { segmentWidths[item] = it.width }
                        .semantics { this.selected = selected }
                        .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, role = Role.RadioButton) { onSelect(item) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(item.label, Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                        color = textColor,
                        fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable private fun KeyboardAction(label: String, enabled: Boolean, primary: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        if (primary) Box(Modifier.matchParentSize().padding(vertical = 6.dp)
            .background(if (enabled) Green else Soft, RoundedCornerShape(10.dp)))
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            color = if (!enabled) Muted.copy(alpha = .55f) else if (primary) MaterialTheme.colorScheme.onPrimary else Green,
            fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable private fun NoticeCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = PaleGreen), shape = RoundedCornerShape(15.dp)) { Text(message, color = Ink, fontSize = 13.sp, modifier = Modifier.padding(13.dp)) }
}

internal data class HelpTip(val title: String, val description: String)
private data class HelpSection(val title: String, val tips: List<HelpTip>)

private val HelpSections = listOf(
    HelpSection("触控板", listOf(
        HelpTip("移动与点击", "单指滑动移动；轻点左键，双指轻点右键。"),
        HelpTip("双击与拖动", "连续轻点两次双击；第二次按住并滑动可拖动。"),
        HelpTip("长按拖动", "设置中开启后，按住约 0.8 秒再滑动。"),
        HelpTip("文字输入", "在主页触控板外的空白处上下滑动，进入文字输入。"),
    )),
    HelpSection("滚动与缩放", listOf(
        HelpTip("持续滚动", "按住约半秒再拖动；偏移越大越快，松手停止。"),
        HelpTip("双指滚动", "上下或左右滑动，换方向先抬手。"),
        HelpTip("缩放", "双指张开放大，捏合缩小。"),
    )),
    HelpSection("体感鼠标", listOf(
        HelpTip("开启体感", "点体感图标，转动手机移动光标。"),
        HelpTip("手机归位调整", "按住触摸区域光标暂停移动，松手继续。"),
    )),
    HelpSection("黑屏模式", GyroBlackScreenHelpTips),
)

@Composable internal fun HelpTipText(tip: HelpTip) {
    val keywordColor = Ink
    val appLanguage = LocalAppLanguage.current
    Text(buildAnnotatedString {
        withStyle(SpanStyle(color = keywordColor, fontWeight = FontWeight.SemiBold)) {
            append(Localization.text(tip.title, appLanguage))
            append(if (appLanguage == AppLanguage.Chinese) "：" else ": ")
        }
        append(Localization.text(tip.description, appLanguage))
    }, color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
}

@Composable private fun HelpPage(close: () -> Unit) {
    SettingsScaffold("帮助与技巧", close) {
        HelpSections.forEach { section ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardSurface),
                shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(section.title, color = Green, fontSize = 18.sp, lineHeight = 24.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
                    section.tips.forEach { tip -> HelpTipText(tip) }
                }
            }
        }
    }
}

@Composable private fun ConnectionInfoDialog(state: UiState, vm: MainViewModel, onDismiss: () -> Unit) {
    val connected = state.connection.connected
    val busy = state.connection.phase in listOf("connecting", "reconnecting")
    val configuration = LocalConfiguration.current
    val listMaxHeight = (configuration.screenHeightDp * .30f).coerceIn(96f, 300f).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 400.dp).fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            color = CardSurface,
        ) {
            Column(Modifier.heightIn(min = 192.dp, max = (configuration.screenHeightDp - 80).coerceAtLeast(240).dp)
                .padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (connected) "当前连接" else "连接电脑", Modifier.weight(1f), color = Ink, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = localize("关闭连接窗口"), tint = Muted, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                // The body only scrolls when a short screen or large font needs
                // it; the title and connection action stay within reach.
                Column(
                    Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (connected) {
                        ConnectionDetails(state)
                    }
                    if (!connected) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.weight(1f).alignByBaseline(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("附近的电脑", Modifier.weight(1f, fill = false).alignByBaseline(), color = Ink, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(
                                        "· " + when {
                                            state.connection.phase == "reconnecting" -> "重连中"
                                            busy -> "连接中"
                                            else -> "未连接"
                                        },
                                        modifier = Modifier.alignByBaseline(),
                                        color = Muted,
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp,
                                        maxLines = 1,
                                    )
                                }
                                Text("${state.discovered.size} 台", Modifier.alignByBaseline(), color = Muted, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1)
                            }
                            if (!busy && state.selectedServer != null) {
                                Text(state.connectionError ?: state.connection.message, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
                            }
                            if (state.discovered.isEmpty()) {
                                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("暂未找到电脑", color = Ink, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
                                    Text("请打开电脑接收端，并让手机和电脑\n连接同一 Wi-Fi。列表会自动更新。", color = Muted, fontSize = 13.sp, lineHeight = 20.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                }
                            } else {
                                Column(Modifier.fillMaxWidth().heightIn(max = listMaxHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    state.discovered.forEach { candidate ->
                                        OutlinedButton(
                                            onClick = {
                                                if (busy && candidate.endpoint == state.selectedServer?.endpoint) vm.disconnect()
                                                else vm.connect(candidate)
                                            },
                                            enabled = !busy || candidate.endpoint == state.selectedServer?.endpoint,
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                                            shape = RoundedCornerShape(14.dp),
                                            border = BorderStroke(1.dp, Green.copy(alpha = if (busy && candidate.endpoint != state.selectedServer?.endpoint) .12f else .22f)),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                                        ) {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                val selected = busy && candidate.endpoint == state.selectedServer?.endpoint
                                                Icon(painterResource(R.drawable.ic_computer), contentDescription = null, tint = if (busy && !selected) Muted else Green, modifier = Modifier.size(24.dp))
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text(candidate.name, color = Ink, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                    Text(candidate.endpoint, color = Muted, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                }
                                                Text(if (selected) "取消" else "连接", color = if (busy && !selected) Muted else Green, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (connected) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = Soft)
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (connected) {
                            TextButton(
                                onClick = vm::disconnect,
                                modifier = Modifier.heightIn(min = 48.dp),
                                colors = ButtonDefaults.textButtonColors(contentColor = Green),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text("断开连接", fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
                            }
                        } else {
                            OutlinedButton(
                                onClick = vm::disconnect,
                                modifier = Modifier.heightIn(min = 48.dp),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, Green.copy(alpha = .22f)),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Text("取消连接", color = Green, fontSize = 13.sp, lineHeight = 18.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun ConnectionDetails(state: UiState) {
    val connected = state.connection.connected
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            if (connected) {
                Icon(painterResource(R.drawable.ic_computer), contentDescription = null, tint = Green, modifier = Modifier.size(24.dp))
            } else {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Green, strokeWidth = 2.dp)
            }
            Text(state.selectedServer?.name ?: "Windows 电脑", Modifier.weight(1f).alignByBaseline(), color = Ink, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    connected -> "已连接"
                    state.connection.phase == "reconnecting" -> "重连中"
                    else -> "连接中"
                },
                Modifier.alignByBaseline(), color = Green, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1,
            )
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(start = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            state.selectedServer?.let { Text(it.endpoint, color = Muted, fontSize = 12.sp, lineHeight = 18.sp) }
            if (connected) {
                Text(state.connection.rttMs?.let { "响应延迟 $it ms" } ?: "延迟测量中", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
        if (!connected) {
            Text(state.connectionError ?: state.connection.message, Modifier.padding(start = 32.dp), color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

class TouchpadView(context: Context) : View(context) {
    private var inputEpoch = Long.MIN_VALUE
    internal fun setInputEpoch(value: Long) {
        if (inputEpoch != value) { cancelInteraction(animate = false); inputEpoch = value }
    }
    var onMove: (Double, Double) -> Unit = { _, _ -> }
    var onScroll: (Double) -> Unit = { }
    var onTwoFingerScroll: (Double) -> Unit = { }
    var onTwoFingerHorizontalScroll: (Double) -> Unit = { }
    var onTwoFingerZoom: (Double) -> Unit = { }
    var onTwoFingerGestureEnd: (Boolean) -> Unit = { }
    var onTap: () -> Unit = { }
    var onTapFeedback: () -> Unit = { }
    var onRightTap: () -> Unit = { }
    var onButtonDown: () -> Unit = { }
    var onButtonUp: () -> Unit = { }
    var onTouchStart: () -> Unit = { }
    var onScrollHold: () -> Unit = { }
    var onGyroReposition: (Boolean) -> Unit = { }
    var tapToClick = true
    private var longPressDrag = false
    private var doubleTapHoldDrag = true
    private var pointerMotionEnabled = true
    private var gyroRepositionEnabled = false
    private var gyroRepositioning = false
    private var blackScreenExit: (() -> Unit)? = null
    private var appLanguage = systemLanguage()
    private var blackExitOrigins = emptyList<TouchContact>()
    private var blackScreenTouchCaptured = false
    private val restoreBlackScreen = Runnable {
        val exit = blackScreenExit
        if (exit != null && blackScreenTouchCaptured && blackExitOrigins.size == 3 && canReceiveInput()) {
            cancelInteraction(animate = false)
            exit()
        }
    }
    private val density = resources.displayMetrics.density
    private val pad = Paint(Paint.ANTI_ALIAS_FLAG)
    private var colors = AppColors.Light
    private var fullscreenPresentation = false

    internal fun setFullscreenPresentation(value: Boolean) {
        if (fullscreenPresentation == value) return
        cancelInteraction(animate = false)
        fullscreenPresentation = value
        updateGestureDescription()
        invalidate()
    }

    internal fun setColors(value: AppColors) {
        if (colors == value) return
        colors = value
        invalidate()
    }

    internal fun setLanguage(value: AppLanguage) {
        if (appLanguage == value) return
        appLanguage = value
        updateGestureDescription()
    }

    private fun accent(alpha: Int = 255) = colors.green.copy(alpha = alpha / 255f).toArgb()
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var downPointerId = 0
    private val twoFingerGesture = TwoFingerGestureController(
        onHorizontalScroll = { if (it != 0.0) onTwoFingerHorizontalScroll(it) },
        onZoom = { if (it != 0.0) onTwoFingerZoom(it) })
    private var twoFingerActive = false
    private val pointerAcceleration = PointerAccelerationController(PointerInputKind.Touchpad)
    private var accelerationSettings = PointerAccelerationSettings(mode = PointerAccelerationMode.Off)
    private var motionTimeNanos = 0L
    private val pointerGesture = PointerGestureController(
        onMove = { dx, dy ->
            if (pointerMotionEnabled) {
                val delta = pointerAcceleration.move(dx, dy, motionTimeNanos, accelerationSettings)
                if (delta.x != 0.0 || delta.y != 0.0) onMove(delta.x, delta.y)
            }
        },
        onTap = { onTap() },
        onButtonDown = { invalidate(); onButtonDown() },
        onButtonUp = { invalidate(); onButtonUp() },
        onTapRecognized = { performClick(); onTapFeedback() },
    )
    private val resolveTap = Runnable {
        if (!scrollMode && canScroll()) {
            pointerGesture.flushTap(SystemClock.uptimeMillis())
            schedulePendingTap()
        }
    }

    private fun schedulePendingTap() {
        removeCallbacks(resolveTap)
        val deadline = pointerGesture.pendingTapDeadline ?: return
        postDelayed(resolveTap, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0L))
    }
    private val armPointerDrag = Runnable {
        if (!scrollMode && canScroll()) pointerGesture.tryStartLongPress(SystemClock.uptimeMillis())
    }
    private var scrollMode = false
    private var scrollActive = false
    private var scrollThumbPosition = 0.5f
    private val scrollGesture = ScrollGestureController()
    private var returnAnimator: ValueAnimator? = null
    private val armContinuous = Runnable {
        if (scrollMode && scrollActive && canScroll() && scrollGesture.tryStartContinuous(SystemClock.uptimeMillis())) {
            onScrollHold()
            invalidate()
            postOnAnimation(scrollFrame)
        }
    }
    private val scrollFrame = object : Runnable {
        override fun run() {
            if (!canScroll() || scrollGesture.mode != ScrollGestureController.Mode.Continuous) {
                cancelInteraction(animate = false)
                return
            }
            val delta = scrollGesture.frame(SystemClock.uptimeMillis())
            if (delta != 0.0) onScroll(delta)
            postOnAnimation(this)
        }
    }
    private var moved = false
    private val dragging get() = pointerGesture.dragging
    private var cancelled = false
    // Keep a 48dp+ gesture target while drawing a slimmer rail inside it. The
    // previous 42dp strip made the scroll affordance easy to miss on phones.
    private val scrollWidth get() = if (fullscreenPresentation) 0f else 52f * density
    init { isFocusable = true; updateGestureDescription(); setBackgroundColor(android.graphics.Color.TRANSPARENT) }

    internal fun setDragModes(longPress: Boolean, doubleTapHold: Boolean) {
        if (longPressDrag == longPress && doubleTapHoldDrag == doubleTapHold) return
        cancelInteraction(animate = false)
        longPressDrag = longPress; doubleTapHoldDrag = doubleTapHold
        updateGestureDescription()
    }

    internal fun setPointerMotionEnabled(value: Boolean) {
        if (pointerMotionEnabled == value) return
        cancelInteraction(animate = false)
        pointerMotionEnabled = value
        updateGestureDescription()
    }

    internal fun setGyroRepositionEnabled(value: Boolean) {
        if (gyroRepositionEnabled == value) return
        cancelInteraction(animate = false)
        gyroRepositionEnabled = value
        updateGestureDescription()
    }

    private fun setGyroRepositioning(value: Boolean) {
        if (gyroRepositioning == value) return
        gyroRepositioning = value
        onGyroReposition(value)
    }

    internal fun setBlackScreenPresentation(onExit: (() -> Unit)?) {
        if ((blackScreenExit != null) != (onExit != null)) {
            cancelInteraction(animate = false)
            blackScreenExit = onExit
            updateGestureDescription()
            invalidate()
        } else blackScreenExit = onExit
    }

    private fun updateGestureDescription() {
        contentDescription = Localization.text(buildString {
            if (blackScreenExit != null) {
                append("黑屏。轻点左键，双指轻点右键，双指上下或左右滑动滚动，捏合缩放。三指按住约 0.6 秒或手机返回恢复界面，允许轻微移动。")
                if (gyroRepositionEnabled) append("单指按住立即回位，松手继续体感。")
                return@buildString
            }
            append(if (fullscreenPresentation) "全屏触控板。" else "触控板。")
            if (pointerMotionEnabled) append("单指移动鼠标，") else append("体感模式下触摸不移动光标，")
            append("轻点左键，双指上下或左右滑动滚动，捏合缩放，双指轻点右键。")
            if (gyroRepositionEnabled) append("单指按住立即回位，松手继续体感。")
            if (pointerMotionEnabled && doubleTapHoldDrag) append("双击同一点，第二次按住后拖动。")
            if (pointerMotionEnabled && longPressDrag) append("按住 0.8 秒后拖动。")
            if (!fullscreenPresentation) append("右侧滚轮直接拖动精确滚动，先长按再拖动持续滚动，松手停止")
        }, appLanguage)
    }

    internal fun setControlEnabled(value: Boolean) {
        if (!value && isEnabled) cancelInteraction(animate = false)
        isEnabled = value
    }

    internal fun setAcceleration(value: PointerAccelerationSettings) {
        if (accelerationSettings == value) return
        accelerationSettings = value
        pointerAcceleration.reset()
    }

    internal fun cancelInteraction(animate: Boolean) {
        setGyroRepositioning(false)
        cancelBlackScreenHold()
        removeCallbacks(armPointerDrag)
        removeCallbacks(resolveTap)
        pointerGesture.cancel()
        pointerAcceleration.reset()
        twoFingerGesture.cancel()
        if (twoFingerActive) onTwoFingerGestureEnd(true)
        twoFingerActive = false
        cancelled = true
        stopScroll(animate)
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun canReceiveInput() = isEnabled && isAttachedToWindow && isShown && hasWindowFocus() &&
        windowVisibility == VISIBLE
    private fun canScroll() = canReceiveInput() && !cancelled

    private fun stopScroll(animate: Boolean) {
        removeCallbacks(armContinuous)
        removeCallbacks(scrollFrame)
        scrollGesture.stop()
        scrollMode = false
        scrollActive = false
        returnAnimator?.cancel()
        returnAnimator = null
        if (animate && isAttachedToWindow && scrollThumbPosition != 0.5f) {
            returnAnimator = ValueAnimator.ofFloat(scrollThumbPosition, 0.5f).apply {
                duration = 240L
                interpolator = DecelerateInterpolator(2f)
                addUpdateListener {
                    // Returning the visual thumb never generates wheel input.
                    scrollThumbPosition = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            scrollThumbPosition = 0.5f
        }
        invalidate()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) cancelInteraction(animate = false)
    }

    override fun onDetachedFromWindow() {
        cancelInteraction(animate = false)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (blackScreenExit != null) {
            canvas.drawColor(android.graphics.Color.BLACK)
            return
        }
        pad.color = colors.soft.toArgb()
        if (fullscreenPresentation) canvas.drawColor(pad.color)
        else canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 18f * density, 18f * density, pad)
        if (dragging && !scrollMode) {
            // Keep a subtle focus ring visible while the remote left button is
            // held after the second tap, so drag mode has a clear on-device state.
            pad.color = accent(180)
            pad.style = Paint.Style.STROKE
            pad.strokeWidth = 2f * density
            canvas.drawRoundRect(RectF(1f * density, 1f * density, width - 1f * density, height - 1f * density), 17f * density, 17f * density, pad)
            pad.style = Paint.Style.FILL
        }
        pad.color = accent(38)
        val gap = 25f * density
        var x = gap
        while (x < width - scrollWidth) { var y = gap; while (y < height) { canvas.drawCircle(x, y, 1.1f * density, pad); y += gap }; x += gap }
        if (fullscreenPresentation) return
        val railLeft = width - scrollWidth
        val railTop = 8f * density
        val railBottom = height - 8f * density
        val continuous = scrollGesture.mode == ScrollGestureController.Mode.Continuous
        // The rail remains visually distinct from the pointer area while its
        // contrast increases during a scroll gesture.
        pad.color = accent(if (continuous) 88 else if (scrollActive) 64 else 35)
        canvas.drawRoundRect(RectF(railLeft + 4f * density, railTop, width - 8f * density, railBottom), 13f * density, 13f * density, pad)

        val centerX = railLeft + scrollWidth / 2f - 2f * density
        val trackTop = railTop + 27f * density
        val trackBottom = railBottom - 27f * density
        pad.color = accent(if (continuous) 155 else if (scrollActive) 105 else 70)
        canvas.drawRoundRect(RectF(centerX - 2f * density, trackTop, centerX + 2f * density, trackBottom), 2f * density, 2f * density, pad)
        if (continuous) {
            pad.strokeWidth = 1.5f * density
            canvas.drawLine(centerX - 14f * density, height / 2f, centerX + 14f * density, height / 2f, pad)
        }

        val thumbHalfWidth = 12f * density
        val thumbHalfHeight = 19f * density
        val thumbCenter = trackTop + (trackBottom - trackTop) * scrollThumbPosition
        pad.color = accent(if (scrollActive) 255 else 150)
        canvas.drawRoundRect(
            RectF(centerX - thumbHalfWidth, thumbCenter - thumbHalfHeight, centerX + thumbHalfWidth, thumbCenter + thumbHalfHeight),
            9f * density,
            9f * density,
            pad,
        )

        // Small chevrons explain the direction without competing with the
        // thumb. They also give the strip a useful visual rhythm at rest.
        pad.color = accent(if (scrollActive) 210 else 145)
        pad.strokeWidth = 1.7f * density
        pad.style = Paint.Style.STROKE
        val chevronHalf = 4f * density
        val topChevronY = railTop + 13f * density
        canvas.drawLine(centerX - chevronHalf, topChevronY + 2f * density, centerX, topChevronY - 2f * density, pad)
        canvas.drawLine(centerX, topChevronY - 2f * density, centerX + chevronHalf, topChevronY + 2f * density, pad)
        val bottomChevronY = railBottom - 13f * density
        canvas.drawLine(centerX - chevronHalf, bottomChevronY - 2f * density, centerX, bottomChevronY + 2f * density, pad)
        canvas.drawLine(centerX, bottomChevronY + 2f * density, centerX + chevronHalf, bottomChevronY - 2f * density, pad)
        pad.style = Paint.Style.FILL
    }
    private fun processPoint(x: Float, y: Float, timeNanos: Long) {
        motionTimeNanos = timeNanos
        if (scrollMode) {
            if (abs(x - downX) > 4f * density || abs(y - downY) > 4f * density) moved = true
            val delta = scrollGesture.move(x / density, y / density)
            if (scrollGesture.mode != ScrollGestureController.Mode.Pressing) removeCallbacks(armContinuous)
            val amplitude = (scrollGesture.displacementDp / ScrollGestureController.FULL_SPEED_DP).coerceIn(-1f, 1f)
            scrollThumbPosition = 0.5f + amplitude * 0.42f
            invalidate()
            if (delta != 0.0) onScroll(delta)
        }
        else {
            pointerGesture.move(x / density, y / density)
            if (!pointerGesture.longPressPending) removeCallbacks(armPointerDrag)
        }
    }

    private fun MotionEvent.sampleTimeNanos(history: Int? = null): Long =
        if (Build.VERSION.SDK_INT >= 34) {
            if (history == null) eventTimeNanos else getHistoricalEventTimeNanos(history)
        } else (if (history == null) eventTime else getHistoricalEventTime(history)) * 1_000_000L

    private fun contacts(event: MotionEvent, history: Int? = null): List<TouchContact> =
        (0 until event.pointerCount).map { index ->
            TouchContact(event.getPointerId(index),
                (if (history == null) event.getX(index) else event.getHistoricalX(index, history)) / density,
                (if (history == null) event.getY(index) else event.getHistoricalY(index, history)) / density)
        }

    private fun sendTwoFingerScroll(delta: Double) {
        if (delta != 0.0) onTwoFingerScroll(delta)
    }

    private fun cancelBlackScreenHold() {
        removeCallbacks(restoreBlackScreen)
        blackExitOrigins = emptyList()
    }

    /** Own the entire touch sequence once three fingers land, including after restore. */
    private fun trackBlackScreenHold(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelBlackScreenHold()
            return false
        }
        if (!blackScreenTouchCaptured) {
            if (event.actionMasked != MotionEvent.ACTION_POINTER_DOWN || event.pointerCount != 3 || !canScroll())
                return false
            cancelInteraction(animate = false)
            blackScreenTouchCaptured = true
            parent?.requestDisallowInterceptTouchEvent(true)
            blackExitOrigins = contacts(event)
            postDelayed(restoreBlackScreen, 600L)
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelInteraction(animate = false)
                blackScreenTouchCaptured = false
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> cancelBlackScreenHold()
            MotionEvent.ACTION_MOVE -> if (blackExitOrigins.isNotEmpty()) {
                fun moved(points: List<TouchContact>) = points.size != 3 || points.any { point ->
                    val origin = blackExitOrigins.firstOrNull { it.id == point.id }
                    origin == null || kotlin.math.hypot(point.x - origin.x, point.y - origin.y) > 10f
                }
                // A batched sample can move away and return before this frame.
                if ((0 until event.historySize).any { moved(contacts(event, it)) } || moved(contacts(event)))
                    cancelBlackScreenHold()
            }
        }
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) blackScreenTouchCaptured = false
        if ((blackScreenTouchCaptured || (blackScreenExit != null && isEnabled)) && trackBlackScreenHold(event)) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isEnabled) return false
                removeCallbacks(armPointerDrag)
                removeCallbacks(resolveTap)
                if (twoFingerActive) onTwoFingerGestureEnd(true)
                twoFingerGesture.cancel(); twoFingerActive = false
                onTouchStart()
                parent.requestDisallowInterceptTouchEvent(true)
                downX = event.x; downY = event.y
                downPointerId = event.getPointerId(0)
                motionTimeNanos = event.sampleTimeNanos()
                pointerAcceleration.begin(motionTimeNanos)
                downAt = SystemClock.uptimeMillis(); scrollMode = !fullscreenPresentation && event.x >= width - scrollWidth
                if (gyroRepositionEnabled && !scrollMode) setGyroRepositioning(true)
                if (scrollMode) { pointerGesture.flushTap(event.eventTime, force = true); pointerGesture.cancel() }
                else {
                    pointerGesture.down(event.x / density, event.y / density, event.eventTime, tapToClick,
                        longPressDrag && pointerMotionEnabled, doubleTapHoldDrag && pointerMotionEnabled)
                    if (pointerGesture.longPressPending) postDelayed(armPointerDrag, PointerGestureController.LONG_PRESS_MS)
                }
                scrollActive = scrollMode
                if (scrollMode) {
                    returnAnimator?.cancel()
                    returnAnimator = null
                    scrollThumbPosition = 0.5f
                    val arrowSize = 36f * density
                    val allowContinuous = event.y > arrowSize && event.y < height - arrowSize
                    scrollGesture.start(event.x / density, event.y / density, downAt, allowContinuous)
                    if (allowContinuous) postDelayed(armContinuous, ScrollGestureController.HOLD_MS)
                    invalidate()
                }
                moved = false; cancelled = false; return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A two-finger gesture owns the rest of this touch sequence;
                // lifting back to one finger must not unexpectedly re-clutch.
                setGyroRepositioning(false)
                if (!cancelled && event.pointerCount == 2 && !twoFingerActive) {
                    val canTap = if (scrollMode) !moved && scrollGesture.mode != ScrollGestureController.Mode.Continuous
                        else pointerGesture.tapEligible
                    val firstIndex = event.findPointerIndex(downPointerId)
                    val firstStill = firstIndex >= 0 && abs(event.getX(firstIndex) - downX) <= 4f * density &&
                        abs(event.getY(firstIndex) - downY) <= 4f * density
                    removeCallbacks(armPointerDrag)
                    removeCallbacks(resolveTap)
                    pointerGesture.cancel()
                    pointerAcceleration.reset()
                    stopScroll(animate = false)
                    twoFingerGesture.start(contacts(event), event.downTime, canTap && firstStill)
                    twoFingerActive = true
                } else cancelInteraction(animate = true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (twoFingerActive && !cancelled) {
                    for (index in 0 until event.historySize) sendTwoFingerScroll(twoFingerGesture.move(contacts(event, index)))
                    sendTwoFingerScroll(twoFingerGesture.move(contacts(event)))
                    return true
                }
                if (event.pointerCount > 1 && !cancelled) cancelInteraction(animate = true)
                if (cancelled) { moved = true; return true }
                for (index in 0 until event.historySize) processPoint(event.getHistoricalX(0, index), event.getHistoricalY(0, index), event.sampleTimeNanos(index))
                processPoint(event.x, event.y, event.sampleTimeNanos())
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (twoFingerActive && !cancelled) sendTwoFingerScroll(twoFingerGesture.lift(contacts(event)))
                return true
            }
            MotionEvent.ACTION_UP -> {
                motionTimeNanos = event.sampleTimeNanos()
                removeCallbacks(armPointerDrag)
                parent.requestDisallowInterceptTouchEvent(false)
                if (twoFingerActive && !cancelled) {
                    if (twoFingerGesture.finish(contacts(event), event.eventTime)) { performClick(); onRightTap() }
                    onTwoFingerGestureEnd(false)
                    twoFingerActive = false
                } else if (!scrollMode && !cancelled) {
                    pointerGesture.up(event.x / density, event.y / density, event.eventTime, tapToClick)
                } else if (!cancelled && !moved && scrollMode && scrollGesture.mode != ScrollGestureController.Mode.Continuous) {
                    // The arrow zones are useful for a precise one-step scroll
                    // when a thumb drag would be too coarse.
                    val arrowSize = 36f * density
                    if (downY <= arrowSize) onScroll(360.0)
                    else if (downY >= height - arrowSize) onScroll(-360.0)
                }
                stopScroll(animate = true)
                setGyroRepositioning(false)
                pointerAcceleration.reset()
                schedulePendingTap()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelInteraction(animate = true)
                return true
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
