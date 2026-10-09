package com.windy.phonemouse.app

import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Shared fullscreen dialog chrome with a compact footer and a bounded content area. */
@Composable internal fun FullscreenDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    val orientation = LocalConfiguration.current.orientation
    var previousOrientation by remember { mutableStateOf(orientation) }
    val rotationProgress = remember { Animatable(1f) }
    val rotationOffset = with(LocalDensity.current) { 4.dp.toPx() }
    LaunchedEffect(orientation) {
        if (previousOrientation != orientation) {
            previousOrientation = orientation
            rotationProgress.snapTo(.94f)
            rotationProgress.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
        }
    }
    Dialog(onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = fullscreenDialogModifier().padding(vertical = 12.dp)
            .graphicsLayer {
                alpha = rotationProgress.value
                translationY = (1f - rotationProgress.value) / .06f * rotationOffset
            },
            shape = AlertDialogDefaults.shape, color = CardSurface) {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                    ProvideTextStyle(MaterialTheme.typography.titleMedium.copy(
                        fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold), title)
                }
                Spacer(Modifier.height(8.dp))
                // Keep the footer reachable when the keyboard or a short landscape window
                // reduces the available height. Short content still determines dialog height.
                Box(Modifier.weight(1f, fill = false).fillMaxWidth().padding(horizontal = 24.dp)) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium, text)
                }
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

@Composable internal fun FullscreenDialogHeading(label: String, mode: MouseControlMode? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Ink, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
        if (mode != null) {
            val gyro = mode == MouseControlMode.Gyroscope
            Surface(Modifier.weight(1f, fill = false),
                color = if (gyro) PaleGreen else Soft, shape = RoundedCornerShape(8.dp)) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(painterResource(if (gyro) R.drawable.ic_gyroscope else R.drawable.ic_touchpad),
                        null, tint = Green, modifier = Modifier.size(16.dp))
                    Text(if (gyro) "体感鼠标" else "触控板", color = Green, fontSize = 12.sp, lineHeight = 16.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false))
                }
            }
        }
    }
}
