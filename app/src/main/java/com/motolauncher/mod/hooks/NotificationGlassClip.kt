package com.motolauncher.mod.hooks

/** Native clip amounts may briefly exceed the background height during dismissal. */
internal object NotificationGlassClip {
    fun top(amount: Int, height: Int): Int = amount.coerceIn(0, height.coerceAtLeast(0))

    fun bottom(amount: Int, height: Int, top: Int): Int =
        (height.coerceAtLeast(0) - amount.coerceAtLeast(0)).coerceAtLeast(top)
}
