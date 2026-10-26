package com.motolauncher.mod.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockClockGlassScopeTest {
    @Test fun motoClockTagAndAospAnimatedDigitsAreSupported() {
        assertTrue(LockClockGlassScope.isClockText("clock_face_time", "android.widget.TextView"))
        assertTrue(LockClockGlassScope.isClockText(null, "com.android.keyguard.AnimatableClockView"))
        assertTrue(LockClockGlassScope.isClockText(null,
            "com.android.systemui.shared.clocks.AnimatableClockView"))
    }

    @Test fun datesWidgetsAndStatusBarClocksAreExcluded() {
        assertFalse(LockClockGlassScope.isClockText("clock_face_date", "android.widget.TextView"))
        assertFalse(LockClockGlassScope.isClockText(null, "com.android.systemui.statusbar.policy.Clock"))
        assertFalse(LockClockGlassScope.isClockText(null, "android.widget.TextClock"))
        assertFalse(LockClockGlassScope.isClockText("battery", "android.widget.TextView"))
    }

    @Test fun appliesOnlyToInteractiveHardwareRenderedKeyguard() {
        assertTrue(LockClockGlassScope.shouldApply(true, true, false, true, true))
        assertFalse(LockClockGlassScope.shouldApply(false, true, false, true, true))
        assertFalse(LockClockGlassScope.shouldApply(true, false, false, true, true))
        assertFalse(LockClockGlassScope.shouldApply(true, true, true, true, true))
        assertFalse(LockClockGlassScope.shouldApply(true, true, false, false, true))
        assertFalse(LockClockGlassScope.shouldApply(true, true, false, true, false))
    }

    @Test fun reactiveClockDigitsAreRecognizedOnlyInsideClockFaces() {
        val clazz = "com.android.systemui.shared.clocks.view.SimpleDigitalClockTextView"
        assertTrue(LockClockGlassScope.isClockText(null, clazz, insideFace = true))
        assertFalse(LockClockGlassScope.isClockText(null, clazz))
        assertTrue(LockClockGlassScope.isClockText(null, "android.widget.TextView",
            insideFace = true, text = "１２:３４"))
        assertFalse(LockClockGlassScope.isClockText(null, "android.widget.TextView", text = "12:34"))
    }

    @Test fun extrasAreNotTreatedAsClockDigitsEvenInsideClockHost() {
        listOf("date", "battery_percentage", "weather", "alarm", "smartspace", "status_bar_clock").forEach { id ->
            assertFalse(LockClockGlassScope.isClockText(null, "android.widget.TextView", id, true, "26"))
        }
        assertFalse(LockClockGlassScope.isClockText(null, "android.widget.TextView", "", true, "10/26"))
    }

    @Test fun statusBarAndNotificationHostsAreExcluded() {
        assertTrue(LockClockGlassScope.isClockHost("keyguard_clock_container", "android.widget.FrameLayout"))
        assertTrue(LockClockGlassScope.isClockHost("", "com.android.systemui.shared.clocks.view.FlexClockView"))
        assertFalse(LockClockGlassScope.isClockHost("clock", "com.android.systemui.statusbar.policy.Clock"))
        assertFalse(LockClockGlassScope.isClockHost("notification_stack_scroller", "android.widget.FrameLayout"))
    }
}
