package com.motolauncher.mod.hooks

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationGlassClipTest {
    @Test fun unclippedBackgroundKeepsFullHeight() {
        assertEquals(0, NotificationGlassClip.top(0, 120))
        assertEquals(120, NotificationGlassClip.bottom(0, 120, 0))
    }

    @Test fun nativeGroupClipAmountsArePreserved() {
        val top = NotificationGlassClip.top(12, 120)
        assertEquals(12, top)
        assertEquals(96, NotificationGlassClip.bottom(24, 120, top))
    }

    @Test fun fullyDismissedCardHasEmptyRatherThanInvertedClip() {
        val top = NotificationGlassClip.top(200, 120)
        assertEquals(120, top)
        assertEquals(top, NotificationGlassClip.bottom(200, 120, top))
    }

    @Test fun negativeTransitionValuesAreSafe() {
        assertEquals(0, NotificationGlassClip.top(-10, 120))
        assertEquals(120, NotificationGlassClip.bottom(-10, 120, 0))
        assertEquals(0, NotificationGlassClip.top(10, -1))
        assertEquals(0, NotificationGlassClip.bottom(10, -1, 0))
    }
}
