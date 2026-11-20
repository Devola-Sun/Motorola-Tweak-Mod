package com.motolauncher.mod.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeThreeColumnOffscreenTouchTest {
    @Test fun onlyCarouselZonesAreMuted() {
        assertTrue(NativeThreeColumnOffscreenTouch.isCarouselTitle("OffscreenTouchZoneTopLeft"))
        assertTrue(NativeThreeColumnOffscreenTouch.isCarouselTitle("OffscreenTouchZoneBottomRight"))
        listOf("StageCoordinatorSplitDivider0", "StageCoordinatorSplitDivider1", "Caption of Task=4",
            "recents_animation_input_consumer", "OtherOffscreenTouchZoneTopLeft").forEach {
            assertFalse(NativeThreeColumnOffscreenTouch.isCarouselTitle(it))
        }
    }
}
