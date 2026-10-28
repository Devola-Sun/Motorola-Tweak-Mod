package com.motolauncher.mod.hooks

/** Keeps the user's Dock multiplier separate from Launcher3's animated scale. */
internal class DockScaleState(initialScaleX: Float, initialScaleY: Float) {
    var factor = 1f
        private set
    private var stockScaleX = initialScaleX
    private var stockScaleY = initialScaleY
    var appliedScaleX = initialScaleX
        private set
    var appliedScaleY = initialScaleY
        private set

    fun onStockScaleX(value: Float): Float {
        stockScaleX = value
        appliedScaleX = value * factor
        return appliedScaleX
    }

    fun onStockScaleY(value: Float): Float {
        stockScaleY = value
        appliedScaleY = value * factor
        return appliedScaleY
    }

    fun update(currentScaleX: Float, currentScaleY: Float, newFactor: Float) {
        // Also accept stock writes that bypass the Java setters. Never recapture
        // our own multiplied output as a new baseline on repeated measure passes.
        if (currentScaleX != appliedScaleX) stockScaleX = currentScaleX
        if (currentScaleY != appliedScaleY) stockScaleY = currentScaleY
        factor = newFactor
        appliedScaleX = stockScaleX * factor
        appliedScaleY = stockScaleY * factor
    }
}
