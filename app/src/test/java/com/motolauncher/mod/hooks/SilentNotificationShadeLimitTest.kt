package com.motolauncher.mod.hooks

import org.junit.Assert.assertEquals
import org.junit.Test

class SilentNotificationShadeLimitTest {
    @Test fun openShadeReleasesOneCardCap() {
        val policy = SilentNotificationShadeLimit()
        assertEquals(-1, policy.update(1, true, 0, true, false))
        assertEquals(-1, policy.update(-1, true, 0, true, false))
        assertEquals(1, policy.update(-1, false, 0, true, false))
    }

    @Test fun keyguardDozeAndClosedShadeKeepStockLimits() {
        for (state in listOf(1, 2)) {
            assertEquals(1, SilentNotificationShadeLimit().update(1, true, state, true, false))
        }
        assertEquals(1, SilentNotificationShadeLimit().update(1, true, 0, true, true))
        assertEquals(1, SilentNotificationShadeLimit().update(1, true, 0, false, false))
        assertEquals(1, SilentNotificationShadeLimit().update(1, false, 0, true, false))
    }

    @Test fun freshNativeLimitWinsWhenLeavingShade() {
        val policy = SilentNotificationShadeLimit()
        assertEquals(-1, policy.update(1, true, 0, true, false))
        assertEquals(3, policy.update(3, true, 1, false, false))
        assertEquals(-1, policy.update(-1, false, 0, false, false))
    }

    @Test fun nativeUnlimitedShadeRemainsUnlimitedOnDisable() {
        val policy = SilentNotificationShadeLimit()
        assertEquals(-1, policy.update(-1, true, 0, true, false))
        assertEquals(-1, policy.update(-1, false, 0, true, false))
    }
}
