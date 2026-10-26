package com.motolauncher.mod.hooks

import org.junit.Assert.*
import org.junit.Test

class NotificationStackGeometryTest {
    @Test fun emptyAndSingleCardDoNotScroll() {
        assertTrue(NotificationStackGeometry.place(emptyList(), 20f, 10f, 28f).isEmpty())
        assertEquals(0f, NotificationStackGeometry.scrollRange(listOf(100f), 10f), 0f)
        val card = NotificationStackGeometry.place(listOf(100f), 200f, 10f, 28f).single()
        assertEquals(0f, card.y, 0f)
        assertTrue(card.visible)
        assertEquals(1f, card.scale, 0f)
    }

    @Test fun twoCardsOverlapWithTheFrontCardAbove() {
        val cards = NotificationStackGeometry.place(listOf(100f, 100f), 0f, 10f, 28f)
        assertEquals(0f, cards[0].y, 0f)
        assertEquals(28f, cards[1].y, 0f)
        assertTrue(cards[0].depth > cards[1].depth)
        assertTrue(cards[1].scale < cards[0].scale)
        assertTrue(cards.all { it.visible })
    }

    @Test fun onlyThreeCardsAreVisibleAtRest() {
        val cards = NotificationStackGeometry.place(List(8) { 100f }, 0f, 10f, 28f)
        assertEquals(3, cards.count { it.visible })
        assertTrue(cards.drop(3).all { it.alpha == 0f })
    }

    @Test fun scrollingRevealsEveryCardIncludingTheLast() {
        val heights = listOf(80f, 140f, 100f, 70f)
        var scroll = 0f
        heights.indices.forEach { index ->
            val cards = NotificationStackGeometry.place(heights, scroll, 10f, 28f)
            assertEquals(0f, cards[index].y, .001f)
            assertTrue(cards[index].visible)
            assertTrue(cards.take(index).none { it.visible })
            scroll += heights[index] + 10f
        }
        assertEquals(350f, NotificationStackGeometry.scrollRange(heights, 10f), .001f)
    }

    @Test fun overscrollIsClamped() {
        val heights = listOf(100f, 100f)
        assertEquals(NotificationStackGeometry.place(heights, 0f, 10f, 28f),
            NotificationStackGeometry.place(heights, -500f, 10f, 28f))
        assertEquals(NotificationStackGeometry.place(heights, 110f, 10f, 28f),
            NotificationStackGeometry.place(heights, 500f, 10f, 28f))
    }

    @Test fun nextCardMovesContinuouslyTowardTheFront() {
        val heights = listOf(100f, 100f, 100f)
        val halfway = NotificationStackGeometry.place(heights, 55f, 10f, 28f)
        assertEquals(-55f, halfway[0].y, .001f)
        assertEquals(14f, halfway[1].y, .001f)
        val before = NotificationStackGeometry.place(heights, 109.99f, 10f, 28f)[1]
        val after = NotificationStackGeometry.place(heights, 110f, 10f, 28f)[1]
        assertEquals(before.y, after.y, .01f)
        assertEquals(before.scale, after.scale, .01f)
    }
}
