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

    @Test fun referenceLayoutKeepsSemanticActionsCenteredWithoutOptionalAction() {
        val ids = listOf("actionPrev", "actionPlayPause", "actionNext")
        for (custom in listOf(emptyList(), listOf("action0"))) {
            val slots = MediaControlGeometry.referenceActions(400, 12, 48, ids, custom)
            assertEquals(200, slots.getValue("actionPlayPause").let { it.left + it.size / 2 })
        }
    }

    @Test fun outputNeverOverlapsButtonsWithManySessionActions() {
        val semantic = listOf("actionPrev", "actionPlayPause", "actionNext")
        for (width in listOf(180, 240, 320, 400)) for (count in 0..5) {
            val custom = List(count) { "action$it" }
            val slots = MediaControlGeometry.referenceActions(width, 12, 48, semantic, custom)
            val output = MediaControlGeometry.referenceOutput(width, 12, 48, count)
            val row = (slots.values + output).sortedBy { it.left }
            assertEquals(semantic.size + count + 1, row.size)
            assertTrue(row.first().left >= 12)
            assertTrue(row.last().let { it.left + it.size } <= width - 12)
            row.zipWithNext().forEach { (a, b) -> assertTrue(a.left + a.size <= b.left) }
        }
    }
}
