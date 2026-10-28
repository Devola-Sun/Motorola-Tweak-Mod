package com.motolauncher.mod.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockIndicatorPolicyTest {
    @Test fun legacyAndReactiveBottomIndicatorsAreRecognized() {
        assertTrue(LockIndicatorPolicy.isNativeIcon("lock_icon_view", "android.widget.FrameLayout"))
        assertTrue(LockIndicatorPolicy.isNativeIcon("device_entry_icon_view", "android.widget.FrameLayout"))
        assertTrue(LockIndicatorPolicy.isNativeIcon("", "com.android.systemui.keyguard.ui.view.DeviceEntryIconView"))
        assertFalse(LockIndicatorPolicy.isNativeIcon("start_button", "android.widget.ImageView"))
        assertFalse(LockIndicatorPolicy.isNativeIcon("end_button", "android.widget.ImageView"))
    }

    @Test fun glyphSitsAboveTheClock() {
        assertEquals(168f, LockIndicatorPolicy.top(200f, 30f, 22f, 10f), 0f)
    }

    @Test fun shortClockLayoutsClampInsteadOfDisablingTheGlyph() {
        assertEquals(30f, LockIndicatorPolicy.top(50f, 30f, 22f, 10f), 0f)
    }
}
