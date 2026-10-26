package com.motolauncher.mod.hooks

import kotlin.math.roundToInt

/** Both shade panels share one window: scale its native animation without a visibility handoff. */
internal object ControlCenterBlurPolicy {
    fun radius(nativeRadius: Int, percent: Int): Int {
        val scale = percent.coerceIn(0, 100) / 100f
        return (nativeRadius.coerceAtLeast(0) * scale).roundToInt()
    }
}
