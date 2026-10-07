package com.windy.phonemouse.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class ThemeMode(val code: String, val label: String) {
    System("system", "跟随系统"),
    Light("light", "浅色"),
    Dark("dark", "深色");

    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code } ?: System
    }
}

internal data class AppColors(
    val dark: Boolean,
    val ink: Color,
    val green: Color,
    val paleGreen: Color,
    val page: Color,
    val card: Color,
    val soft: Color,
    val muted: Color,
    val outline: Color,
    val onGreen: Color,
) {
    companion object {
        val Light = AppColors(false, Color(0xFF20382C), Color(0xFF285C45), Color(0xFFE5EEE5),
            Color(0xFFF3F5F1), Color(0xFFFDFEF9), Color(0xFFE9EDE5), Color(0xFF728076),
            Color(0xFFBBC8BC), Color(0xFFFDFEF9))
        val Dark = AppColors(true, Color(0xFFE8E8E8), Color(0xFF97C8A8), Color(0xFF27382D),
            Color(0xFF121212), Color(0xFF1D1D1D), Color(0xFF282828), Color(0xFFA5A5A5),
            Color(0xFF575757), Color(0xFF14291C))
    }
}

internal val LocalAppColors = staticCompositionLocalOf { AppColors.Light }
internal val Ink: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.ink
internal val Green: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.green
internal val PaleGreen: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.paleGreen
internal val Page: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.page
internal val CardSurface: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.card
internal val Soft: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.soft
internal val Muted: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.muted

@Composable internal fun PhoneMouseTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val colors = if (dark) AppColors.Dark else AppColors.Light
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = colors.green, onPrimary = colors.onGreen,
        primaryContainer = colors.paleGreen, onPrimaryContainer = colors.ink,
        inversePrimary = if (dark) AppColors.Light.green else AppColors.Dark.green,
        secondary = colors.green, onSecondary = colors.onGreen,
        secondaryContainer = colors.paleGreen, onSecondaryContainer = colors.ink,
        tertiary = colors.green, onTertiary = colors.onGreen,
        tertiaryContainer = colors.paleGreen, onTertiaryContainer = colors.ink,
        background = colors.page, onBackground = colors.ink,
        surface = colors.card, onSurface = colors.ink,
        surfaceVariant = colors.soft, onSurfaceVariant = colors.muted,
        surfaceTint = colors.green,
        inverseSurface = if (dark) AppColors.Light.soft else AppColors.Dark.soft,
        inverseOnSurface = if (dark) AppColors.Light.ink else AppColors.Dark.ink,
        outline = colors.outline, outlineVariant = colors.outline.copy(alpha = .5f),
        surfaceBright = colors.soft, surfaceDim = colors.page,
        surfaceContainerLowest = colors.page, surfaceContainerLow = colors.card,
        surfaceContainer = colors.card, surfaceContainerHigh = colors.soft,
        surfaceContainerHighest = colors.soft,
    )
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
