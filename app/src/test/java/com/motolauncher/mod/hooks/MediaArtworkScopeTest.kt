package com.motolauncher.mod.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaArtworkScopeTest {
    @Test fun matchesOnlyVendorControlCenterRippleShade() {
        val ripple = "com.android.systemui.surfaceeffects.ripple.MultiRippleView"
        val card = "com.motorola.systemui.prc.media.MotoMediaViewForPrcQS"
        assertTrue(MediaArtworkScope.isControlCenterShade("touch_ripple_view", ripple, card))
        assertFalse(MediaArtworkScope.isControlCenterShade("album_art", ripple, card))
        assertFalse(MediaArtworkScope.isControlCenterShade("touch_ripple_view", "android.view.View", card))
        assertFalse(MediaArtworkScope.isControlCenterShade("touch_ripple_view", ripple,
            "com.android.systemui.util.animation.TransitionLayout"))
        assertFalse(MediaArtworkScope.isControlCenterShade("touch_ripple_view", ripple,
            "com.motorola.systemui.prc.media.MediaPanelKeyguard"))
    }

    @Test fun acceptsArtworkButNotIconsControlsOrBackgrounds() {
        assertTrue(MediaArtworkScope.isArtwork("album_art"))
        assertTrue(MediaArtworkScope.isArtwork("media_artwork"))
        for (name in listOf("icon", "actionPlayPause", "media_background", "header_title", "")) {
            assertFalse(name, MediaArtworkScope.isArtwork(name))
        }
    }

    @Test fun includesNotificationControlCenterKeyguardAndVendorPopupHosts() {
        assertTrue(MediaArtworkScope.isMediaHost("media_player", "android.view.ViewGroup"))
        assertTrue(MediaArtworkScope.isMediaHost("qs_media_controls", "android.view.ViewGroup"))
        assertTrue(MediaArtworkScope.isMediaHost("album_art_container", "android.widget.FrameLayout"))
        assertTrue(MediaArtworkScope.isMediaHost("", "com.motorola.systemui.prc.media.MediaPanelModernQS"))
        assertTrue(MediaArtworkScope.isMediaHost("", "com.motorola.systemui.prc.media.MediaPanelKeyguard"))
        assertTrue(MediaArtworkScope.isMediaHost("", "com.motorola.systemui.prc.media.MediaPanel"))
    }

    @Test fun includesModernControlCenterArtworkImageDespiteContainerId() {
        // Captured Modern QS hierarchy: album_art_container:ImageView is a child
        // of MediaPanelModernQS, unlike the album_art used by the other players.
        assertTrue(MediaArtworkScope.isArtwork("album_art_container"))
        assertTrue(MediaArtworkScope.isMediaHost("media_panel_on_modern_qs",
            "com.motorola.systemui.prc.media.MediaPanelModernQS"))
        for (name in listOf("icon", "action1", "action2", "action3",
            "media_seamless_image", "media_progress_bar_default")) {
            assertFalse(name, MediaArtworkScope.isArtwork(name))
        }
    }

    @Test fun doesNotMatchUnrelatedImageContainers() {
        assertFalse(MediaArtworkScope.isMediaHost("root_view", "android.widget.FrameLayout"))
        assertFalse(MediaArtworkScope.isMediaHost("notification_panel", "android.view.ViewGroup"))
        assertFalse(MediaArtworkScope.isMediaHost("", "com.example.media.MediaPanel"))
    }
}
