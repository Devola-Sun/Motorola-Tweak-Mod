package com.motolauncher.mod.hooks

import com.motolauncher.mod.hooks.NativeThreeColumnTaskGuard.Identity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeThreeColumnTaskGuardTest {
    private val roots = listOf(Identity(3, "split-root"), Identity(4, "A"), Identity(5, "B"), Identity(6, "C"))
    private val apps = listOf(Identity(9519, "anchor"), Identity(9518, "center"), Identity(9520, "right"))

    @Test fun rejectsParentRootAndEveryStageEvenWithAppMetadata() {
        for (root in roots) {
            assertFalse(NativeThreeColumnTaskGuard.isApplicationCandidate(root, roots))
            assertFalse(NativeThreeColumnTaskGuard.canReparent(apps.take(2) + root, roots))
        }
    }

    @Test fun rejectsMatchingIdOrTokenIndependently() {
        assertFalse(NativeThreeColumnTaskGuard.canReparent(apps.take(2) + Identity(3, "other-token"), roots))
        assertFalse(NativeThreeColumnTaskGuard.canReparent(apps.take(2) + Identity(9521, "split-root"), roots))
    }

    @Test fun onlyThreeDistinctAppTasksAndFourKnownDistinctRootsCanCommit() {
        assertTrue(NativeThreeColumnTaskGuard.canReparent(apps, roots))
        assertFalse(NativeThreeColumnTaskGuard.canReparent(apps, roots.dropLast(1)))
        assertFalse(NativeThreeColumnTaskGuard.canReparent(apps, roots.dropLast(1) + roots.first()))
        assertFalse(NativeThreeColumnTaskGuard.canReparent(apps.take(2) + apps.first(), roots))
        assertFalse(NativeThreeColumnTaskGuard.canReparent(apps.take(2) + Identity(9521, "anchor"), roots))
    }

    @Test fun repeatEnterUsesTheCurrentPhysicalPermutation() {
        val expected = listOf("A", "B", "C")
        val rolled = listOf("C", "A", "B")
        assertEquals(rolled, NativeThreeColumnTaskGuard.orderedStages(rolled, expected))
        assertNull(NativeThreeColumnTaskGuard.orderedStages(listOf("C", "A", "A"), expected))
        assertNull(NativeThreeColumnTaskGuard.orderedStages(listOf("C", "A", "D"), expected))
        assertNull(NativeThreeColumnTaskGuard.orderedStages(listOf("C", "A"), expected))
    }
}
