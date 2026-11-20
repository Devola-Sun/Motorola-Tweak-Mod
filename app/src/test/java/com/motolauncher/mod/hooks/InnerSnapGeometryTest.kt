package com.motolauncher.mod.hooks

import com.motolauncher.mod.hooks.InnerSnapGeometry.Box
import com.motolauncher.mod.hooks.InnerSnapGeometry.Axis
import org.junit.Assert.*
import org.junit.Test

class InnerSnapGeometryTest {
    private val safe = Box(20, 40, 980, 760)
    private val window = Box(300, 200, 700, 500)

    @Test fun snapsAllThreePositionsWithoutResizingOrMovingY() {
        listOf(20, 300, 580).forEachIndexed { position, x ->
            val result = InnerSnapGeometry.snap(window, safe, position)!!
            assertEquals(x, result.left)
            assertEquals(window.width, result.width)
            assertEquals(window.height, result.height)
            assertEquals(window.top, result.top)
        }
    }

    @Test fun clampsYOnlyWhenOutsideSafeArea() {
        assertEquals(40, InnerSnapGeometry.snap(Box(200, -10, 600, 290), safe, 0)!!.top)
        assertEquals(460, InnerSnapGeometry.snap(Box(200, 600, 600, 900), safe, 2)!!.top)
    }

    @Test fun refusesOversizeAndEmptyWindowsRatherThanResizing() {
        assertNull(InnerSnapGeometry.snap(Box(0, 0, 2000, 100), safe, 0))
        assertNull(InnerSnapGeometry.snap(Box(0, 0, 100, 1000), safe, 1))
        assertNull(InnerSnapGeometry.snap(Box(0, 0, 0, 100), safe, 2))
    }

    @Test fun buttonIsContainedEntirelyInMargin() {
        assertNull(InnerSnapGeometry.buttonX(0, 47, 48))
        assertEquals(0, InnerSnapGeometry.buttonX(0, 48, 48))
        assertEquals(36, InnerSnapGeometry.buttonX(20, 100, 48))
        assertNull(InnerSnapGeometry.buttonX(100, 20, 48))
    }

    @Test fun recognizesBothEdgesAndCenterWithInsets() {
        assertEquals(0, InnerSnapGeometry.position(Box(22, 100, 422, 400), safe, 4))
        assertEquals(1, InnerSnapGeometry.position(window, safe, 4))
        assertEquals(2, InnerSnapGeometry.position(Box(578, 100, 978, 400), safe, 4))
    }

    @Test fun settingsEmbeddedPaneIsNotAFullscreenCompatWindow() {
        assertFalse(InnerSnapGeometry.supportedActivity(1, 6, true))
        assertFalse(InnerSnapGeometry.supportedActivity(1, 6, false))
        assertFalse(InnerSnapGeometry.supportedActivity(1, 1, true))
        assertFalse(InnerSnapGeometry.supportedActivity(6, 6, false))
        assertTrue(InnerSnapGeometry.supportedActivity(1, 1, false))
        assertTrue(InnerSnapGeometry.supportedActivity(5, 5, false))
    }

    @Test fun landscapeGameWithFullWidthUsesVerticalAxis() {
        val displaySafe = Box(0, 152, 2484, 2148)
        val game = Box(0, 432, 2484, 1800)
        assertEquals(Axis.VERTICAL, InnerSnapGeometry.axis(game, displaySafe, 126, false))
        assertNull(InnerSnapGeometry.axis(game, displaySafe, 126, true))
        val placements = InnerSnapGeometry.controls(game, displaySafe, 126, Axis.VERTICAL)
        assertEquals(2, placements.size)
        placements.forEach { (direction, box) ->
            assertEquals(252, box.width)
            assertEquals(126, box.height)
            assertTrue(box.left >= displaySafe.left && box.right <= displaySafe.right)
            assertTrue(box.top >= displaySafe.top && box.bottom <= displaySafe.bottom)
            if (direction == 0) assertTrue(box.bottom <= game.top)
            else assertTrue(box.top >= game.bottom)
        }
    }

    @Test fun verticalAlignmentPreservesWidthAndUsesSafeInsets() {
        val game = Box(0, 432, 2484, 1800)
        val safe = Box(0, 152, 2484, 2148)
        assertEquals(152, InnerSnapGeometry.alignedStart(game, safe, 0, Axis.VERTICAL))
        assertEquals(466, InnerSnapGeometry.alignedStart(game, safe, 1, Axis.VERTICAL))
        assertEquals(780, InnerSnapGeometry.alignedStart(game, safe, 2, Axis.VERTICAL))
        assertEquals(0, InnerSnapGeometry.position(Box(0, 152, 2484, 152 + game.height), safe, 4, Axis.VERTICAL))
        assertEquals(2, InnerSnapGeometry.position(Box(0, 780, 2484, 2148), safe, 4, Axis.VERTICAL))
    }

    @Test fun completeFullscreenBoundsDoNotTreatSystemBarsAsMargins() {
        val full = Box(0, 0, 2484, 2232)
        assertNull(InnerSnapGeometry.axis(full, Box(0, 152, 2484, 2148), 126, false))
    }

    @Test fun portraitCompatAndFreeformKeepHorizontalBehavior() {
        val app = Box(584, 0, 1648, 2484)
        val safe = Box(0, 123, 2232, 2400)
        assertEquals(Axis.HORIZONTAL, InnerSnapGeometry.axis(app, safe, 126, false))
        assertEquals(0, InnerSnapGeometry.alignedStart(app, safe, 0, Axis.HORIZONTAL))
        assertEquals(1168, InnerSnapGeometry.alignedStart(app, safe, 2, Axis.HORIZONTAL))
        assertEquals(Axis.HORIZONTAL, InnerSnapGeometry.axis(window, this.safe, 48, true))
    }

    @Test fun controlsAtEdgesExposeCenterAndOppositeDirectionInsideSafeArea() {
        val atLeft = Box(20, 200, 420, 500)
        val controls = InnerSnapGeometry.controls(atLeft, safe, 48, Axis.HORIZONTAL)
        assertEquals(listOf(2), controls.map { it.first })
        val group = controls.single().second
        assertEquals(48, group.width)
        assertEquals(96, group.height)
        assertTrue(group.left >= atLeft.right && group.right <= safe.right)
    }

    @Test fun verticalMarginNeedsFullButtonAndEnoughWidthForTwoControls() {
        assertNull(InnerSnapGeometry.axis(Box(0, 47, 500, 500), Box(0, 0, 500, 500), 48, false))
        assertEquals(Axis.VERTICAL,
            InnerSnapGeometry.axis(Box(0, 48, 500, 500), Box(0, 0, 500, 500), 48, false))
        assertTrue(InnerSnapGeometry.controls(Box(0, 100, 80, 400), Box(0, 0, 80, 500), 48, Axis.VERTICAL).isEmpty())
    }
}
