package com.motolauncher.mod.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCenterBlurPolicyTest {
    @Test fun standardStrengthPreservesNativeAnimation() {
        for (radius in 0..100) {
            assertEquals(radius, ControlCenterBlurPolicy.radius(radius, 100))
        }
    }

    @Test fun sameRatioAppliesThroughoutNativeExpansion() {
        for (radius in 0..100) {
            assertEquals(radius, ControlCenterBlurPolicy.radius(radius * 2, 50))
        }
    }

    @Test fun sliderScalesOriginalRadiusAndNeverCreatesBlurWhenNativeIsZero() {
        assertEquals(40, ControlCenterBlurPolicy.radius(80, 50))
        assertEquals(0, ControlCenterBlurPolicy.radius(80, 0))
        for (percent in 0..100) assertEquals(0, ControlCenterBlurPolicy.radius(0, percent))
    }

    @Test fun nativeAnimationRemainsMonotonic() {
        val radii = (0..100).map { ControlCenterBlurPolicy.radius(it, 75) }
        radii.zipWithNext().forEach { (before, after) -> assertTrue(after - before in 0..1) }
    }

    @Test fun invalidInputsAreBounded() {
        assertEquals(80, ControlCenterBlurPolicy.radius(80, 500))
        assertEquals(0, ControlCenterBlurPolicy.radius(80, -10))
        assertEquals(0, ControlCenterBlurPolicy.radius(-1, 100))
    }

    @Test fun openingAndClosingUseTheSameRatioWithoutAVisibilityHandoff() {
        for (percent in listOf(0, 25, 50, 75, 100)) {
            val opening = (0..100).map { ControlCenterBlurPolicy.radius(it, percent) }
            val closing = (100 downTo 0).map { ControlCenterBlurPolicy.radius(it, percent) }
            assertEquals(opening, closing.reversed())
            assertEquals(percent, opening.last())
        }
    }
}
