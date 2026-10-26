package com.motolauncher.mod.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaControlGeometryTest {
    @Test fun fiveButtonsHaveSeparateHitTargets() {
        val slots = MediaControlGeometry.actions(400, 12, 48, 5)
        assertEquals(5, slots.size)
        slots.zipWithNext().forEach { (left, right) ->
            assertTrue(left.left + left.size <= right.left)
        }
        assertEquals(176, slots[2].left)
    }

    @Test fun narrowAndManyActionLayoutsStayInsideTheCard() {
        for (width in listOf(240, 320, 400)) for (count in 1..8) {
            val slots = MediaControlGeometry.actions(width, 12, 48, count)
            assertTrue(slots.first().left >= 12)
            assertTrue(slots.last().let { it.left + it.size } <= width - 12)
            slots.zipWithNext().forEach { (left, right) ->
                assertTrue(left.left + left.size <= right.left)
            }
        }
    }

    @Test fun noActionsIsSupported() {
        assertTrue(MediaControlGeometry.actions(400, 12, 48, 0).isEmpty())
    }
}
