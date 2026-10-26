package com.motolauncher.mod.hooks

/** Match actual hosts, not the device's locked state: QS can be open on the lock screen. */
internal object MediaGlassScope {
    private val controlCenterHosts = setOf(
        "qs_frame", "quick_settings_panel", "quick_qs_panel", "qs_panel", "qs_container",
        "qs_media_host", "qqs_media_host", "modern_media_container",
        "modern_media_player_container", "modern_qs_media_container"
    )
    private val notificationHosts = setOf(
        "notification_stack_scroller", "keyguard_media_host", "keyguard_media_container",
        "keyguard_media_view", "media_keyguard"
    )

    fun isControlCenter(resource: String, className: String): Boolean =
        className.startsWith("com.android.systemui.qs.") ||
            className.startsWith("com.motorola.systemui.prc.media.MediaPanelModernQS") ||
            resource in controlCenterHosts

    fun isNotificationHost(resource: String, className: String): Boolean =
        className == "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout" ||
            className == "com.android.systemui.statusbar.notification.stack.MediaContainerView" ||
            className == "com.android.systemui.statusbar.notification.row.MediaContainerView" ||
            resource in notificationHosts

    fun isDynamicHost(resource: String, className: String): Boolean {
        val name = "$resource $className".lowercase()
        return listOf("dynamic", "island", "ongoing", "heads_up", "headsup",
            "media_popup", "floating_media").any(name::contains)
    }

    /** Separate SystemUI popup windows, not an arbitrary unattached media view. */
    fun isPopupWindow(type: Int): Boolean = type in setOf(1000, 1002, 2014, 2017, 2038)
}
