package com.motolauncher.mod.hooks

import com.motolauncher.mod.HookConfig.BatteryIconStyle
import com.motolauncher.mod.HookConfig.BatteryPercentStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryDisplayPolicyTest {
    @Test fun inIconWorksBeforeMeasurementAndInSmallNativeSlots() {
        // There is deliberately no size dependency: zero pre-layout dimensions
        // and narrow stock battery slots must not switch this mode to external text.
        for (icon in listOf(BatteryIconStyle.SYSTEM, BatteryIconStyle.CIRCLE, BatteryIconStyle.PILL)) {
            val policy = BatteryDisplayPolicy.resolve(icon, BatteryPercentStyle.IN_ICON)
            assertTrue(policy.showPercentInside)
            assertEquals(false, policy.showExternalPercent)
            assertEquals(if (icon == BatteryIconStyle.SYSTEM) BatteryIconStyle.PILL else icon, policy.iconStyle)
        }
    }

    @Test fun hiddenIconFallsBackToExternalPercentage() {
        val policy = BatteryDisplayPolicy.resolve(BatteryIconStyle.HIDDEN, BatteryPercentStyle.IN_ICON)
        assertEquals(BatteryIconStyle.HIDDEN, policy.iconStyle)
        assertFalse(policy.showPercentInside)
        assertEquals(true, policy.showExternalPercent)
    }

    @Test fun systemPercentageLeavesNativeVisibilityUntouched() {
        for (icon in BatteryIconStyle.entries) {
            val policy = BatteryDisplayPolicy.resolve(icon, BatteryPercentStyle.SYSTEM)
            assertEquals(icon, policy.iconStyle)
            assertNull(policy.showExternalPercent)
            assertFalse(policy.showPercentInside)
        }
    }

    @Test fun explicitExternalAndHiddenModesKeepTheSelectedIcon() {
        for (icon in BatteryIconStyle.entries) {
            for (percent in listOf(BatteryPercentStyle.ALWAYS, BatteryPercentStyle.HIDDEN)) {
                val policy = BatteryDisplayPolicy.resolve(icon, percent)
                assertEquals(icon, policy.iconStyle)
                assertFalse(policy.showPercentInside)
                assertEquals(percent == BatteryPercentStyle.ALWAYS, policy.showExternalPercent)
            }
        }
    }

    @Test fun defaultInIconUsesBatteryShapedWideViewport() {
        val policy = BatteryDisplayPolicy.resolve(BatteryIconStyle.SYSTEM, BatteryPercentStyle.IN_ICON)
        assertEquals(BatteryIconStyle.PILL, policy.iconStyle)
        assertEquals(BatteryIconSize(26, 16), policy.customSize)
        val size = requireNotNull(policy.customSize)
        assertTrue(size.widthDp > size.heightDp)
    }

    @Test fun circleAlwaysUsesReadableSquareViewport() {
        for (percent in BatteryPercentStyle.entries) {
            val policy = BatteryDisplayPolicy.resolve(BatteryIconStyle.CIRCLE, percent)
            assertEquals(BatteryIconStyle.CIRCLE, policy.iconStyle)
            assertEquals(BatteryIconSize(18, 18), policy.customSize)
        }
    }

    @Test fun stockAndHiddenIconsNeverOverrideNativeDimensions() {
        assertNull(BatteryDisplayPolicy.resolve(BatteryIconStyle.SYSTEM, BatteryPercentStyle.SYSTEM).customSize)
        for (percent in BatteryPercentStyle.entries) {
            assertNull(BatteryDisplayPolicy.resolve(BatteryIconStyle.HIDDEN, percent).customSize)
        }
    }
}
