package com.windy.phonemouse.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdateTest {
    private fun manifest(code: Int = 4): JSONObject = JSONObject("""
        {"schema":1,"version":"1.0.1","release_page":"https://example.com/releases/v1.0.1",
         "notes":"更新说明","android":{"version_name":"1.0.1","version_code":$code,
         "url":"https://example.com/app.apk"}}
    """.trimIndent())

    @Test fun dailyAndWeeklyIntervalsIncludeTheirExactBoundary() {
        for (frequency in listOf(UpdateCheckFrequency.Daily, UpdateCheckFrequency.Weekly)) {
            val interval = frequency.intervalMillis!!
            assertTrue(shouldCheckForUpdate(null, 100, frequency))
            assertFalse(shouldCheckForUpdate(100, 101, frequency))
            assertFalse(shouldCheckForUpdate(100, 100 + interval - 1, frequency))
            assertTrue(shouldCheckForUpdate(100, 100 + interval, frequency))
        }
    }

    @Test fun waitsForSavedSettingsAndKeepsRecordedAttemptsAcrossRestart() {
        val schedule = AppUpdateSchedule()
        assertFalse(schedule.isDue(100))
        schedule.restore(UpdateCheckFrequency.Daily, null)
        assertTrue(schedule.isDue(100))
        schedule.recordAttempt(100) // Success and failure use the same throttle.
        assertFalse(schedule.isDue(101))
        val restored = AppUpdateSchedule()
        restored.restore(UpdateCheckFrequency.Daily, 100)
        assertFalse(restored.isDue(101))
        assertTrue(restored.isDue(100 + UpdateCheckFrequency.Daily.intervalMillis!!))
    }

    @Test fun changingFrequencyReevaluatesTheExistingTimestamp() {
        val schedule = AppUpdateSchedule()
        schedule.restore(UpdateCheckFrequency.Weekly, 100)
        val tomorrow = 100 + UpdateCheckFrequency.Daily.intervalMillis!!
        assertFalse(schedule.isDue(tomorrow))
        schedule.setFrequency(UpdateCheckFrequency.Daily)
        assertTrue(schedule.isDue(tomorrow))
        schedule.setFrequency(UpdateCheckFrequency.Weekly)
        assertFalse(schedule.isDue(tomorrow))
    }

    @Test fun offDisablesAutomaticChecksAndInvalidatesInFlightPrompts() {
        assertFalse(shouldCheckForUpdate(null, 100, UpdateCheckFrequency.Off))
        assertFalse(shouldCheckForUpdate(100, Long.MAX_VALUE, UpdateCheckFrequency.Off))
        val schedule = AppUpdateSchedule()
        schedule.restore(UpdateCheckFrequency.Daily, null)
        val generation = schedule.promptGeneration
        assertTrue(schedule.mayPrompt(generation))
        schedule.setFrequency(UpdateCheckFrequency.Off)
        assertFalse(schedule.isDue(100))
        assertFalse(schedule.mayPrompt(generation))
        schedule.setFrequency(UpdateCheckFrequency.Daily)
        assertFalse(schedule.mayPrompt(generation))
        assertTrue(schedule.mayPrompt(schedule.promptGeneration))
    }

    @Test fun clockRollbackChecksOnceThenUsesTheNewClock() {
        val schedule = AppUpdateSchedule()
        schedule.restore(UpdateCheckFrequency.Daily, 1_000)
        assertTrue(schedule.isDue(500))
        schedule.recordAttempt(500)
        assertFalse(schedule.isDue(501))
        assertTrue(schedule.isDue(500 + UpdateCheckFrequency.Daily.intervalMillis!!))
    }

    @Test fun frequencyDefaultsAndLabelsTranslate() {
        assertEquals(UpdateCheckFrequency.Daily, UpdateCheckFrequency.fromCode(null))
        assertEquals(UpdateCheckFrequency.Daily, UpdateCheckFrequency.fromCode("invalid"))
        assertEquals("Check frequency", Localization.text("检查频率", AppLanguage.English))
        assertEquals("Daily", UpdateCheckFrequency.Daily.localizedLabel(AppLanguage.English))
        assertEquals("Weekly", UpdateCheckFrequency.Weekly.localizedLabel(AppLanguage.English))
        assertEquals("Off", UpdateCheckFrequency.Off.localizedLabel(AppLanguage.English))
        assertEquals("关闭", UpdateCheckFrequency.Off.localizedLabel(AppLanguage.Chinese))
    }

    @Test fun usesAndroidVersionCodeAndDoesNotDowngrade() {
        assertEquals("1.0.1", parseAppUpdate(manifest().toString(), 3)?.versionName)
        assertNull(parseAppUpdate(manifest().toString(), 4))
        assertNull(parseAppUpdate(manifest().toString(), 5))
    }

    @Test fun invalidManifestsAreErrorsInsteadOfUpToDate() {
        for (body in listOf("{}", "<html>error</html>", manifest().put("schema", 2).toString(),
            manifest().put("schema", 1.5).toString(), manifest().put("version", "1.0.2").toString(),
            manifest().apply { getJSONObject("android").put("version_code", 4.5) }.toString(),
            manifest().apply { getJSONObject("android").put("version_name", "garbage") }.toString())) {
            assertThrows(Exception::class.java) { parseAppUpdate(body, 3) }
        }
    }

    @Test fun unsafeLinksAreRejectedBeforeVersionComparison() {
        for (url in listOf("http://example.com/app.apk", "file:///app.apk", "https://", "https://user@example.com/app.apk")) {
            assertThrows(Exception::class.java) {
                parseAppUpdate(manifest().apply { getJSONObject("android").put("url", url) }.toString(), 5)
            }
            assertThrows(Exception::class.java) { parseAppUpdate(manifest().put("release_page", url).toString(), 3) }
        }
    }

    @Test fun updateVersionSummariesTranslate() {
        assertEquals("Current version 1.0.0", Localization.text("当前版本 1.0.0", AppLanguage.English))
        assertEquals("New version available 1.0.1", Localization.text("发现新版本 1.0.1", AppLanguage.English))
    }
}
