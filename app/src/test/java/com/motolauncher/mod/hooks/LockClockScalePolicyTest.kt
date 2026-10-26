package com.motolauncher.mod.hooks

import com.motolauncher.mod.HookConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockClockScalePolicyTest {
    @Test fun sliderBoundsAndNativeDefault() {
        assertEquals(100, HookConfig.DEFAULT_LOCK_CLOCK_SIZE_PERCENT)
        assertEquals(100, HookConfig.coerceLockClockSize(-1))
        assertEquals(150, HookConfig.coerceLockClockSize(150))
        assertEquals(200, HookConfig.coerceLockClockSize(999))
        assertEquals(1f, LockClockScalePolicy.factor(100, true, false, true), 0f)
        assertEquals(1.5f, LockClockScalePolicy.factor(150, true, false, true), 0f)
        assertEquals(2f, LockClockScalePolicy.factor(200, true, false, true), 0f)
    }

    @Test fun aodUnlockedAndNonInteractiveStayNative() {
        assertEquals(1f, LockClockScalePolicy.factor(200, true, true, true), 0f)
        assertEquals(1f, LockClockScalePolicy.factor(200, false, false, true), 0f)
        assertEquals(1f, LockClockScalePolicy.factor(200, true, false, false), 0f)
    }

    @Test fun fallbackTargetsOnlyClockFaces() {
        assertTrue(LockClockScalePolicy.isFace("lockscreen_clock_view_large", "android.widget.FrameLayout"))
        assertTrue(LockClockScalePolicy.isFace("lockscreen_clock_view", "android.widget.FrameLayout"))
        assertTrue(LockClockScalePolicy.isFace("", "com.android.systemui.shared.clocks.view.FlexClockView"))
        assertTrue(LockClockScalePolicy.isFace("", "com.android.keyguard.AnimatableClockView"))
        listOf("keyguard_clock_switch", "keyguard_clock_container", "smartspace", "date",
            "notification_stack_scroller", "status_bar_clock").forEach {
            assertFalse(LockClockScalePolicy.isFace(it, "android.widget.FrameLayout"))
        }
        assertFalse(LockClockScalePolicy.isFace("clock", "com.android.systemui.statusbar.policy.Clock"))
    }
}
