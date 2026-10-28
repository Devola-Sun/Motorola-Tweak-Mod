package com.motolauncher.mod.hooks

/** Preserve native crossfades while removing the separate artwork dimming scrim. */
internal object MediaArtworkLayerPolicy {
    // Removing a scrim by alpha alone is not durable: LayerDrawable.setAlpha
    // propagates to its children when ImageView/native transitions update it.
    fun replaceScrim(composite: Boolean, scrim: Boolean): Boolean = composite && scrim

    fun alpha(original: Int, composite: Boolean, scrim: Boolean): Int = when {
        composite && scrim -> 0
        composite -> original
        else -> 255
    }
}
