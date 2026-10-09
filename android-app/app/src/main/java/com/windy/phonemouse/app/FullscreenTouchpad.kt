package com.windy.phonemouse.app

import android.content.res.Configuration
import android.os.Build
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlin.math.roundToInt

@Composable internal fun FullscreenSystemBars(window: Window, fullscreen: Boolean, blackScreen: Boolean = false) {
    val colors = LocalAppColors.current
    DisposableEffect(window, fullscreen, colors, blackScreen) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val navigationContrast = if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced else false
        val statusContrast = if (Build.VERSION.SDK_INT >= 29) window.isStatusBarContrastEnforced else false
        WindowCompat.setDecorFitsSystemWindows(window, false)
        controller.isAppearanceLightStatusBars = blackScreen || !colors.dark
        controller.isAppearanceLightNavigationBars = blackScreen || !colors.dark
        @Suppress("DEPRECATION")
        window.statusBarColor = (if (blackScreen) Color.Black else if (fullscreen) colors.soft else colors.page).toArgb()
        @Suppress("DEPRECATION")
        // Android 8.0 cannot draw dark navigation icons, so keep their background dark.
        window.navigationBarColor = (if (blackScreen || Build.VERSION.SDK_INT < 27) Color.Black
            else if (fullscreen) colors.soft else colors.page).toArgb()
        if (blackScreen && Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        // Hidden navigation can consume the first Back gesture to reveal system bars.
        // Keep navigation available while the touchpad fills the content area.
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        if (blackScreen) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.show(WindowInsetsCompat.Type.navigationBars())
        } else controller.show(WindowInsetsCompat.Type.systemBars())
        onDispose {
            if (Build.VERSION.SDK_INT >= 29) {
                window.isNavigationBarContrastEnforced = navigationContrast
                window.isStatusBarContrastEnforced = statusContrast
            }
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable internal fun FullscreenTouchpad(state: UiState, vm: MainViewModel, inputEnabled: Boolean,
    onExit: () -> Unit, onKeyboard: () -> Unit = {}, inputPanelBounds: Rect? = null,
    displayRotation: Int = 0, onMotionBlocked: (Boolean) -> Unit = {}) {
    FullscreenTouchpadLayout(state.settings, inputEnabled, state.notice,
        onSettingsChange = { vm.updateSettings(it) },
        onDown = { vm.buttonDown(it.wire, "floating") }, onUp = { vm.buttonUp(it.wire, "floating") },
        onScroll = vm::scroll, onScrollHold = vm::scrollHoldHaptic, onExit = onExit,
        onKeyboard = onKeyboard, inputPanelBounds = inputPanelBounds, displayRotation = displayRotation,
        onMotionBlocked = onMotionBlocked,
        motionControls = if (state.settings.mouseControlMode == MouseControlMode.Gyroscope) {
            { modifier -> GyroControlPanel(state, vm, modifier, compact = true,
                showBlackScreenButton = state.settings.controlsSplit) }
        } else null,
        onGyro = vm::toggleFullscreenGyro, gyroAvailable = vm.gyroAvailable,
        onBlackScreen = vm::enterGyroBlackScreen, blackScreenAvailable = state.canEnterBlackScreen) { modifier, enabled, exclude ->
        TouchpadViewHost(state, vm, modifier, enabled,
            {}, fullscreenPresentation = true, excludeStart = exclude)
    }
}

private class FullscreenHitRegions {
    var padOrigin = Offset.Zero
    var inputPanel: Rect? = null
    val controls = mutableMapOf<String, Rect>()
    fun excludes(x: Float, y: Float): Boolean {
        val point = Offset(x, y) + padOrigin
        return inputPanel?.contains(point) == true || controls.values.any { it.contains(point) }
    }
}

/** Shared by the app and the native multi-touch verification activity. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable internal fun FullscreenTouchpadLayout(settings: ControlSettings, inputEnabled: Boolean,
    notice: String? = null, onSettingsChange: ((ControlSettings) -> ControlSettings) -> Unit,
    onDown: (MouseKey) -> Unit, onUp: (MouseKey) -> Unit, onExit: () -> Unit,
    onKeyboard: () -> Unit = {},
    inputPanelBounds: Rect? = null,
    displayRotation: Int = 0,
    onScroll: (Double) -> Unit = {}, onScrollHold: () -> Unit = {},
    onControlBounds: (String, Rect) -> Unit = { _, _ -> },
    onMotionBlocked: (Boolean) -> Unit = {},
    motionControls: (@Composable (Modifier) -> Unit)? = null,
    onGyro: () -> Unit = {}, gyroAvailable: Boolean = true,
    onBlackScreen: () -> Unit = {}, blackScreenAvailable: Boolean = false,
    touchpad: @Composable (Modifier, Boolean, (Float, Float) -> Boolean) -> Unit) {
    val hitRegions = remember { FullscreenHitRegions() }
    hitRegions.inputPanel = inputPanelBounds
    var controlsOrigin by remember { mutableStateOf(Offset.Zero) }
    var rootHeight by remember { mutableStateOf(0) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var editing by remember(landscape) { mutableStateOf(false) }
    var draggingId by remember(landscape) { mutableStateOf<FloatingControl?>(null) }
    var dragPoint by remember(landscape) { mutableStateOf<IslandOffset?>(null) }
    var dragStart by remember(landscape) { mutableStateOf<IslandOffset?>(null) }
    var held by remember { mutableStateOf(emptySet<MouseKey>()) }
    var wheelActive by remember { mutableStateOf(false) }
    var showFullscreenHelp by rememberSaveable { mutableStateOf(false) }
    var showFullscreenActions by rememberSaveable { mutableStateOf(false) }
    var showRotationPanel by rememberSaveable { mutableStateOf(false) }
    var showSpeedPanel by rememberSaveable { mutableStateOf(false) }
    var showGyroActionPanel by rememberSaveable { mutableStateOf(false) }
    val focused = LocalWindowInfo.current.isWindowFocused
    val motionBlockedCallback by rememberUpdatedState(onMotionBlocked)
    SideEffect {
        motionBlockedCallback(editing || draggingId != null ||
            (settings.controlsSplit && settings.islandCollapsed) || showFullscreenHelp ||
            showFullscreenActions || showRotationPanel || showSpeedPanel || showGyroActionPanel)
    }
    DisposableEffect(Unit) { onDispose { motionBlockedCallback(false) } }
    val enabled = inputEnabled && focused && !editing && draggingId == null && !showFullscreenHelp &&
        !showFullscreenActions && !showRotationPanel && !showSpeedPanel && !showGyroActionPanel
    fun controlsIdle() = held.isEmpty() && !wheelActive && draggingId == null
    val canChange = controlsIdle() && !showFullscreenHelp && !showFullscreenActions &&
        !showRotationPanel && !showSpeedPanel && !showGyroActionPanel
    val gyroSelected = settings.mouseControlMode == MouseControlMode.Gyroscope
    val gyroLabel = when {
        !gyroAvailable -> "设备不支持陀螺仪"
        !gyroSelected -> "开启体感鼠标"
        else -> "关闭体感鼠标"
    }
    val savedIsland = if (landscape) settings.landscapeIsland else settings.portraitIsland
    val savedLayout = if (landscape) settings.landscapeControls else settings.portraitControls
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, landscape) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { editing = false }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(focused, settings.islandCollapsed, settings.controlsSplit) {
        if (!focused || settings.islandCollapsed || !settings.controlsSplit) editing = false
    }
    BackHandler(enabled = editing, onBack = onExit)
    BackHandler(enabled = showFullscreenActions) { showFullscreenActions = false }
    Box(Modifier.fillMaxSize().background(Soft).onSizeChanged { rootHeight = it.height }.statusBarsPadding()) {
        touchpad(Modifier.fillMaxSize().onGloballyPositioned { hitRegions.padOrigin = it.positionInRoot() }, enabled, hitRegions::excludes)
        // Keep saved normalized positions independent of the IME's size.
        val controlsInsets = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
            .union(WindowInsets.mandatorySystemGestures)
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(controlsInsets)
            .onGloballyPositioned { controlsOrigin = it.positionInRoot() }) {
            val density = LocalDensity.current
            fun px(value: Float) = with(density) { value.dp.toPx() }
            val width = constraints.maxWidth.toFloat(); val height = constraints.maxHeight.toFloat()
            val collapsed = settings.islandCollapsed; val split = settings.controlsSplit
            val toolbarCollapsed = split && if (landscape) settings.landscapeToolbarCollapsed else settings.portraitToolbarCollapsed
            val splitToolbarWidth = if (editing) 247.dp else 207.dp
            val panelWidth = minOf(if (split) splitToolbarWidth else 240.dp,
                (maxWidth - 16.dp).coerceAtLeast(56.dp))
            val sizes = mapOf(FloatingControl.Toolbar to FloatingSize(with(density) { panelWidth.toPx() }, px(52f)),
                FloatingControl.Left to FloatingSize(px(72f), px(52f)), FloatingControl.Middle to FloatingSize(px(72f), px(52f)),
                FloatingControl.Right to FloatingSize(px(72f), px(52f)), FloatingControl.Wheel to FloatingSize(px(56f), px(160f)),
                FloatingControl.Keyboard to FloatingSize(px(52f), px(52f)),
                FloatingControl.Gyroscope to FloatingSize(px(52f), px(52f)))
            val splitGeometry = SplitControlsGeometry(width, height, sizes, px(8f), px(24f), px(8f))
            val initialLayout = savedLayout ?: SplitControlLayout.defaults()
            val offsets = splitGeometry.resolve(initialLayout)
            val panelGeometry = FloatingIslandGeometry(width, height,
                if (collapsed) px(56f) else with(density) { panelWidth.toPx() },
                px(if (collapsed) 56f else if (split) 52f else 144f), px(8f), px(24f))
            val toolbarBubbleGeometry = FloatingIslandGeometry(width, height, px(56f), px(56f), px(8f), px(24f))
            val panelPosition = if (split) initialLayout.toolbar else savedIsland
            val panelOffset = when {
                split && !collapsed && toolbarCollapsed -> toolbarBubbleGeometry.offset(panelPosition)
                split && !collapsed -> offsets.getValue(FloatingControl.Toolbar)
                else -> panelGeometry.offset(panelPosition)
            }
            val expectedPanelSize = FloatingSize(
                if (collapsed || toolbarCollapsed) px(56f) else with(density) { panelWidth.toPx() },
                px(if (collapsed || toolbarCollapsed) 56f else if (split) 52f else 144f))
            var measuredPanelSize by remember(collapsed, split, toolbarCollapsed, panelWidth) {
                mutableStateOf(expectedPanelSize)
            }
            val imeBottom = WindowInsets.ime.getBottom(density)
            val inputInControls = inputPanelBounds?.let { bounds ->
                FloatingInputBounds(bounds.left - controlsOrigin.x, bounds.top - controlsOrigin.y,
                    bounds.right - controlsOrigin.x, bounds.bottom - controlsOrigin.y)
            }
            val keyboardTop = if (imeBottom > 0) rootHeight - controlsOrigin.y - imeBottom else null
            val displayPanelOffset = avoidFloatingInput(panelOffset, measuredPanelSize, inputInControls,
                keyboardTop, px(8f), px(8f))
            fun setToolbarCollapsed(value: Boolean) {
                onSettingsChange { current ->
                    if (landscape) current.copy(landscapeToolbarCollapsed = value)
                    else current.copy(portraitToolbarCollapsed = value)
                }
            }
            fun saveLayout(transform: (SplitControlLayout) -> SplitControlLayout) {
                onSettingsChange { current ->
                    val existing = (if (landscape) current.landscapeControls else current.portraitControls) ?: splitGeometry.layout(offsets)
                    if (landscape) current.copy(landscapeControls = transform(existing)) else current.copy(portraitControls = transform(existing))
                }
            }
            LaunchedEffect(split, landscape, savedLayout, width, height) {
                if (split && savedLayout == null && width > 0 && height > 0) saveLayout { splitGeometry.layout(offsets) }
            }
            fun endDrag(id: FloatingControl) {
                if (draggingId != id) return
                dragPoint?.let { point ->
                    if (split && !collapsed && id == FloatingControl.Toolbar && toolbarCollapsed) {
                        val position = toolbarBubbleGeometry.position(point)
                        saveLayout { it.copy(toolbar = position) }
                    } else if (split && !collapsed) {
                        val placed = splitGeometry.place(id, point, offsets.filterKeys { it != id }, dragStart)
                        val layout = splitGeometry.layout(offsets + (id to placed))
                        saveLayout { layout }
                    } else {
                        val position = panelGeometry.position(point)
                        if (split) saveLayout { it.copy(toolbar = position) }
                        else onSettingsChange { if (landscape) it.copy(landscapeIsland = position) else it.copy(portraitIsland = position) }
                    }
                }
                dragPoint = null; dragStart = null; draggingId = null
            }
            fun dragModifier(id: FloatingControl, allowed: Boolean, base: IslandOffset, geometry: FloatingIslandGeometry) =
                Modifier.islandDrag(allowed && (draggingId == null || draggingId == id),
                    onStart = { draggingId = id; dragPoint = base; dragStart = base },
                    onDelta = { amount -> if (draggingId == id) {
                        val point = dragPoint ?: base
                        dragPoint = geometry.clamp(IslandOffset(point.x + amount.x, point.y + amount.y))
                    } }, onEnd = { endDrag(id) })
            DisposableEffect(landscape) { onDispose { draggingId?.let { endDrag(it) } } }
            fun positioned(id: FloatingControl, base: IslandOffset): Modifier {
                val point = if (draggingId == id) dragPoint ?: base else base
                return Modifier.offset { IntOffset(point.x.roundToInt(), point.y.roundToInt()) }.trackRegion(hitRegions, id.code, onControlBounds)
            }
            val handleDrag = dragModifier(FloatingControl.Toolbar, held.isEmpty() && !wheelActive,
                displayPanelOffset, when {
                    split && !collapsed && toolbarCollapsed -> toolbarBubbleGeometry
                    split && !collapsed -> splitGeometry.geometry(FloatingControl.Toolbar)
                    else -> panelGeometry
                })
            val outline = LocalAppColors.current.outline
            val panelModifier = positioned(FloatingControl.Toolbar, displayPanelOffset)
                .onSizeChanged { measuredPanelSize = FloatingSize(it.width.toFloat(), it.height.toFloat()) }
            if (collapsed) {
                val collapsedLabel = if (split) "展开全部控件" else "展开鼠标工具岛"
                TooltipBox(tooltip = { PlainTooltip { Text(collapsedLabel) } },
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    state = rememberTooltipState(), modifier = panelModifier) {
                Surface(modifier = Modifier.size(56.dp).then(handleDrag), shape = CircleShape, color = CardSurface,
                    border = BorderStroke(1.dp, outline), shadowElevation = 5.dp,
                    onClick = { if (draggingId == null) onSettingsChange { it.copy(islandCollapsed = false) } }) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_touchpad), collapsedLabel, tint = Green, modifier = Modifier.size(25.dp))
                    }
                }
                }
            } else if (split && toolbarCollapsed) {
                TooltipBox(tooltip = { PlainTooltip { Text("展开工具栏") } },
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    state = rememberTooltipState(), modifier = panelModifier) {
                    Surface(modifier = Modifier.size(56.dp).then(handleDrag)
                        .trackRegion(hitRegions, "toolbarCollapsed", onControlBounds), shape = CircleShape,
                        color = CardSurface, border = BorderStroke(1.dp, outline), shadowElevation = 5.dp,
                        onClick = { if (draggingId == null) setToolbarCollapsed(false) }) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_touchpad), "展开工具栏", tint = Green, modifier = Modifier.size(25.dp))
                        }
                    }
                }
            } else {
                Surface(panelModifier.width(panelWidth).then(if (editing) handleDrag else Modifier), color = CardSurface,
                    shape = RoundedCornerShape(20.dp), border = BorderStroke(if (editing) 2.dp else 1.dp,
                        if (editing) Green else outline.copy(alpha = .45f)), shadowElevation = 5.dp) {
                    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                         CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
                              val splitToolbarButtonModifier = Modifier
                             BoxWithConstraints(Modifier.fillMaxWidth().height(40.dp)) {
                             Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(if (split) (-5).dp else 2.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(if (split) 24.dp else 32.dp).fillMaxHeight().then(if (editing) Modifier else handleDrag)
                                    .onGloballyPositioned { onControlBounds("handle", it.boundsInRoot()) }
                                    .semantics { contentDescription = localize("拖动工具岛") }, contentAlignment = Alignment.CenterStart) {
                                    Icon(painterResource(R.drawable.ic_drag_handle), null,
                                        tint = Muted.copy(alpha = if (held.isEmpty() && !wheelActive) 1f else .35f),
                                        modifier = Modifier.padding(start = 4.dp).size(20.dp))
                                }
                                if (split) {
                                    ToolbarButton("toolbarCollapse", "收起工具栏", R.drawable.ic_collapse, canChange, {
                                        if (controlsIdle()) { editing = false; setToolbarCollapsed(true) }
                                    }, onControlBounds, splitToolbarButtonModifier)
                                    if (editing) ToolbarButton("reset", "恢复默认布局", R.drawable.ic_reset_layout, canChange, {
                                        if (controlsIdle()) saveLayout { SplitControlLayout.defaults() }
                                    }, onControlBounds, splitToolbarButtonModifier)
                                    ToolbarButton("collapse", "隐藏全部控件", R.drawable.ic_hide_controls, canChange, {
                                        if (controlsIdle()) { editing = false; onSettingsChange { it.copy(islandCollapsed = true) } }
                                    }, onControlBounds, splitToolbarButtonModifier)
                                     ToolbarButton(if (editing) "done" else "layout", if (editing) "完成布局" else "调整布局",
                                         if (editing) R.drawable.ic_done else R.drawable.ic_layout, canChange,
                                         { if (controlsIdle()) editing = !editing }, onControlBounds, splitToolbarButtonModifier,
                                         iconSize = 18.dp)
                                     ToolbarButton("merge", "合并控件", R.drawable.ic_merge, canChange, {
                                         if (controlsIdle()) { editing = false; onSettingsChange { it.copy(controlsSplit = false) } }
                                     }, onControlBounds, splitToolbarButtonModifier)
                                     ToolbarButton("exit", "退出全屏", R.drawable.ic_exit, draggingId == null, onExit, onControlBounds,
                                         splitToolbarButtonModifier, iconSize = 16.dp)
                                } else {
                                    ToolbarButton("collapse", "收起工具岛", R.drawable.ic_collapse, canChange, {
                                        if (controlsIdle()) { editing = false; onSettingsChange { it.copy(islandCollapsed = true) } }
                                    }, onControlBounds, splitToolbarButtonModifier, placementOffset = (-10).dp)
                                    Spacer(Modifier.weight(1f))
                                    // Keep the split icon fixed: 40 + 2 + 9 - 16 = 35dp between icon centers.
                                    ToolbarButton("keyboard", "文字输入", R.drawable.ic_keyboard, canChange, onKeyboard,
                                        onControlBounds, splitToolbarButtonModifier, placementOffset = 16.dp)
                                    ToolbarButton("split", "分裂控件", R.drawable.ic_split, canChange, {
                                        if (controlsIdle()) {
                                            saveLayout { splitGeometry.layout(offsets) }
                                            onSettingsChange { it.copy(controlsSplit = true, islandCollapsed = false) }
                                            editing = false
                                        }
                                    }, onControlBounds, splitToolbarButtonModifier,
                                        iconSize = 20.dp, placementOffset = 9.dp)
                                    ToolbarButton("exit", "退出全屏", R.drawable.ic_exit, draggingId == null, onExit, onControlBounds,
                                        splitToolbarButtonModifier, iconModifier = Modifier.offset(x = (-1).dp), iconSize = 16.dp)
                                }
                            }
                             // Anchor the 40dp button in the full row so its shifted center remains tappable.
                             // Keyboard and split retain their positions; adjacent centers are 35dp apart.
                             if (!split) {
                                 ToolbarButton("black", "黑屏", R.drawable.ic_screen_off, canChange && blackScreenAvailable,
                                     onBlackScreen, onControlBounds, iconSize = 18.dp, placementOffset = maxWidth - 182.dp)
                                 ToolbarButton("gyro", gyroLabel, R.drawable.ic_gyroscope, canChange && gyroAvailable,
                                     onGyro, onControlBounds, placementOffset = maxWidth - 147.dp, selected = gyroSelected)
                             }
                             }
                        }
                        if (!split) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                MouseKey.entries.forEach { key -> FloatingMouseButton(key,
                                    Modifier.weight(1f).onGloballyPositioned { onControlBounds(key.wire, it.boundsInRoot()) }, enabled,
                                    onDown = { held = held + key; onDown(key) }, onUp = { held = held - key; onUp(key) }) }
                            }
                            FloatingScrollWheel(Modifier.fillMaxWidth().padding(horizontal = 8.dp), enabled,
                                onScroll, onScrollHold, { wheelActive = it }, onControlBounds = onControlBounds)
                        }
                    }
                }
            }
            if (split && !collapsed) {
                MouseKey.entries.forEach { mouse ->
                    val id = when (mouse) { MouseKey.Left -> FloatingControl.Left; MouseKey.Middle -> FloatingControl.Middle; MouseKey.Right -> FloatingControl.Right }
                    val base = offsets.getValue(id)
                    val modifier = positioned(id, base).width(72.dp).then(dragModifier(id, editing, base, splitGeometry.geometry(id)))
                        .then(if (editing) Modifier.border(2.dp, Green, RoundedCornerShape(15.dp)) else Modifier)
                    FloatingMouseButton(mouse, modifier, enabled,
                        onDown = { held = held + mouse; onDown(mouse) }, onUp = { held = held - mouse; onUp(mouse) }, standalone = true)
                }
                val id = FloatingControl.Wheel; val base = offsets.getValue(id)
                FloatingScrollWheel(positioned(id, base).width(56.dp).then(dragModifier(id, editing, base, splitGeometry.geometry(id)))
                    .then(if (editing) Modifier.border(2.dp, Green, RoundedCornerShape(14.dp)) else Modifier),
                    enabled, onScroll, onScrollHold, { wheelActive = it }, vertical = true, onControlBounds = onControlBounds)
                listOf(FloatingControl.Keyboard, FloatingControl.Gyroscope).forEach { control ->
                    val gyro = control == FloatingControl.Gyroscope
                    val label = if (gyro) gyroLabel else "文字输入"
                    val actionOffset = avoidFloatingInput(offsets.getValue(control), sizes.getValue(control),
                        inputInControls, keyboardTop, px(8f), px(8f))
                    TooltipBox(tooltip = { PlainTooltip { Text(label) } },
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), state = rememberTooltipState(),
                        modifier = positioned(control, actionOffset)) {
                        Surface(modifier = Modifier.size(52.dp)
                            .then(dragModifier(control, editing, actionOffset, splitGeometry.geometry(control)))
                            .semantics { selected = gyro && gyroSelected },
                            shape = RoundedCornerShape(15.dp), color = if (gyro && gyroSelected) PaleGreen else CardSurface,
                            border = BorderStroke(if (editing) 2.dp else 1.dp, if (editing) Green else outline),
                            shadowElevation = 5.dp, enabled = canChange && !editing && (!gyro || gyroAvailable),
                            onClick = { if (controlsIdle() && !editing) { if (gyro) onGyro() else onKeyboard() } }) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(painterResource(if (gyro) R.drawable.ic_gyroscope else R.drawable.ic_keyboard), localize(label),
                                    tint = Green.copy(alpha = if ((canChange || editing) && (!gyro || gyroAvailable)) 1f else .35f),
                                    modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }
            val message = if (editing) "布局模式：拖动控件，点击 ✓ 完成" else notice
            if (message != null || motionControls != null) {
                Column(Modifier.align(Alignment.TopCenter)
                    .padding(horizontal = 60.dp, vertical = 12.dp).widthIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    motionControls?.invoke(Modifier.trackRegion(hitRegions, "gyroControls", onControlBounds))
                    if (message != null) Surface(Modifier.trackRegion(hitRegions, "notice", onControlBounds),
                        color = CardSurface, shape = RoundedCornerShape(16.dp)) {
                        Text(message, Modifier.padding(12.dp), color = Ink, fontSize = 12.sp)
                    }
                }
            }
            if (showFullscreenActions) {
                // Keep the open tool menu modal to the fullscreen controls and close it
                // when the user taps anywhere outside the menu or its trigger button.
                Box(Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClick = { showFullscreenActions = false }))
            }
            IconButton(onClick = { showFullscreenActions = !showFullscreenActions }, enabled = controlsIdle(),
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(40.dp)
                    .trackRegion(hitRegions, "fullscreenTools", onControlBounds)) {
                Icon(painterResource(R.drawable.ic_tools), "全屏工具", tint = Green, modifier = Modifier.size(22.dp))
            }
            if (showFullscreenActions) {
                Surface(Modifier.align(Alignment.TopStart)
                    .padding(start = if (landscape) 56.dp else 8.dp,
                        top = if (landscape) 8.dp else 56.dp)
                    .width(IntrinsicSize.Max).widthIn(min = if (landscape) 220.dp else 168.dp,
                        max = if (landscape) 300.dp else 280.dp)
                    .trackRegion(hitRegions, "fullscreenToolsPanel", onControlBounds),
                    color = CardSurface, shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, outline.copy(alpha = .5f)), shadowElevation = 7.dp) {
                    Column(Modifier.padding(vertical = 6.dp)
                        .heightIn(max = if (landscape) 320.dp else 420.dp)
                        .verticalScroll(rememberScrollState())) {
                        FullscreenToolRow(R.drawable.ic_settings_preview, "调整速度", compact = landscape) {
                            showFullscreenActions = false; showSpeedPanel = true
                        }
                        if (gyroSelected) FullscreenToolRow(R.drawable.ic_gyroscope, "手机动作",
                            enabled = gyroAvailable, compact = landscape) {
                            showFullscreenActions = false; showGyroActionPanel = true
                        }
                        FullscreenToolRow(R.drawable.ic_reset_layout, "旋转", compact = landscape) {
                            showFullscreenActions = false; showRotationPanel = true
                        }
                        FullscreenToolRow(R.drawable.ic_gyroscope,
                            if (gyroSelected) "切换为触控板" else "切换为体感", enabled = gyroAvailable,
                            compact = landscape) {
                            showFullscreenActions = false; onGyro()
                        }
                        if (settings.fullscreenHelpEnabled) FullscreenToolRow(R.drawable.ic_help, "帮助",
                            compact = landscape) {
                            showFullscreenActions = false; showFullscreenHelp = true
                        }
                    }
                }
            }
        }
    }
    if (showFullscreenHelp) {
        FullscreenHelpDialog(onClose = { showFullscreenHelp = false }, onDisable = {
            showFullscreenHelp = false
            onSettingsChange { it.copy(fullscreenHelpEnabled = false) }
        })
    }
    if (showRotationPanel) {
        FullscreenRotationDialog(settings, displayRotation,
            onSettingsChange = onSettingsChange, onClose = { showRotationPanel = false })
    }
    if (showSpeedPanel) {
        FullscreenSpeedDialog(settings, gyroAvailable,
            onSettingsChange = onSettingsChange, onClose = { showSpeedPanel = false })
    }
    if (showGyroActionPanel) {
        FullscreenGyroActionDialog(settings, gyroAvailable,
            onSettingsChange = onSettingsChange, onClose = { showGyroActionPanel = false })
    }
}

@Composable private fun FullscreenToolRow(icon: Int, label: String, enabled: Boolean = true,
    compact: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = if (compact) 44.dp else 48.dp)
        .clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = if (compact) 12.dp else 14.dp, vertical = if (compact) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(icon), localize(label), tint = if (enabled) Green else Muted, modifier = Modifier.size(20.dp))
        Text(label, color = if (enabled) Ink else Muted, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable internal fun fullscreenDialogModifier(maxWidth: Dp = 420.dp, landscapeMaxWidth: Dp = 640.dp): Modifier {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    return Modifier.widthIn(max = if (landscape) landscapeMaxWidth else maxWidth)
        .fillMaxWidth()
        .padding(horizontal = if (landscape) 20.dp else 16.dp)
}

@Composable private fun fullscreenLandscapeColumns(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
        configuration.screenWidthDp >= 600
}

@Composable internal fun fullscreenDialogContentMaxHeight(defaultMax: Dp): Dp {
    val configuration = LocalConfiguration.current
    val reserved = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 132 else 180
    return minOf(defaultMax, configuration.screenHeightDp.minus(reserved).coerceAtLeast(220).dp)
}

@Composable private fun FullscreenRotationDialog(settings: ControlSettings, displayRotation: Int,
    onSettingsChange: ((ControlSettings) -> ControlSettings) -> Unit, onClose: () -> Unit) {
    val currentAngle = (displayRotation.coerceIn(0, 3) * 90)
    val selectedAngle = if (settings.fullscreenRotationMode == FullscreenRotationMode.Manual)
        settings.fullscreenRotation else currentAngle
    val landscape = fullscreenLandscapeColumns()
    @Composable fun ModeControls() {
        FullscreenModeTabs(FullscreenRotationMode.entries, settings.fullscreenRotationMode, { it.label }) { mode ->
            onSettingsChange {
                if (mode == FullscreenRotationMode.Manual) it.copy(fullscreenRotationMode = mode, fullscreenRotation = selectedAngle)
                else it.copy(fullscreenRotationMode = mode)
            }
        }
        SettingsHint(if (settings.fullscreenRotationMode == FullscreenRotationMode.FollowSystem)
            "全屏跟随手机当前显示方向。"
        else "全屏锁定所选方向，触摸和体感统一使用该方向。")
    }
    @Composable fun DirectionControls() {
        Text("手动方向", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        FullscreenRotationChoices(selectedAngle, grid = landscape) { angle ->
            if (settings.fullscreenRotationMode != FullscreenRotationMode.Manual || settings.fullscreenRotation != angle) {
                onSettingsChange { it.copy(fullscreenRotationMode = FullscreenRotationMode.Manual, fullscreenRotation = angle) }
            }
        }
    }
    FullscreenDialog(onDismissRequest = onClose,
        title = { FullscreenDialogHeading("全屏方向") },
        text = {
            if (landscape) {
                Row(Modifier.fillMaxWidth().heightIn(max = fullscreenDialogContentMaxHeight(440.dp)),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(.9f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ModeControls()
                    }
                    Column(Modifier.weight(1.1f)
                        .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DirectionControls()
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().heightIn(max = fullscreenDialogContentMaxHeight(440.dp))
                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeControls()
                    DirectionControls()
                }
            }
        }, confirmButton = { TextButton(onClick = onClose) { Text("关闭") } })
}

@Composable private fun <T> FullscreenModeTabs(options: List<T>, selected: T, label: (T) -> String,
    enabled: (T) -> Boolean = { true }, onSelect: (T) -> Unit) {
    SettingsSegmentedChoices(options, selected, label, enabled,
        vertical = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE,
        onSelect = onSelect)
}

@Composable private fun FullscreenRotationChoices(selectedAngle: Int, grid: Boolean = false, onSelect: (Int) -> Unit) {
    val outline = LocalAppColors.current.outline
    val screenColor = PaleGreen
    val angles = listOf(0, 90, 180, 270)
    @Composable fun Choice(angle: Int, modifier: Modifier) {
        val selected = selectedAngle == angle
        Surface(
            // Landscape uses a compact 2×2 grid so both rows stay visible beside
            // the mode controls; portrait keeps the roomier cards.
            modifier = modifier.height(if (grid) 56.dp else 84.dp)
                .clickable(onClick = { onSelect(angle) })
                .semantics {
                    this.selected = selected
                    contentDescription = localize(when (angle) {
                        0 -> "竖屏方向"
                        90 -> "左侧横屏方向"
                        180 -> "倒置竖屏方向"
                        else -> "右侧横屏方向"
                    })
                },
            color = if (selected) PaleGreen.copy(alpha = .38f) else Soft,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, if (selected) Green else outline.copy(alpha = .38f))) {
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 30.dp, height = 50.dp).graphicsLayer {
                    // Display rotation is opposite to the physical phone orientation.
                    rotationZ = -angle.toFloat()
                }.background(CardSurface, RoundedCornerShape(7.dp))
                    .border(1.5.dp, if (selected) Green else outline, RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.padding(4.dp).fillMaxSize()
                        .background(screenColor, RoundedCornerShape(4.dp)))
                    Box(Modifier.padding(top = 4.dp).size(10.dp, 2.5.dp)
                        .background(Muted, RoundedCornerShape(2.dp)))
                }
            }
        }
    }
    if (grid) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            angles.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { angle -> Choice(angle, Modifier.weight(1f)) }
                }
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            angles.forEach { angle -> Choice(angle, Modifier.weight(1f)) }
        }
    }
}

@Composable private fun FullscreenSpeedDialog(settings: ControlSettings, gyroAvailable: Boolean,
    onSettingsChange: ((ControlSettings) -> ControlSettings) -> Unit, onClose: () -> Unit) {
    val gyro = settings.mouseControlMode == MouseControlMode.Gyroscope
    val enabled = !gyro || gyroAvailable
    val value = if (gyro) settings.gyro.sensitivity else settings.sensitivity
    val landscape = fullscreenLandscapeColumns()
    val acceleration = if (gyro) settings.gyro.acceleration else settings.touchpadAcceleration
    var expanded by rememberSaveable(gyro) { mutableStateOf(false) }
    val leftScroll = rememberScrollState()
    val rightScroll = rememberScrollState()
    val portraitScroll = rememberScrollState()
    val changeAcceleration: (PointerAccelerationSettings) -> Unit = { next ->
        onSettingsChange { current ->
            if (gyro) current.copy(gyro = current.gyro.copy(acceleration = next))
            else current.copy(touchpadAcceleration = next)
        }
    }
    @Composable fun Sensitivity() {
        SettingsSlider("基础灵敏度", value, .1f..10f, enabled = enabled) { next ->
            onSettingsChange { current ->
                if (gyro) current.copy(gyro = current.gyro.copy(sensitivity = next))
                else current.copy(sensitivity = next)
            }
        }
    }
    @Composable fun Strength() {
        PointerAccelerationStrengthOptions(acceleration, gyroscope = gyro, enabled = enabled, compact = true,
            expanded = expanded, onExpandedChange = { expanded = it }, onChange = changeAcceleration)
    }
    FullscreenDialog(onDismissRequest = onClose,
        title = { FullscreenDialogHeading("调整速度", settings.mouseControlMode) },
        text = {
            if (landscape) {
                Row(Modifier.fillMaxWidth().heightIn(max = fullscreenDialogContentMaxHeight(520.dp)),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f).verticalScroll(leftScroll),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Sensitivity()
                        SettingsDivider()
                        Text("指针加速", color = Ink, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                        if (gyro && !gyroAvailable) SettingsHint("当前手机没有可用的陀螺仪，无法使用体感鼠标。")
                        PointerAccelerationModeOptions(acceleration, gyroscope = gyro, enabled = enabled, compact = true,
                            singleRow = true, showHint = false, onChange = changeAcceleration)
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rightScroll),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (acceleration.mode == PointerAccelerationMode.Off) {
                            SettingsHint("按基础灵敏度保持固定移动比例。")
                        } else Strength()
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().heightIn(max = fullscreenDialogContentMaxHeight(520.dp))
                    .verticalScroll(portraitScroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Sensitivity()
                    SettingsDivider()
                    Text("指针加速", color = Ink, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                    if (gyro && !gyroAvailable) SettingsHint("当前手机没有可用的陀螺仪，无法使用体感鼠标。")
                    PointerAccelerationModeOptions(acceleration, gyroscope = gyro, enabled = enabled, compact = true,
                        onChange = changeAcceleration)
                    if (acceleration.mode != PointerAccelerationMode.Off) {
                        SettingsDivider()
                        Strength()
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onClose) { Text("完成") } })
}

@Composable private fun FullscreenGyroActionDialog(settings: ControlSettings, gyroAvailable: Boolean,
    onSettingsChange: ((ControlSettings) -> ControlSettings) -> Unit, onClose: () -> Unit) {
    val landscape = fullscreenLandscapeColumns()
    FullscreenDialog(onDismissRequest = onClose,
        title = { FullscreenDialogHeading("手机动作") },
        text = {
            if (landscape) {
                Row(Modifier.fillMaxWidth().heightIn(max = fullscreenDialogContentMaxHeight(500.dp)),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(.85f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FullscreenModeTabs(GyroDirectionMode.entries, settings.gyro.directionMode, { it.label },
                            { gyroAvailable }) { mode ->
                            onSettingsChange { it.copy(gyro = it.gyro.copy(directionMode = mode)) }
                        }
                    }
                    Column(Modifier.weight(1.15f)
                        .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GyroDirectionPreview(settings.gyro.directionMode)
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().heightIn(max = fullscreenDialogContentMaxHeight(500.dp))
                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FullscreenModeTabs(GyroDirectionMode.entries, settings.gyro.directionMode, { it.label },
                        { gyroAvailable }) { mode ->
                        onSettingsChange { it.copy(gyro = it.gyro.copy(directionMode = mode)) }
                    }
                    GyroDirectionPreview(settings.gyro.directionMode)
                }
            }
        }, confirmButton = { TextButton(onClick = onClose) { Text("完成") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ToolbarButton(name: String, label: String, icon: Int, enabled: Boolean,
    onClick: () -> Unit, onBounds: (String, Rect) -> Unit, modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier, iconSize: Dp = 20.dp, placementOffset: Dp = 0.dp, selected: Boolean = false) {
    val interactions = remember(name) { MutableInteractionSource() }
    val pressAlpha = remember(name) { Animatable(0f) }
    val focused by interactions.collectIsFocusedAsState()
    LaunchedEffect(interactions, enabled) {
        if (!enabled) pressAlpha.snapTo(0f)
        else interactions.interactions.filterIsInstance<PressInteraction>().collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> pressAlpha.snapTo(.12f)
                is PressInteraction.Release, is PressInteraction.Cancel -> pressAlpha.animateTo(0f, tween(160))
                else -> Unit
            }
        }
    }
    TooltipBox(tooltip = { PlainTooltip { Text(label) } },
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        state = rememberTooltipState(), modifier = Modifier.offset(x = placementOffset)) {
        Box(modifier = modifier.size(40.dp)
                .clickable(interactionSource = interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { this.selected = selected }
                .onGloballyPositioned { onBounds(name, it.boundsInRoot()) }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(32.dp)
                .background(if (selected) PaleGreen else Color.Transparent, RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = maxOf(pressAlpha.value, if (focused && enabled) .12f else 0f)), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), localize(label), tint = Green.copy(alpha = if (enabled) 1f else .35f),
                    modifier = Modifier.size(iconSize).then(iconModifier))
            }
        }
    }
}

private fun Modifier.trackRegion(regions: FullscreenHitRegions, id: String, onBounds: (String, Rect) -> Unit) = composed {
    DisposableEffect(regions, id) { onDispose { regions.controls.remove(id) } }
    onGloballyPositioned { regions.controls[id] = it.boundsInRoot(); onBounds(id, it.boundsInRoot()) }
}

private fun Modifier.islandDrag(enabled: Boolean, onStart: () -> Unit, onDelta: (Offset) -> Unit, onEnd: () -> Unit): Modifier = composed {
    val start by rememberUpdatedState(onStart); val delta by rememberUpdatedState(onDelta); val end by rememberUpdatedState(onEnd)
    pointerInput(enabled) {
        if (enabled) try {
            detectDragGestures(onDragStart = { start() }, onDragEnd = { end() }, onDragCancel = { end() }) { change, amount ->
                change.consume(); delta(amount)
            }
        } finally { end() }
    }
}
