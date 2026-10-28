package com.motolauncher.mod.hooks

import org.junit.Assert.assertEquals
import org.junit.Test

class DockScaleStateTest {
    @Test fun homeReturnAndUnlockFinishAtTheLatestStockScale() {
        val state = DockScaleState(0.85f, 0.85f)
        state.update(0.85f, 0.85f, 1.2f)
        listOf(0.9f, 0.97f, 1f).forEach { frame ->
            assertEquals(frame * 1.2f, state.onStockScaleX(frame), 0.0001f)
            assertEquals(frame * 1.2f, state.onStockScaleY(frame), 0.0001f)
        }
        state.update(state.appliedScaleX, state.appliedScaleY, 1.2f)
        assertEquals(1.2f, state.appliedScaleX, 0.0001f)
        assertEquals(1.2f, state.appliedScaleY, 0.0001f)
    }

    @Test fun repeatedMeasurementsDoNotCompoundTheUserScale() {
        val state = DockScaleState(1f, 1f)
        repeat(100) {
            state.update(state.appliedScaleX, state.appliedScaleY, 0.8f)
            assertEquals(0.8f, state.appliedScaleX, 0.0001f)
            assertEquals(0.8f, state.appliedScaleY, 0.0001f)
        }
    }

    @Test fun disablingOrUnfoldingRemovesOnlyTheUserMultiplier() {
        val state = DockScaleState(0.85f, 0.85f)
        state.update(0.85f, 0.85f, 0.8f)
        state.onStockScaleX(1f)
        state.onStockScaleY(1f)
        state.update(state.appliedScaleX, state.appliedScaleY, 1f)
        assertEquals(1f, state.appliedScaleX, 0f)
        assertEquals(1f, state.appliedScaleY, 0f)
    }

    @Test fun changingSettingsMidAnimationPreservesTheAnimationFrame() {
        val state = DockScaleState(1f, 1f)
        state.update(1f, 1f, 0.8f)
        state.onStockScaleX(0.9f)
        state.onStockScaleY(0.95f)
        state.update(state.appliedScaleX, state.appliedScaleY, 1.2f)
        assertEquals(0.9f * 1.2f, state.appliedScaleX, 0.0001f)
        assertEquals(0.95f * 1.2f, state.appliedScaleY, 0.0001f)
        state.update(state.appliedScaleX, state.appliedScaleY, 1f)
        assertEquals(0.9f, state.appliedScaleX, 0.0001f)
        assertEquals(0.95f, state.appliedScaleY, 0.0001f)
    }

    @Test fun directStockChangesAreAcceptedWithoutReusingTheInitialSnapshot() {
        val state = DockScaleState(0.85f, 0.85f)
        state.update(0.85f, 0.85f, 0.8f)
        state.update(1f, 1f, 0.8f)
        assertEquals(0.8f, state.appliedScaleX, 0.0001f)
        assertEquals(0.8f, state.appliedScaleY, 0.0001f)
    }
}
