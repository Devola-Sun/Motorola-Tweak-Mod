package com.motolauncher.mod.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockMediaCardScopeTest {
    @Test fun enabledKeyguardNotificationCardIsStyled() {
        assertTrue(LockMediaCardScope.allows(true, true, true, false))
    }

    @Test fun controlCenterIsExcludedEvenOverKeyguard() {
        assertFalse(LockMediaCardScope.allows(true, true, true, true))
        assertFalse(LockMediaCardScope.allows(true, true, false, true))
    }

    @Test fun unlockedNotificationCenterIsStyledToo() {
        assertTrue(LockMediaCardScope.allows(true, false, true, false))
    }

    @Test fun disabledAndUnknownHostsRemainStock() {
        assertFalse(LockMediaCardScope.allows(false, true, true, false))
        assertFalse(LockMediaCardScope.allows(true, true, false, false))
    }

    @Test fun expandedIslandSharesTheExistingCompactToggle() {
        assertTrue(LockMediaCardScope.allows(true, false, false, false, dynamicHost = true))
        assertTrue(LockMediaCardScope.allows(true, true, false, false, dynamicHost = true))
        assertFalse(LockMediaCardScope.allows(false, false, false, false, dynamicHost = true))
        assertFalse(LockMediaCardScope.allows(false, true, false, false, dynamicHost = true))
    }

    @Test fun controlCenterWinsOverDynamicHostIdentification() {
        assertFalse(LockMediaCardScope.allows(true, false, false, true, dynamicHost = true))
        assertFalse(LockMediaCardScope.allows(true, true, true, true, dynamicHost = true))
    }

    @Test fun leavingAnIslandForAnUnknownHostRestoresStock() {
        assertTrue(LockMediaCardScope.allows(true, false, false, false, dynamicHost = true))
        assertFalse(LockMediaCardScope.allows(true, false, false, false, dynamicHost = false))
    }
}
