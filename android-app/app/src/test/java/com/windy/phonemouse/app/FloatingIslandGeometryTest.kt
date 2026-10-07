package com.windy.phonemouse.app

import org.junit.Assert.*
import org.junit.Test

class FloatingIslandGeometryTest {
    private fun geometry(panelWidth: Float = 240f, panelHeight: Float = 156f) =
        FloatingIslandGeometry(400f, 800f, panelWidth, panelHeight, 12f, 24f)

    @Test fun edgePositionsStayInsideViewport() {
        assertEquals(IslandOffset(12f, 12f), geometry().offset(IslandPosition(0f, 0f)))
        assertEquals(IslandOffset(148f, 632f), geometry().offset(IslandPosition(1f, 1f)))
        assertEquals(IslandOffset(12f, 632f), geometry().clamp(IslandOffset(-100f, 1000f)))
    }

    @Test fun collapsingPreservesVerticalCentreAndChosenEdge() {
        val saved = IslandPosition(1f, .6f)
        val full = geometry().offset(saved)
        val bubble = geometry(56f, 56f).offset(saved)
        assertEquals(full.y + 78f, bubble.y + 28f, .001f)
        assertEquals(388f, full.x + 240f, .001f)
        assertEquals(388f, bubble.x + 56f, .001f)
    }

    @Test fun freePositionRoundTripsWithoutSnapping() {
        val saved = IslandPosition(.4f, .55f)
        val actual = geometry().position(geometry().offset(saved), snap = false)
        assertEquals(saved.x, actual.x, .001f); assertEquals(saved.y, actual.y, .001f)
    }

    @Test fun releaseSnapsOnlyNearHorizontalEdges() {
        assertEquals(0f, geometry().position(IslandOffset(36f, 300f)).x, .001f)
        assertEquals(1f, geometry().position(IslandOffset(124f, 300f)).x, .001f)
        assertEquals(.5f, geometry().position(IslandOffset(80f, 300f)).x, .001f)
    }

    @Test fun smallViewportDoesNotProduceInvertedBounds() {
        val small = FloatingIslandGeometry(200f, 100f, 240f, 156f, 12f, 24f)
        assertEquals(IslandOffset(0f, 0f), small.offset(IslandPosition()))
        assertTrue(small.position(IslandOffset(-100f, 1000f)).y.isFinite())
    }

    @Test fun invalidStoredPositionFallsBackOrClamps() {
        assertEquals(IslandPosition(), IslandPosition(Float.NaN, Float.POSITIVE_INFINITY).sanitized())
        assertEquals(IslandPosition(0f, 1f), IslandPosition(-2f, 3f).sanitized())
        val settings = ControlSettings(portraitIsland = IslandPosition(Float.NaN, -.3f), landscapeIsland = IslandPosition(.2f, .3f)).sanitized()
        assertEquals(IslandPosition(1f, 0f), settings.portraitIsland)
        assertEquals(IslandPosition(.2f, .3f), settings.landscapeIsland)
    }
}
