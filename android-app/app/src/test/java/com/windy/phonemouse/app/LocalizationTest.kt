package com.windy.phonemouse.app

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizationTest {
    @Test fun chineseSystemLocaleSelectsChinese() {
        assertEquals(AppLanguage.Chinese, systemLanguage(listOf(Locale.SIMPLIFIED_CHINESE)))
        assertEquals(AppLanguage.Chinese, LanguageMode.System.effective(listOf(Locale("zh", "TW"))))
    }

    @Test fun nonChineseSystemLocaleSelectsEnglish() {
        assertEquals(AppLanguage.English, systemLanguage(listOf(Locale.US)))
        assertEquals(AppLanguage.English, LanguageMode.System.effective(listOf(Locale.JAPAN)))
    }

    @Test fun explicitModeOverridesSystemLocale() {
        assertEquals(AppLanguage.English, LanguageMode.English.effective(listOf(Locale.CHINA)))
        assertEquals(AppLanguage.Chinese, LanguageMode.Chinese.effective(listOf(Locale.US)))
    }

    @Test fun catalogTranslatesACommonLabel() {
        Localization.setMode(LanguageMode.English, listOf(Locale.CHINA))
        assertEquals("Touchpad", Localization.text("触控板"))
        Localization.setMode(LanguageMode.Chinese, listOf(Locale.US))
        assertEquals("触控板", Localization.text("触控板"))
    }

    @Test fun fullscreenMotionLabelsAndOrientationDescriptionsTranslateCompletely() {
        val labels = mapOf(
            "手机动作" to "Phone motion",
            "横竖屏单独设置" to "Separate portrait and landscape settings",
            "开启后，横屏和竖屏分别保存动作与反向设置；关闭时使用共用设置。" to "When enabled, portrait and landscape each save their own motion and axis directions. When disabled, the shared settings apply.",
            "竖屏" to "Portrait",
            "横屏" to "Landscape",
            "头部指向" to "Point with top",
            "侧边倾斜" to "Tilt sideways",
            "切换为体感" to "Switch to gyroscope",
            "左侧横屏方向" to "Landscape with top on left",
            "右侧横屏方向" to "Landscape with top on right",
            "查看说明" to "View explanation",
        )
        labels.forEach { (source, translated) ->
            assertEquals(translated, Localization.text(source, AppLanguage.English))
        }
    }

    @Test fun languageChangeInvalidatesExistingTextReaders() {
        Localization.setMode(LanguageMode.Chinese)
        Snapshot.sendApplyNotifications()
        var invalidated = false
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            observer.observeReads(Unit, { invalidated = true }) {
                assertEquals("触控板", localize("触控板"))
            }
            Localization.setMode(LanguageMode.English)
            Snapshot.sendApplyNotifications()
            assertTrue("Existing UI readers must refresh after selecting a language", invalidated)
            assertEquals("Touchpad", localize("触控板"))

            invalidated = false
            observer.observeReads(Unit, { invalidated = true }) {
                assertEquals("Touchpad", localize("触控板"))
            }
            Localization.setMode(LanguageMode.Chinese)
            Snapshot.sendApplyNotifications()
            assertTrue("Switching back must also refresh the current UI", invalidated)
            assertEquals("触控板", localize("触控板"))
        } finally {
            observer.stop()
            observer.clear()
            Localization.setMode(LanguageMode.System)
        }
    }

    @Test fun languageNamesRemainNativeAndCurrentLanguageHintIsComplete() {
        Localization.setMode(LanguageMode.English)
        try {
            assertEquals("中文", localize(LanguageMode.Chinese.label))
            assertEquals("English", localize(LanguageMode.English.label))
            assertEquals("Using English.", localize("当前使用English。"))
            assertEquals("Using 中文.", localize("当前使用中文。"))
        } finally {
            Localization.setMode(LanguageMode.System)
        }
    }
}
