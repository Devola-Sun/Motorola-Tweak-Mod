package com.motolauncher.mod.hooks

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemUiGlassPaletteTest {
    @Test fun lightNotificationsReuseControlCenterMaterialOnBothHostsAndFallbacks() {
        for (backdrop in listOf(false, true)) for (keyguard in listOf(false, true)) {
            assertArrayEquals(SystemUiGlassPalette.controlCenterColors(backdrop),
                SystemUiGlassPalette.notificationColors(false, backdrop, keyguard))
        }
    }

    @Test fun controlCenterPaletteRemainsUnchanged() {
        assertArrayEquals(intArrayOf(0x4c1e2024, 0x66121418),
            SystemUiGlassPalette.controlCenterColors(true))
        assertArrayEquals(intArrayOf(0x9c1e2024.toInt(), 0xb8121418.toInt()),
            SystemUiGlassPalette.controlCenterColors(false))
    }

    @Test fun lightNotificationBodyIsNotAWhiteWash() {
        for (backdrop in listOf(false, true)) for (keyguard in listOf(false, true)) {
            for (color in SystemUiGlassPalette.notificationColors(false, backdrop, keyguard)) {
                assertTrue((color and 255) <= 36)
                assertTrue(((color ushr 8) and 255) <= 36)
                assertTrue(((color ushr 16) and 255) <= 36)
            }
        }
    }

    @Test fun isolateLightShadeAndBothLockThemesButKeepDarkShadeNative() {
        assertTrue(SystemUiGlassPalette.isolateNotificationDraw(false, false))
        assertTrue(SystemUiGlassPalette.isolateNotificationDraw(false, true))
        assertTrue(SystemUiGlassPalette.isolateNotificationDraw(true, true))
        assertEquals(false, SystemUiGlassPalette.isolateNotificationDraw(true, false))
    }

    @Test fun missingBlurKeepsTheSameStrongerFallbackAsControlCenter() {
        val glass = SystemUiGlassPalette.notificationColors(false, true, true)
        val fallback = SystemUiGlassPalette.notificationColors(false, false, true)
        glass.indices.forEach { assertTrue(fallback[it] ushr 24 > glass[it] ushr 24) }
    }

    @Test fun lightContentIsWhiteOnDarkGlassIncludingCompactAndPlainPlayback() {
        for (night in listOf(false, true)) for (liquid in listOf(false, true)) {
            assertEquals(0xffffffff.toInt(), SystemUiGlassPalette.primaryContentColor(night, liquid))
            assertEquals(0xccffffff.toInt(), SystemUiGlassPalette.secondaryContentColor(night, liquid))
            assertEquals(0x55ffffff, SystemUiGlassPalette.progressTrackColor(night, liquid))
        }
    }

    @Test fun darkNotificationMaterialRemainsUnchanged() {
        assertArrayEquals(intArrayOf(48, 68), alphas(true, true))
        assertArrayEquals(intArrayOf(116, 144), alphas(false, true))
        assertArrayEquals(intArrayOf(76, 102), alphas(true, false))
        assertArrayEquals(intArrayOf(156, 184), alphas(false, false))
    }

    private fun alphas(backdrop: Boolean, keyguard: Boolean) =
        SystemUiGlassPalette.notificationColors(true, backdrop, keyguard).map { it ushr 24 }.toIntArray()

    @Test fun nativeOffFillDoesNotObscureGlassAndOnAccentRemainsLegible() {
        assertTrue(SystemUiGlassPalette.nativeControlAlpha(false) < 64)
        assertTrue(SystemUiGlassPalette.nativeControlAlpha(true) >= 230)
        assertTrue(SystemUiGlassPalette.controlBodyAlpha(true) < 64)
    }
}
