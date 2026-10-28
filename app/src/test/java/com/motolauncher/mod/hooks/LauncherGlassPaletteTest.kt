package com.motolauncher.mod.hooks

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherGlassPaletteTest {
    @Test fun bodyKeepsCoverDockOpacity() {
        assertArrayEquals(intArrayOf(100, 62), LauncherGlassPalette.bodyColors(false).map { it ushr 24 }.toIntArray())
        assertArrayEquals(intArrayOf(82, 112), LauncherGlassPalette.bodyColors(true).map { it ushr 24 }.toIntArray())
    }

    @Test fun stockFolderTintDoesNotMultiplyGlassOpacity() {
        assertEquals(255, LauncherGlassPalette.folderOpacity(255, 160, 160))
        assertEquals(255, LauncherGlassPalette.folderOpacity(255, 255, 255))
    }

    @Test fun nativeFolderFadesAndTransparentFullscreenLayerArePreserved() {
        assertEquals(128, LauncherGlassPalette.folderOpacity(128, 160, 160))
        assertEquals(127, LauncherGlassPalette.folderOpacity(255, 80, 160))
        assertEquals(0, LauncherGlassPalette.folderOpacity(255, 0, 160))
        assertEquals(0, LauncherGlassPalette.folderOpacity(0, 160, 160))
        assertEquals(0, LauncherGlassPalette.folderOpacity(255, 0, 0))
        assertEquals(255, LauncherGlassPalette.folderOpacity(255, 255, 160))
    }
}
