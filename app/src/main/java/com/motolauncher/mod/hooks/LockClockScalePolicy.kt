package com.motolauncher.mod.hooks

import com.motolauncher.mod.HookConfig

/** Only actual faces, never containers that also hold dates, widgets or notifications. */
internal object LockClockScalePolicy {
    fun isFace(id: String, className: String): Boolean =
        id in setOf("lockscreen_clock_view", "lockscreen_clock_view_large") ||
            className.endsWith(".FlexClockView") ||
            className == "com.android.keyguard.AnimatableClockView" ||
            className == "com.android.systemui.shared.clocks.AnimatableClockView"

    fun factor(percent: Int, keyguard: Boolean, dozing: Boolean, interactive: Boolean): Float =
        if (keyguard && !dozing && interactive) HookConfig.coerceLockClockSize(percent) / 100f else 1f
}
