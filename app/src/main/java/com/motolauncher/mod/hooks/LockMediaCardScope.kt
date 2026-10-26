package com.motolauncher.mod.hooks

/** A locked device alone does not authorize styling: QS may be open over keyguard. */
internal object LockMediaCardScope {
    fun allows(enabled: Boolean, keyguard: Boolean, notificationHost: Boolean, controlCenter: Boolean): Boolean =
        enabled && keyguard && notificationHost && !controlCenter
}
