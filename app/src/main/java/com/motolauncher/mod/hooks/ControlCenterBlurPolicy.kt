package com.motolauncher.mod.hooks

import kotlin.math.roundToInt

/** Both shade panels share one window: scale its native animation without a visibility handoff. */
internal object ControlCenterBlurPolicy {
    fun scale(percent: Int): Float = percent.coerceIn(0, 100) / 100f

    fun radius(nativeRadius: Int, percent: Int): Int {
        // One ratio from the first expansion frame through collapse. Glass and
        // panel visibility must not introduce a second blur transition.
        return (nativeRadius.coerceAtLeast(0) * scale(percent)).roundToInt()
    }
}
