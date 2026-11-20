package com.motolauncher.mod.hooks

import com.motolauncher.mod.hooks.NativeThreeColumnFlow.Columns
import com.motolauncher.mod.hooks.NativeThreeColumnFlow.Phase
import org.junit.Assert.*
import org.junit.Test

class NativeThreeColumnFlowTest {
    @Test fun claimsOnlyTheRequestedAnchorWithThreeDistinctAppTasks() {
        val flow = NativeThreeColumnFlow()
        assertTrue(flow.begin(10))
        assertFalse(flow.begin(99))
        assertNull(flow.chooseCenter(20))
        flow.observe(Columns(10, 20, 30))
        assertEquals(Phase.SELECTING_RIGHT, flow.phase)
        assertFalse(flow.chooseRight(10))
        assertTrue(flow.chooseRight(30))
        flow.observe(Columns(10, 20, 30))
        assertNull(flow.requested)
        assertEquals(Phase.SELECTING_CENTER, flow.phase)
        assertFalse(flow.chooseRight(31))
        assertNull(flow.chooseCenter(30))
        assertNull(flow.chooseCenter(10))
        assertEquals(Columns(10, 20, 30), flow.chooseCenter(20))
        assertNull(flow.chooseCenter(21))
        flow.observe(Columns(10, null, 30))
        flow.observe(Columns(10, 30, 30))
        flow.observe(Columns(10, 30, 20))
        assertEquals(Phase.COMMITTING, flow.phase)
        val complete = Columns(10, 20, 30)
        flow.observe(complete)
        assertEquals(Phase.ACTIVE, flow.phase)
        assertTrue(flow.owns(20, complete))
        assertFalse(flow.owns(40, complete))
        assertFalse(flow.owns(20, Columns(10, 20, 40)))
    }

    @Test fun columnRollingPreservesOwnershipAfterCommit() {
        val flow = NativeThreeColumnFlow()
        assertTrue(flow.begin(10))
        flow.chooseRight(30)
        flow.chooseCenter(20)
        flow.observe(Columns(10, 20, 30))
        assertEquals(Phase.ACTIVE, flow.phase)
        val rolled = Columns(30, 10, 20)
        flow.observe(rolled)
        assertTrue(flow.owns(30, rolled))
        flow.observe(Columns(30, null, 20))
        assertEquals(Phase.IDLE, flow.phase)
    }

    @Test fun resetDoesNotClaimALateCompletion() {
        val flow = NativeThreeColumnFlow()
        assertFalse(flow.begin(-1))
        assertTrue(flow.begin(10))
        flow.chooseRight(30)
        flow.chooseCenter(20)
        flow.reset()
        flow.observe(Columns(10, 20, 30))
        assertEquals(Phase.IDLE, flow.phase)
        assertTrue(flow.begin(40))
        flow.observe(Columns(10, 20, 30))
        assertEquals(Phase.SELECTING_RIGHT, flow.phase)
    }

    @Test fun cancelDuringSelectionDiscardsRightChoice() {
        val flow = NativeThreeColumnFlow()
        assertTrue(flow.begin(10))
        assertTrue(flow.chooseRight(30))
        flow.reset()
        assertNull(flow.chooseCenter(20))
        assertTrue(flow.begin(10))
        assertTrue(flow.chooseRight(40))
        assertEquals(Columns(10, 20, 40), flow.chooseCenter(20))
    }
}
