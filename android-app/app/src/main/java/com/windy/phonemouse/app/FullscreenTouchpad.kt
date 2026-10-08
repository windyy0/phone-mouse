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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
    onMotionBlocked: (Boolean) -> Unit = {}) {
    FullscreenTouchpadLayout(state.settings, inputEnabled, state.notice,
        onSettingsChange = { vm.updateSettings(it) },
        onDown = { vm.buttonDown(it.wire, "floating") }, onUp = { vm.buttonUp(it.wire, "floating") },
        onScroll = vm::scroll, onScrollHold = vm::scrollHoldHaptic, onExit = onExit,
        onKeyboard = onKeyboard, inputPanelBounds = inputPanelBounds, onMotionBlocked = onMotionBlocked,
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
    var showFullscreenHelp by remember { mutableStateOf(false) }
    val focused = LocalWindowInfo.current.isWindowFocused
    val motionBlockedCallback by rememberUpdatedState(onMotionBlocked)
    SideEffect {
        motionBlockedCallback(editing || draggingId != null ||
            (settings.controlsSplit && settings.islandCollapsed) || showFullscreenHelp)
    }
    DisposableEffect(Unit) { onDispose { motionBlockedCallback(false) } }
    val enabled = inputEnabled && focused && !editing && draggingId == null && !showFullscreenHelp
    fun controlsIdle() = held.isEmpty() && !wheelActive && draggingId == null
    val canChange = controlsIdle() && !showFullscreenHelp
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
            if (settings.fullscreenHelpEnabled) {
                IconButton(onClick = { showFullscreenHelp = true }, enabled = controlsIdle(),
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(40.dp)
                        .trackRegion(hitRegions, "fullscreenHelp", onControlBounds)) {
                    Icon(painterResource(R.drawable.ic_help), "全屏操作说明", tint = Green, modifier = Modifier.size(22.dp))
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
