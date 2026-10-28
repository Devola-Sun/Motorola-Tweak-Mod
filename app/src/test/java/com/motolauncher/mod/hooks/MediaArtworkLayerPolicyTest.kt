package com.motolauncher.mod.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaArtworkLayerPolicyTest {
    @Test fun replacesCompositeScrimInsteadOfRelyingOnMutableLayerAlpha() {
        assertTrue(MediaArtworkLayerPolicy.replaceScrim(composite = true, scrim = true))
        // Neither native image crossfades nor standalone placeholders are scrims.
        assertFalse(MediaArtworkLayerPolicy.replaceScrim(composite = true, scrim = false))
        assertFalse(MediaArtworkLayerPolicy.replaceScrim(composite = false, scrim = true))
        assertFalse(MediaArtworkLayerPolicy.replaceScrim(composite = false, scrim = false))
    }

    @Test fun removesDimmingScrimsInCompositeArtwork() {
        assertEquals(0, MediaArtworkLayerPolicy.alpha(180, composite = true, scrim = true))
        assertEquals(0, MediaArtworkLayerPolicy.alpha(255, composite = true, scrim = true))
    }

    @Test fun retainsTrackTransitionAlphaForImageLayers() {
        assertEquals(96, MediaArtworkLayerPolicy.alpha(96, composite = true, scrim = false))
        assertEquals(255, MediaArtworkLayerPolicy.alpha(255, composite = true, scrim = false))
    }

    @Test fun undimsSingleImageWithoutHidingStandalonePlaceholder() {
        assertEquals(255, MediaArtworkLayerPolicy.alpha(128, composite = false, scrim = false))
        assertEquals(255, MediaArtworkLayerPolicy.alpha(128, composite = false, scrim = true))
    }
}
