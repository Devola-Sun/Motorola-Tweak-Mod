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

    @Test fun disabledUnlockedAndUnknownHostsRemainStock() {
        assertFalse(LockMediaCardScope.allows(false, true, true, false))
        assertFalse(LockMediaCardScope.allows(true, false, true, false))
        assertFalse(LockMediaCardScope.allows(true, true, false, false))
    }
}
