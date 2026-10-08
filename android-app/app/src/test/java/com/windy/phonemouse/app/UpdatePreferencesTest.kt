package com.windy.phonemouse.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UpdatePreferencesTest {
    private suspend fun withPreferencesFile(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("phone-mouse-update-test").toFile()
        try {
            block(File(directory, "controls.preferences_pb"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private suspend fun withStore(file: File, block: suspend (SettingsStore) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            block(SettingsStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })))
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test fun restartRestoresFrequencyAndLastAttempt() = runBlocking {
        withPreferencesFile { file ->
            withStore(file) { store ->
                assertEquals(UpdateCheckFrequency.Daily, store.settings.first().updateCheckFrequency)
                assertNull(store.lastUpdateCheckAt.first())
                store.save(ControlSettings(updateCheckFrequency = UpdateCheckFrequency.Weekly))
                store.recordUpdateCheck(1_000)
            }
            withStore(file) { store ->
                val settings = store.settings.first()
                val lastCheckAt = store.lastUpdateCheckAt.first()
                assertEquals(UpdateCheckFrequency.Weekly, settings.updateCheckFrequency)
                assertEquals(1_000L, lastCheckAt)
                val schedule = AppUpdateSchedule()
                schedule.restore(settings.updateCheckFrequency, lastCheckAt)
                assertFalse(schedule.isDue(1_000 + UpdateCheckFrequency.Daily.intervalMillis!!))
                assertTrue(schedule.isDue(1_000 + UpdateCheckFrequency.Weekly.intervalMillis!!))
            }
        }
    }

    @Test fun timestampWritesPreserveControlsAndSettingsSavesPreserveTimestamp() = runBlocking {
        withPreferencesFile { file ->
            withStore(file) { store ->
                val settings = ControlSettings(address = "192.168.1.10", sensitivity = 2f,
                    languageMode = LanguageMode.English, updateCheckFrequency = UpdateCheckFrequency.Off)
                store.save(settings)
                store.recordUpdateCheck(2_000)
                assertEquals(settings, store.settings.first())
                store.save(settings.copy(updateCheckFrequency = UpdateCheckFrequency.Daily))
                assertEquals(2_000L, store.lastUpdateCheckAt.first())
            }
            withStore(file) { store ->
                assertEquals(UpdateCheckFrequency.Daily, store.settings.first().updateCheckFrequency)
                assertEquals(2_000L, store.lastUpdateCheckAt.first())
            }
        }
    }
}
