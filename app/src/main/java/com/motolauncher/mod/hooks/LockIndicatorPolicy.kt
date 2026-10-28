package com.motolauncher.mod.hooks

internal object LockIndicatorPolicy {
    fun isNativeIcon(id: String, className: String): Boolean =
        id in setOf("lock_icon_view", "lock_icon", "device_entry_icon", "device_entry_icon_view") ||
            className == "com.android.keyguard.LockIconView" ||
            className == "com.android.systemui.keyguard.ui.view.DeviceEntryIconView"

    fun top(clockTop: Float, safeTop: Float, size: Float, gap: Float): Float =
        (clockTop - gap - size).coerceAtLeast(safeTop)
}
