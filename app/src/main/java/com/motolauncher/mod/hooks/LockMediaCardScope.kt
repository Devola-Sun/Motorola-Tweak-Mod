package com.motolauncher.mod.hooks

/** Shared compact playback style, never control-center media or an unknown host. */
internal object LockMediaCardScope {
    fun allows(enabled: Boolean, keyguard: Boolean, notificationHost: Boolean, controlCenter: Boolean,
        dynamicHost: Boolean = false): Boolean =
        enabled && (notificationHost || dynamicHost) && !controlCenter
}
