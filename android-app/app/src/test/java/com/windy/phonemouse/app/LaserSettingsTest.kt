package com.windy.phonemouse.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class LaserSettingsTest {
    @Test fun settingsSurviveReopeningPreferencesButEnabledStateDoesNot() = runBlocking {
        val directory = Files.createTempDirectory("phone-mouse-laser").toFile()
        val file = directory.resolve("controls.preferences_pb")
        val wanted = LaserSettings(LaserColor.Blue, trail = false, fadeMs = 1700, sizePercent = 175, trailLengthPx = 180)
        val firstJob = SupervisorJob()
        val secondJob = SupervisorJob()
        try {
            val first = SettingsStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + firstJob)) { file })
            first.save(ControlSettings(laser = wanted))
            firstJob.cancelAndJoin()
            val second = SettingsStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + secondJob)) { file })
            val restored = second.settings.first()
            assertEquals(wanted, restored.laser)
            assertFalse(UiState(settings = restored).presentation.laser)
            val largest = LaserSettings(sizePercent = 300, trailLengthPx = 300)
            second.save(ControlSettings(laser = largest))
            assertEquals(largest, second.settings.first().laser)
            second.save(ControlSettings())
            assertEquals(LaserSettings(), second.settings.first().laser)
        } finally {
            firstJob.cancelAndJoin(); secondJob.cancelAndJoin(); directory.deleteRecursively()
        }
    }
    @Test fun sizeDefaultsAndBoundsAreStable() {
        assertEquals(110, LaserSettings().sizePercent)
        assertEquals(800, LaserSettings().fadeMs)
        assertEquals(50, LaserSettings(sizePercent = Int.MIN_VALUE).sanitized().sizePercent)
        assertEquals(300, LaserSettings(sizePercent = Int.MAX_VALUE).sanitized().sizePercent)
        assertEquals(175, LaserSettings(sizePercent = 175).sanitized().sizePercent)
    }
    @Test fun trailLengthDefaultsAndBoundsAreStable() {
        assertEquals(180, LaserSettings().trailLengthPx)
        assertEquals(20, LaserSettings(trailLengthPx = Int.MIN_VALUE).sanitized().trailLengthPx)
        assertEquals(300, LaserSettings(trailLengthPx = Int.MAX_VALUE).sanitized().trailLengthPx)
        assertEquals(180, LaserSettings(trailLengthPx = 180).sanitized().trailLengthPx)
    }

    @Test fun preferencesWithoutSizeUseDefault() = runBlocking {
        val directory = Files.createTempDirectory("phone-mouse-laser-default").toFile()
        val job = SupervisorJob()
        try {
            val store = SettingsStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) {
                directory.resolve("controls.preferences_pb")
            })
            assertEquals(110, store.settings.first().laser.sizePercent)
            assertEquals(180, store.settings.first().laser.trailLengthPx)
            assertEquals(800, store.settings.first().laser.fadeMs)
        } finally { job.cancelAndJoin(); directory.deleteRecursively() }
    }
    @Test fun laserLabelsAndColorNamesTranslate() {
        for (color in LaserColor.entries) assertTrue(Localization.text(color.label, AppLanguage.English).all { it.code < 128 })
        for (label in listOf("开启激光笔", "关闭激光笔", "激光笔设置", "拖尾与淡出", "淡出时长（秒）", "只淡出拖尾，激光点始终可见。",
            "大小", "激光大小", "外观预览", "光点、光晕和拖尾粗细同步缩放。", "当前接收端不支持大小调整，更新 Windows 接收端后生效。",
            "最长长度", "限制拖尾最长长度，实际长度仍随移动速度变化。", "当前接收端不支持拖尾长度调整，更新 Windows 接收端后生效。",
            "当前接收端最高支持 200% 大小，暂按 200% 生效；更新 Windows 接收端后支持 300%。",
            "当前接收端最长支持 200 px 拖尾，暂按 200 px 生效；更新 Windows 接收端后支持 300 px。")) {
            assertTrue(Localization.text(label, AppLanguage.English).all { it.code < 128 })
        }
    }
}
