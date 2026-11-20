package com.motolauncher.mod.hooks

import com.motolauncher.mod.hooks.InnerSnapGeometry.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeThreeColumnGeometryTest {
    @Test fun shellFitOnlyAppliesToLandscapeDisplayRoots() {
        assertTrue(NativeThreeColumnGeometry.isLandscapeRoot(Box(0, 0, 2484, 2232)))
        assertTrue(NativeThreeColumnGeometry.isLandscapeRoot(Box(17, 40, 2501, 2272)))
        assertEquals(false, NativeThreeColumnGeometry.isLandscapeRoot(Box(0, 0, 2232, 2484)))
        assertEquals(false, NativeThreeColumnGeometry.isLandscapeRoot(Box(0, 0, 2232, 2232)))
        assertEquals(false, NativeThreeColumnGeometry.isLandscapeRoot(Box(0, 2, 2484, 1)))
    }

    @Test fun measuredLandscapeModelStaysWithinBothScreenEdges() {
        val layout = checkNotNull(NativeThreeColumnGeometry.calculate(Box(0, 0, 2484, 2232), 26))
        assertEquals(Box(0, 0, 811, 2232), layout.stageA)
        assertEquals(Box(837, 0, 1647, 2232), layout.stageB)
        assertEquals(Box(1673, 0, 2484, 2232), layout.stageC)
        assertEquals(Box(811, 0, 837, 2232), layout.dividerAB)
        assertEquals(Box(1647, 0, 1673, 2232), layout.dividerBC)
    }

    @Test fun effectiveInsetsAndAllRemaindersPreserveCoverage() {
        for (width in 55..2500) {
            val effective = Box(17, 41, 17 + width, 2148)
            val layout = checkNotNull(NativeThreeColumnGeometry.calculate(effective, 26))
            val pieces = listOf(layout.stageA, layout.dividerAB, layout.stageB, layout.dividerBC, layout.stageC)
            assertEquals(effective.left, pieces.first().left)
            assertEquals(effective.right, pieces.last().right)
            for ((left, right) in pieces.zipWithNext()) assertEquals(left.right, right.left)
            for (piece in pieces) {
                assertEquals(effective.top, piece.top)
                assertEquals(effective.bottom, piece.bottom)
                assertTrue(piece.width > 0)
            }
            assertEquals(26, layout.dividerAB.width)
            assertEquals(26, layout.dividerBC.width)
            val widths = listOf(layout.stageA.width, layout.stageB.width, layout.stageC.width)
            assertEquals(width - 52, widths.sum())
            assertTrue(widths.max() - widths.min() <= 1)
            assertEquals(layout.stageA.width, layout.stageC.width)
        }
    }

    @Test fun invalidBoundsOrDividerLeaveNoUsableLayout() {
        assertNull(NativeThreeColumnGeometry.calculate(Box(0, 0, 54, 12), 26))
        assertNull(NativeThreeColumnGeometry.calculate(Box(0, 12, 300, 12), 26))
        assertNull(NativeThreeColumnGeometry.calculate(Box(9, 0, -300, 12), 26))
        assertNull(NativeThreeColumnGeometry.calculate(Box(0, 0, 300, 12), 0))
        assertNull(NativeThreeColumnGeometry.calculate(Box(0, 0, 300, 12), -26))
        assertNull(NativeThreeColumnGeometry.calculate(Box(0, 0, 300, 12), Int.MAX_VALUE))
        assertNull(NativeThreeColumnGeometry.fromDividers(Box(0, 0, 54, 12), 26, 12, 40))
    }

    @Test fun nativeCutsAreRelativeToEffectiveOrigin() {
        val root = Box(-17, 41, 2467, 2189)
        assertEquals(NativeThreeColumnGeometry.calculate(root, 26),
            NativeThreeColumnGeometry.fromDividers(root, 26, 811, 1647))
    }

    @Test fun clampReversedAndOffscreenCuts() {
        val root = Box(0, 0, 2484, 2232)
        val layout = checkNotNull(NativeThreeColumnGeometry.fromDividers(root, 26, Int.MAX_VALUE, Int.MIN_VALUE))
        assertEquals(listOf(1622, 405, 405), layout.stages.map { it.width })
        assertEquals(26, layout.dividerAB.width)
        assertEquals(26, layout.dividerBC.width)
        assertEquals(root.left, layout.stageA.left)
        assertEquals(root.right, layout.stageC.right)
        assertTrue(layout.stages.all { it.top == root.top && it.bottom == root.bottom })
    }

    @Test fun bothDividersClampIndependentlyWithoutMovingTheOtherCut() {
        val root = Box(17, 41, 2501, 2189)
        val equal = checkNotNull(NativeThreeColumnGeometry.calculate(root, 26))
        val moveLeft = checkNotNull(NativeThreeColumnGeometry.moveDivider(root, 26, equal, 0, Int.MAX_VALUE))
        assertEquals(equal.dividerBC, moveLeft.dividerBC)
        assertEquals(405, moveLeft.stageB.width)
        val moveRight = checkNotNull(NativeThreeColumnGeometry.moveDivider(root, 26, moveLeft, 1, Int.MAX_VALUE))
        assertEquals(moveLeft.dividerAB, moveRight.dividerAB)
        assertEquals(405, moveRight.stageC.width)
        assertEquals(root.right, moveRight.stageC.right)
        val moveBack = checkNotNull(NativeThreeColumnGeometry.moveDivider(root, 26, moveRight, 1, Int.MIN_VALUE))
        assertEquals(moveLeft, moveBack)
        assertNull(NativeThreeColumnGeometry.moveDivider(root, 26, equal, 2, 1000))
    }

    @Test fun negativeDisplayOriginAndWideSpanDoNotOverflowCalculations() {
        val effective = Box(Int.MIN_VALUE, -4, Int.MAX_VALUE, 18)
        val layout = checkNotNull(NativeThreeColumnGeometry.calculate(effective, 1))
        assertEquals(effective.left, layout.stageA.left)
        assertEquals(effective.right, layout.stageC.right)
        assertEquals(1, layout.dividerAB.width)
        assertEquals(1, layout.dividerBC.width)
        val widths = listOf(layout.stageA.width, layout.stageB.width, layout.stageC.width)
        assertTrue(widths.max() - widths.min() <= 1)
        assertEquals(4_294_967_293L, widths.sumOf { it.toLong() })
    }
}
