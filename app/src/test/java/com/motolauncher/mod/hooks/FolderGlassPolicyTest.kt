package com.motolauncher.mod.hooks

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderGlassPolicyTest {
    @Test fun tallNativeBoundsCannotProduceTallGlass() {
        // The reported screenshot has a roughly 790 x 1000 content background.
        assertEquals(790, FolderGlassPolicy.containerSide(790, 1000))
        assertEquals(790, FolderGlassPolicy.containerSide(790, 790))
        assertEquals(600, FolderGlassPolicy.containerSide(790, 600))
        assertEquals(0, FolderGlassPolicy.containerSide(0, 1000))
        assertEquals(0, FolderGlassPolicy.containerSide(790, -1))
    }

    @Test fun squareContainerFitsAllFourRows() {
        assertEquals(100, FolderGlassPolicy.squareCellHeight(400, 0, 4, 0))
        assertEquals(90, FolderGlassPolicy.squareCellHeight(400, 16, 4, 8))
        for (side in listOf(320, 401, 720, 1080)) {
            val cell = FolderGlassPolicy.squareCellHeight(side, 16, 4, 8)
            org.junit.Assert.assertTrue(cell * 4 + 16 + 8 * 3 <= side)
        }
    }

    @Test fun squareSizingHandlesDifferentRowCountsAndEmptyGrids() {
        assertEquals(120, FolderGlassPolicy.squareCellHeight(360, 0, 3, 0))
        assertEquals(60, FolderGlassPolicy.squareCellHeight(360, 0, 6, 0))
        assertEquals(0, FolderGlassPolicy.squareCellHeight(0, 0, 4, 0))
        assertEquals(0, FolderGlassPolicy.squareCellHeight(400, 0, 0, 0))
        assertEquals(0, FolderGlassPolicy.squareCellHeight(10, 20, 4, 8))
    }

    @Test fun defaultRestoresNativeOpeningBlur() {
        for (frame in 0..100) {
            val progress = frame / 100f
            assertEquals(progress, FolderGlassPolicy.blurProgress(progress, 100), 0f)
        }
    }

    @Test fun sharedSliderScalesAnimationAndKeepsClosingCleanup() {
        assertEquals(0.25f, FolderGlassPolicy.blurProgress(0.5f, 50), 0f)
        assertEquals(0f, FolderGlassPolicy.blurProgress(1f, 0), 0f)
        for (percent in 0..100) {
            assertEquals(0f, FolderGlassPolicy.blurProgress(0f, percent), 0f)
            assertEquals(ControlCenterBlurPolicy.radius(100, percent) / 100f,
                FolderGlassPolicy.blurProgress(1f, percent), 0.00001f)
        }
        assertEquals(0f, FolderGlassPolicy.blurProgress(1f, -10), 0f)
        assertEquals(1f, FolderGlassPolicy.blurProgress(1f, 120), 0f)
    }

    @Test fun transparentFullscreenStockBackgroundCannotHideContainer() {
        assertEquals(255, FolderGlassPolicy.containerOpacity(true, 0, 0, 0))
        assertEquals(255, FolderGlassPolicy.containerOpacity(true, 0, 160, 160))
        assertEquals(255, FolderGlassPolicy.containerOpacity(true, 255, 0, 160))
    }

    @Test fun ordinaryFoldersRetainNativeFades() {
        assertEquals(255, FolderGlassPolicy.containerOpacity(false, 255, 160, 160))
        assertEquals(128, FolderGlassPolicy.containerOpacity(false, 128, 160, 160))
        assertEquals(0, FolderGlassPolicy.containerOpacity(false, 0, 160, 160))
    }
}
