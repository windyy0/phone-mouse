package com.windy.phonemouse.app

import org.junit.Assert.*
import org.junit.Test

class SplitControlsGeometryTest {
    private val sizes = FloatingControl.entries.associateWith { when (it) {
        FloatingControl.Toolbar -> FloatingSize(252f, 52f)
        FloatingControl.Wheel -> FloatingSize(56f, 160f)
        FloatingControl.Keyboard, FloatingControl.Gyroscope -> FloatingSize(52f, 52f)
        else -> FloatingSize(72f, 52f)
    } }
    private fun geometry(width: Float = 360f, height: Float = 720f) = SplitControlsGeometry(width, height, sizes, 8f, 24f, 8f)
    private fun assertSeparated(g: SplitControlsGeometry, offsets: Map<FloatingControl, IslandOffset>) {
        offsets.forEach { (id, point) -> offsets.filterKeys { it != id }.forEach { (other, p) ->
            assertFalse("$id overlapped $other", g.overlaps(id, point, other, p))
        } }
    }
    @Test fun defaultsLeaveMiddleEmptyAndKeysAtBottom() {
        val g = geometry(); val offsets = g.resolve(SplitControlLayout())
        assertSeparated(g, offsets)
        assertEquals(660f, offsets.getValue(FloatingControl.Left).y, .01f)
        assertEquals(660f, offsets.getValue(FloatingControl.Middle).y, .01f)
        assertEquals(660f, offsets.getValue(FloatingControl.Right).y, .01f)
        assertTrue(offsets.getValue(FloatingControl.Wheel).y < 500f)
    }

    @Test fun defaultsAreTheSharedResetLayout() {
        assertEquals(IslandPosition(1f, .72f), SplitControlLayout.defaults().toolbar)
        assertEquals(IslandPosition(0f, 1f), SplitControlLayout.defaults().left)
        assertEquals(IslandPosition(.5f, 1f), SplitControlLayout.defaults().middle)
        assertEquals(IslandPosition(1f, 1f), SplitControlLayout.defaults().right)
        assertEquals(IslandPosition(1f, .5f), SplitControlLayout.defaults().wheel)
        assertEquals(IslandPosition(0f, .5f), SplitControlLayout.defaults().keyboard)
        assertEquals(IslandPosition(0f, .3f), SplitControlLayout.defaults().gyroscope)
        assertEquals(SplitControlLayout.defaults(), SplitControlLayout.decode(SplitControlLayout.defaults().encode()))
    }
    @Test fun landscapeRestoresWithoutOverlap() {
        val g = geometry(800f, 320f); assertSeparated(g, g.resolve(SplitControlLayout()))
    }
    @Test fun legacyPositionsArePreservedWhenKeyboardIsAdded() {
        val legacy = "0.3,0.4,0.1,0.9,0.5,0.8,0.9,0.7,0.8,0.2"
        val migrated = SplitControlLayout.decode(legacy)!!
        assertEquals(legacy, migrated.encode().split(',').take(10).joinToString(","))
        assertEquals(SplitControlLayout.defaults().keyboard, migrated.keyboard)
        val moved = migrated.copy(keyboard = IslandPosition(.25f, .6f))
        assertEquals(moved, SplitControlLayout.decode(moved.encode()))
    }
    @Test fun collidingStoredPositionsAreRepairedAndStayStable() {
        val g = geometry(); val shared = IslandPosition(.5f, .5f)
        val offsets = g.resolve(SplitControlLayout(shared, shared, shared, shared, shared))
        assertSeparated(g, offsets)
        val restored = g.resolve(SplitControlLayout.decode(g.layout(offsets).encode())!!)
        offsets.forEach { (id, point) ->
            assertEquals(point.x, restored.getValue(id).x, .01f); assertEquals(point.y, restored.getValue(id).y, .01f)
        }
    }
    @Test fun draggingOntoAnotherControlFindsFreeSpace() {
        val g = geometry(); val offsets = g.resolve(SplitControlLayout())
        val placed = g.place(FloatingControl.Left, offsets.getValue(FloatingControl.Right), offsets.filterKeys { it != FloatingControl.Left }, offsets.getValue(FloatingControl.Left))
        assertSeparated(g, offsets + (FloatingControl.Left to placed))
    }
    @Test fun noSpaceFallsBackAndRemainsFinite() {
        val g = geometry(60f, 80f); val fallback = IslandOffset(0f, 0f)
        val placed = g.place(FloatingControl.Left, IslandOffset(500f, 500f), mapOf(FloatingControl.Toolbar to fallback), fallback)
        assertEquals(g.geometry(FloatingControl.Left).clamp(fallback), placed)
    }
    @Test fun missingOrInvalidPositionsUseFirstSplitDefaults() {
        assertNull(SplitControlLayout.decode(null)); assertNull(SplitControlLayout.decode("1,2"))
        assertNull(SplitControlLayout.decode("NaN,1,1,1,1,1,1,1,1,1"))
        assertNull(SplitControlLayout.decode("bad,1,1,1,1,1,1,1,1,1"))
        assertFalse(ControlSettings().controlsSplit); assertNull(ControlSettings().portraitControls)
        assertFalse(ControlSettings().portraitToolbarCollapsed); assertFalse(ControlSettings().landscapeToolbarCollapsed)
    }
    @Test fun savedLayoutIsClampedWithoutMixingOrientations() {
        val portrait = SplitControlLayout(left = IslandPosition(-1f, 2f))
        val landscape = SplitControlLayout(wheel = IslandPosition(.3f, .4f))
        val s = ControlSettings(controlsSplit = true, portraitControls = portrait, landscapeControls = landscape).sanitized()
        assertEquals(IslandPosition(0f, 1f), s.portraitControls!!.left)
        assertEquals(landscape, s.landscapeControls)
    }

    @Test fun toolbarCollapseStateIsIndependentPerOrientation() {
        val s = ControlSettings(portraitToolbarCollapsed = true, landscapeToolbarCollapsed = false)
        assertTrue(s.portraitToolbarCollapsed)
        assertFalse(s.landscapeToolbarCollapsed)
        val changed = s.copy(landscapeToolbarCollapsed = true)
        assertTrue(changed.portraitToolbarCollapsed)
        assertTrue(changed.landscapeToolbarCollapsed)
    }

    @Test fun toolbarBubbleUsesSafeBoundsAndKeepsSavedPosition() {
        val geometry = FloatingIslandGeometry(360f, 720f, 56f, 56f, 8f, 24f)
        val dragged = geometry.clamp(IslandOffset(500f, -20f))
        assertEquals(296f, dragged.x, .01f)
        assertEquals(8f, dragged.y, .01f)
        val saved = SplitControlLayout.defaults().copy(toolbar = geometry.position(dragged))
        assertEquals(dragged, geometry.offset(saved.toolbar))
    }

    @Test fun sixControlLayoutsKeepAllPositionsAndAppendGyroAtItsDefault() {
        val previous = "0.3,0.4,0.1,0.9,0.5,0.8,0.9,0.7,0.8,0.2,0.25,0.6"
        val migrated = SplitControlLayout.decode(previous)!!
        assertEquals(previous, migrated.encode().split(',').take(12).joinToString(","))
        assertEquals(SplitControlLayout.defaults().gyroscope, migrated.gyroscope)
        val moved = migrated.copy(gyroscope = IslandPosition(.65f, .2f))
        assertEquals(moved, SplitControlLayout.decode(moved.encode()))
    }

    @Test fun gyroCanBeMovedWithoutMovingOtherControlsAndKeepsOrientationProfiles() {
        val g = geometry()
        val before = g.resolve(SplitControlLayout.defaults())
        val placed = g.place(FloatingControl.Gyroscope, IslandOffset(250f, 200f), before.filterKeys { it != FloatingControl.Gyroscope })
        val moved = g.layout(before + (FloatingControl.Gyroscope to placed))
        val restored = g.resolve(SplitControlLayout.decode(moved.encode())!!)
        assertSeparated(g, restored)
        before.filterKeys { it != FloatingControl.Gyroscope }.forEach { (id, point) ->
            assertEquals(point.x, restored.getValue(id).x, .01f)
            assertEquals(point.y, restored.getValue(id).y, .01f)
        }
        val settings = ControlSettings(portraitControls = moved, landscapeControls = SplitControlLayout.defaults()).sanitized()
        assertNotEquals(settings.portraitControls!!.gyroscope, settings.landscapeControls!!.gyroscope)
    }
}
