package com.motolauncher.mod.hooks

/** Release the vendor's count-based shelf cap only in the open, unlocked shade. */
internal class SilentNotificationShadeLimit {
    private var stockLimit: Int? = null

    fun update(current: Int, enabled: Boolean, statusBarState: Int, shadeExpanded: Boolean, dozing: Boolean): Int {
        if (enabled && statusBarState == 0 && shadeExpanded && !dozing) {
            if (current >= 0) stockLimit = current
            return -1
        }
        val stock = stockLimit ?: return current
        stockLimit = null
        // A fresh native limit wins over an older saved value.
        return if (current == -1) stock else current
    }
}
