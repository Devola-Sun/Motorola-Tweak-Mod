package com.motolauncher.mod.hooks

import com.motolauncher.mod.HookConfig.BatteryIconStyle
import com.motolauncher.mod.HookConfig.BatteryPercentStyle

/** Resolve preferences before layout: an unmeasured icon is not an unavailable icon. */
internal data class BatteryDisplayPolicy(
    val iconStyle: BatteryIconStyle,
    val showPercentInside: Boolean,
    val showExternalPercent: Boolean?
) {
    val customSize: BatteryIconSize?
        get() = BatteryIconSize.forStyle(iconStyle)

    companion object {
        fun resolve(icon: BatteryIconStyle, percent: BatteryPercentStyle): BatteryDisplayPolicy {
            val inside = percent == BatteryPercentStyle.IN_ICON && icon != BatteryIconStyle.HIDDEN
            return BatteryDisplayPolicy(
                // Keep a recognizable battery body/terminal for the default icon.
                // An explicitly selected circle still uses a level ring, not a solid badge.
                iconStyle = if (inside && icon == BatteryIconStyle.SYSTEM) BatteryIconStyle.PILL else icon,
                showPercentInside = inside,
                showExternalPercent = when (percent) {
                    BatteryPercentStyle.SYSTEM -> null
                    BatteryPercentStyle.ALWAYS -> true
                    BatteryPercentStyle.HIDDEN -> false
                    BatteryPercentStyle.IN_ICON -> !inside
                }
            )
        }
    }
}

/** Compact status-bar dimensions shared by ImageView layout and Drawable intrinsic size. */
internal data class BatteryIconSize(val widthDp: Int, val heightDp: Int) {
    companion object {
        fun forStyle(style: BatteryIconStyle): BatteryIconSize? = when (style) {
            BatteryIconStyle.CIRCLE -> BatteryIconSize(18, 18)
            BatteryIconStyle.PILL -> BatteryIconSize(26, 16)
            BatteryIconStyle.SYSTEM, BatteryIconStyle.HIDDEN -> null
        }
    }
}
