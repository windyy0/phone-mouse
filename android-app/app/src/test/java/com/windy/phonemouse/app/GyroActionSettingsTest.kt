package com.windy.phonemouse.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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

class GyroActionSettingsTest {
    private val shared = GyroSettings(directionMode = GyroDirectionMode.Twist, reverseX = true)
    private val portrait = GyroActionSettings(GyroDirectionMode.Tilt, reverseY = true)
    private val landscape = GyroActionSettings(GyroDirectionMode.Twist, reverseX = true)
    private fun splitSettings() = ControlSettings(gyro = shared, gyroActionsSplit = true,
        portraitGyroAction = portrait, landscapeGyroAction = landscape)

    @Test fun defaultsKeepOneSharedActionUntilFirstEnabled() {
        val original = ControlSettings(gyro = shared)
        assertFalse(original.gyroActionsSplit)
        assertNull(original.portraitGyroAction)
        assertNull(original.landscapeGyroAction)
        for (rotation in 0..3) assertEquals(shared, original.effectiveGyroSettings(rotation))
        val enabled = original.withGyroActionsSplit(true)
        assertEquals(shared.actionSettings(), enabled.portraitGyroAction)
        assertEquals(shared.actionSettings(), enabled.landscapeGyroAction)
    }

    @Test fun editingAnOrientationLeavesTheOtherAndSharedSettingsUntouched() {
        val original = splitSettings()
        val editedPortrait = original.updateGyroAction(GyroOrientation.Portrait) {
            it.copy(directionMode = GyroDirectionMode.Twist, reverseX = true, reverseY = false)
        }
        assertEquals(original.landscapeGyroAction, editedPortrait.landscapeGyroAction)
        assertEquals(original.gyro, editedPortrait.gyro)
        assertEquals(GyroActionSettings(GyroDirectionMode.Twist, true, false), editedPortrait.portraitGyroAction)
        val editedLandscape = original.updateGyroAction(GyroOrientation.Landscape) { it.copy(reverseY = true) }
        assertEquals(original.portraitGyroAction, editedLandscape.portraitGyroAction)
        assertEquals(original.gyro, editedLandscape.gyro)
        assertEquals(landscape.copy(reverseY = true), editedLandscape.landscapeGyroAction)
    }

    @Test fun disablingRestoresSharedActionAndReenablingPreservesProfiles() {
        val original = splitSettings()
        val disabled = original.withGyroActionsSplit(false)
        for (rotation in 0..3) assertEquals(shared, disabled.effectiveGyroSettings(rotation))
        val sharedEdited = disabled.updateGyroAction(GyroOrientation.Landscape) {
            it.copy(directionMode = GyroDirectionMode.Tilt, reverseY = true)
        }
        assertEquals(shared.copy(directionMode = GyroDirectionMode.Tilt, reverseY = true), sharedEdited.gyro)
        val enabled = sharedEdited.withGyroActionsSplit(true)
        assertEquals(portrait, enabled.portraitGyroAction)
        assertEquals(landscape, enabled.landscapeGyroAction)
    }

    @Test fun bothPortraitRotationsAndBothLandscapeRotationsSelectTheirOwnProfile() {
        val settings = splitSettings()
        for (rotation in 0..3) {
            val expected = if (rotation % 2 == 0) portrait else landscape
            assertEquals(expected, settings.effectiveGyroSettings(rotation).actionSettings())
            assertEquals(settings.effectiveGyroSettings(rotation),
                UiState(settings = settings, gyroDisplayRotation = rotation).effectiveGyroSettings)
        }
    }

    @Test fun sensitivitySmoothingAndAccelerationRemainShared() {
        val acceleration = PointerAccelerationSettings(mode = PointerAccelerationMode.Speed,
            strength = PointerAccelerationStrength.Strong)
        val settings = splitSettings().copy(gyro = shared.copy(sensitivity = 4.5f,
            smoothing = false, acceleration = acceleration))
        for (rotation in 0..3) {
            val effective = settings.effectiveGyroSettings(rotation)
            assertEquals(4.5f, effective.sensitivity)
            assertFalse(effective.smoothing)
            assertEquals(acceleration, effective.acceleration)
        }
    }

    @Test fun manualFullscreenRotationWinsButHiddenFullscreenUsesDisplayRotation() {
        val settings = splitSettings().copy(fullscreenMode = true, fullscreenRotationMode = FullscreenRotationMode.Manual)
        for (angle in listOf(0, 90, 180, 270)) for (displayRotation in 0..3) {
            val rotated = settings.copy(fullscreenRotation = angle)
            val effective = rotated.effectiveGyroRotation(displayRotation)
            assertEquals(angle / 90, effective)
            assertEquals(if (angle % 180 == 0) portrait else landscape,
                rotated.effectiveGyroSettings(effective).actionSettings())
            assertEquals(displayRotation, rotated.effectiveGyroRotation(displayRotation, fullscreenVisible = false))
            assertEquals(displayRotation, rotated.copy(fullscreenRotationMode = FullscreenRotationMode.FollowSystem)
                .effectiveGyroRotation(displayRotation))
        }
    }

    @Test fun motionUsesEachOrientationsModeAndReversals() {
        val settings = splitSettings().copy(gyro = shared.copy(sensitivity = 1f, smoothing = false,
            acceleration = PointerAccelerationSettings(mode = PointerAccelerationMode.Off)))
        for (rotation in 0..3) {
            val effective = settings.effectiveGyroSettings(rotation)
            val gravity = when (rotation) {
                1 -> doubleArrayOf(-9.81, 0.0, 0.0)
                2 -> doubleArrayOf(0.0, -9.81, 0.0)
                3 -> doubleArrayOf(9.81, 0.0, 0.0)
                else -> doubleArrayOf(0.0, 9.81, 0.0)
            }
            val pitch = when (rotation) {
                1 -> doubleArrayOf(0.0, 1.0, 0.0)
                2 -> doubleArrayOf(-1.0, 0.0, 0.0)
                3 -> doubleArrayOf(0.0, -1.0, 0.0)
                else -> doubleArrayOf(1.0, 0.0, 0.0)
            }
            val controller = GyroMotionController().apply { gravity(gravity[0], gravity[1], gravity[2]) }
            controller.sample(1_000_000_000, 0.0, 0.0, 0.0, rotation, effective)
            val lean = controller.sample(1_010_000_000, 0.0, 0.0, 1.0, rotation, effective)
            assertEquals(if (rotation % 2 == 0) 0.0 else 11.88, lean.x, .00001)
            val heading = controller.sample(1_020_000_000,
                gravity[0] / 9.81, gravity[1] / 9.81, 0.0, rotation, effective)
            assertEquals(if (rotation % 2 == 0) -11.88 else 0.0, heading.x, .00001)
            val up = controller.sample(1_030_000_000, pitch[0], pitch[1], 0.0, rotation, effective)
            assertEquals(if (rotation % 2 == 0) 11.88 else -11.88, up.y, .00001)
        }
    }

    @Test fun leaningChangesGravityWithoutSwitchingTheLockedScreenProfile() {
        val state = UiState(settings = splitSettings(), gyroDisplayRotation = 1, gyroActive = true)
        val controller = GyroMotionController()
        for (posture in listOf(doubleArrayOf(-9.81, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 9.81),
            doubleArrayOf(-6.0, 6.0, 4.9))) {
            controller.gravity(posture[0], posture[1], posture[2])
            assertEquals(landscape, state.effectiveGyroSettings.actionSettings())
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

    private suspend fun withPreferencesFile(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("phone-mouse-gyro-action-test").toFile()
        try { block(File(directory, "controls.preferences_pb")) } finally { directory.deleteRecursively() }
    }

    @Test fun restartRestoresIndependentProfilesAndDisabledState() = runBlocking {
        withPreferencesFile { file ->
            val disabled = splitSettings().withGyroActionsSplit(false)
            withStore(file) { it.save(disabled) }
            withStore(file) { store ->
                val restored = store.settings.first()
                assertEquals(disabled, restored)
                store.save(restored.withGyroActionsSplit(true))
            }
            withStore(file) { store ->
                assertEquals(splitSettings(), store.settings.first())
            }
        }
    }

    @Test fun upgradingLegacyPreferencesAndFirstEnablingCopiesTheLatestSharedAction() = runBlocking {
        withPreferencesFile { file ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val preferences = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
                preferences.edit {
                    it[stringPreferencesKey("gyro_direction_mode")] = "twist"
                    it[booleanPreferencesKey("gyro_reverse_x")] = true
                    it[booleanPreferencesKey("gyro_reverse_y")] = true
                }
                val store = SettingsStore(preferences)
                val migrated = store.settings.first()
                assertFalse(migrated.gyroActionsSplit)
                assertNull(migrated.portraitGyroAction)
                assertNull(migrated.landscapeGyroAction)
                assertEquals(GyroActionSettings(GyroDirectionMode.Twist, true, true), migrated.gyro.actionSettings())
                val changed = migrated.updateGyroAction(GyroOrientation.Portrait) { it.copy(reverseX = false) }
                store.save(changed)
                val enabled = store.settings.first().withGyroActionsSplit(true)
                assertEquals(changed.gyro.actionSettings(), enabled.portraitGyroAction)
                assertEquals(changed.gyro.actionSettings(), enabled.landscapeGyroAction)
                store.save(enabled)
            } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
            withStore(file) { store ->
                val restored = store.settings.first()
                assertTrue(restored.gyroActionsSplit)
                assertEquals(GyroActionSettings(GyroDirectionMode.Twist, false, true), restored.portraitGyroAction)
                assertEquals(restored.portraitGyroAction, restored.landscapeGyroAction)
            }
        }
    }
}
