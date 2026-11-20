package com.motolauncher.mod.hooks

import com.motolauncher.mod.hooks.InnerSnapGeometry.Box
import com.motolauncher.mod.hooks.ThreeColumnPolicy.Candidate
import org.junit.Assert.*
import org.junit.Test

class ThreeColumnPolicyTest {
    private fun task(id: Int, user: Int = 0, pkg: String = "app$id", mode: Int = 1,
        standard: Boolean = true, root: Boolean = true, embedded: Boolean = false, time: Long = id.toLong()) =
        Candidate(id, user, pkg, mode, standard, root, embedded, time)

    @Test fun anchorThenMostRecentDistinctApps() {
        assertEquals(listOf(1, 4, 3), ThreeColumnPolicy.select(
            listOf(task(1), task(2), task(3), task(4)), 1, emptySet()))
    }
    @Test fun excludesOtherUsersPipSplitHomeAndEmbedded() {
        val list = listOf(task(1), task(2, user = 10), task(3, mode = 2), task(4, mode = 6),
            task(5, standard = false), task(6, embedded = true), task(7, root = false), task(8), task(9, mode = 5))
        assertEquals(listOf(1, 9, 8), ThreeColumnPolicy.select(list, 1, emptySet()))
    }
    @Test fun excludesDuplicatePackagesAndNeverUsesOnlyTwoTasks() {
        assertTrue(ThreeColumnPolicy.select(listOf(task(1), task(2, pkg = "app1"), task(3)), 1, emptySet()).isEmpty())
        assertTrue(ThreeColumnPolicy.select(listOf(task(1), task(2), task(3)), 1, setOf("app1")).isEmpty())
        assertTrue(ThreeColumnPolicy.select(listOf(task(1), task(2), task(3)), 4, emptySet()).isEmpty())
    }
    @Test fun cardsPreserveOutsideEdgesWithTwoGapsAndHeaderOffset() {
        val safe = Box(17, 152, 2501, 2148)
        val cards = ThreeColumnPolicy.cards(safe, 2.625f, 40)
        val columns = cards.map { it.visible }
        assertEquals(3, columns.size)
        assertEquals(safe.left, columns.first().left)
        assertEquals(safe.right, columns.last().right)
        assertEquals(safe.width - 16, columns.sumOf { it.width })
        cards.forEach { card ->
            assertEquals(safe.top, card.visible.top)
            assertEquals(safe.bottom, card.visible.bottom)
            assertEquals(card.visible.copy(top = 47), card.task)
            assertEquals(105, card.cropTop)
        }
        assertEquals(8, columns[1].left - columns[0].right)
        assertEquals(8, columns[2].left - columns[1].right)
    }
    @Test fun widthsDifferByAtMostOnePixel() {
        val widths = ThreeColumnPolicy.cards(Box(0, 0, 2485, 1900), 2.625f, 42).map { it.visible.width }
        assertTrue(widths.max() - widths.min() <= 1)
    }
    @Test fun rejectsPortraitInvalidDensityAndTooNarrowColumns() {
        assertTrue(ThreeColumnPolicy.cards(Box(0, 0, 1000, 2000), 1f, 40).isEmpty())
        assertTrue(ThreeColumnPolicy.cards(Box(0, 0, 1000, 800), 3f, 40).isEmpty())
        assertTrue(ThreeColumnPolicy.cards(Box(0, 0, 2500, 2000), Float.NaN, 40).isEmpty())
    }
    @Test fun usesDeviceDpiAndSupportsNegativeTaskTop() {
        assertEquals(311, ThreeColumnPolicy.targetDensity(420, 74))
        assertEquals(420, ThreeColumnPolicy.targetDensity(420, 100))
        val card = ThreeColumnPolicy.cards(Box(0, 40, 2484, 2200), 2.625f, 42).first()
        assertEquals(-70, card.task.top)
        assertEquals(110, card.cropTop)
    }
    @Test fun handleSwapsOuterColumnsWithCenterAndCenterWithRight() {
        val expected = listOf(listOf(2, 1, 3), listOf(1, 3, 2), listOf(1, 3, 2))
        expected.forEachIndexed { i, order ->
            val cards = mutableListOf(1, 2, 3)
            ThreeColumnPolicy.swapWithCenter(cards, i)
            assertEquals(order, cards)
        }
    }
    @Test fun onlyVerifiedShellMenuTitleMatches() {
        assertEquals(9389, ThreeColumnPolicy.menuTask("Additional view container of Task=9389"))
        assertNull(ThreeColumnPolicy.menuTask("Handle Input Layer of task 9389"))
        assertNull(ThreeColumnPolicy.menuTask("PopupWindow:9389"))
        assertNull(ThreeColumnPolicy.menuTask("Additional view container of Task=-1"))
        assertNull(ThreeColumnPolicy.menuTask("Additional view container of Task=2147483648"))
    }
    @Test fun expandsOnlyFixedMenusThatFit() {
        assertEquals(802, ThreeColumnPolicy.expandedMenuHeight(424, 378, 2232))
        assertEquals(750, ThreeColumnPolicy.expandedMenuHeight(400, 350, 1001))
        assertNull(ThreeColumnPolicy.expandedMenuHeight(401, 350, 1001))
        assertNull(ThreeColumnPolicy.expandedMenuHeight(-2, 378, 2232))
        assertNull(ThreeColumnPolicy.expandedMenuHeight(Int.MAX_VALUE, 378, Int.MAX_VALUE))
    }
}
