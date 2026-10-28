package com.motolauncher.mod.hooks

/** Scope artwork by both its ID and a real media ancestor, never by the current theme. */
internal object MediaArtworkScope {
    private val artworkNames = setOf(
        // Modern QS uses this "container" ID for an ImageView, not a ViewGroup.
        // The caller still requires an ImageView inside an actual media host.
        "album_art", "album_art_container", "album_art_view", "media_album_art", "media_artwork",
        "media_cover", "cover_image", "artwork", "album", "album_cover"
    )

    private val mediaHosts = setOf(
        "media_player", "media_player_view", "media_panel", "qs_media_controls",
        "media_recommendation", "media_carousel", "media_carousel_container",
        "album_art_container", "media_player_container", "modern_media_container",
        "modern_media_player_container"
    )

    fun isArtwork(name: String): Boolean = name in artworkNames

    /** The vendor draws its persistent shade behind the ripple, above the cover. */
    fun isControlCenterShade(name: String, className: String, parentClassName: String): Boolean =
        name == "touch_ripple_view" &&
            className == "com.android.systemui.surfaceeffects.ripple.MultiRippleView" &&
            parentClassName == "com.motorola.systemui.prc.media.MotoMediaViewForPrcQS"

    fun isMediaHost(name: String, className: String): Boolean = name in mediaHosts ||
        className.startsWith("com.motorola.systemui.") &&
        (className.contains(".media.") || className.contains("MediaPanel"))
}
