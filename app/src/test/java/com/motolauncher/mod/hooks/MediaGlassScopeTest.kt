package com.motolauncher.mod.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGlassScopeTest {
    @Test fun dynamicPopupScopeExcludesOrdinaryWindows() {
        assertTrue(MediaGlassScope.isDynamicHost("dynamic_media_container", "android.widget.FrameLayout"))
        assertTrue(MediaGlassScope.isDynamicHost("", "com.motorola.systemui.ongoing.MediaPanel"))
        assertTrue(MediaGlassScope.isPopupWindow(2017))
        assertTrue(MediaGlassScope.isPopupWindow(1000))
        assertFalse(MediaGlassScope.isPopupWindow(1))
        assertFalse(MediaGlassScope.isPopupWindow(2040))
        assertFalse(MediaGlassScope.isDynamicHost("media_player", "android.widget.FrameLayout"))
    }

    @Test fun vendorControlCenterKeepsStockMaterial() {
        assertTrue(MediaGlassScope.isControlCenter("media_panel",
            "com.motorola.systemui.prc.media.MediaPanelModernQS"))
        assertTrue(MediaGlassScope.isControlCenter("", "com.android.systemui.qs.QSModernPanel"))
        assertTrue(MediaGlassScope.isControlCenter("qs_media_host", "android.widget.FrameLayout"))
        assertTrue(MediaGlassScope.isControlCenter("modern_qs_media_container", "android.widget.FrameLayout"))
    }

    @Test fun notificationAndKeyguardHostsAllowGlass() {
        assertTrue(MediaGlassScope.isNotificationHost("",
            "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout"))
        assertTrue(MediaGlassScope.isNotificationHost("",
            "com.android.systemui.statusbar.notification.row.MediaContainerView"))
        assertTrue(MediaGlassScope.isNotificationHost("",
            "com.android.systemui.statusbar.notification.stack.MediaContainerView"))
        assertTrue(MediaGlassScope.isNotificationHost("keyguard_media_host", "android.widget.FrameLayout"))
        assertFalse(MediaGlassScope.isControlCenter("keyguard_media_host", "android.widget.FrameLayout"))
    }

    @Test fun genericMediaCardDoesNotAuthorizeUnknownHost() {
        assertFalse(MediaGlassScope.isControlCenter("qs_media_controls",
            "com.android.systemui.util.animation.TransitionLayout"))
        assertFalse(MediaGlassScope.isNotificationHost("media_player",
            "com.android.systemui.util.animation.TransitionLayout"))
        assertFalse(MediaGlassScope.isNotificationHost("media_panel",
            "com.motorola.systemui.prc.media.MediaPanelModernQS"))
        assertFalse(MediaGlassScope.isNotificationHost("", "android.widget.FrameLayout"))
    }
}
