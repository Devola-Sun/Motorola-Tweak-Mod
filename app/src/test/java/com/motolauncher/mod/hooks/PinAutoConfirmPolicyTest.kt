package com.motolauncher.mod.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinAutoConfirmPolicyTest {
    @Test fun knownLengthMismatchAndInvalidSettingsStayStock() {
        for (length in 4..16) assertTrue(PinAutoConfirmPolicy.applies(true, length, length))
        assertFalse(PinAutoConfirmPolicy.applies(false, 6, 6))
        assertFalse(PinAutoConfirmPolicy.applies(true, 4, 6))
        assertFalse(PinAutoConfirmPolicy.applies(true, 6, 4))
        assertFalse(PinAutoConfirmPolicy.applies(true, 6, 0))
        assertFalse(PinAutoConfirmPolicy.applies(true, 3, 3))
        assertFalse(PinAutoConfirmPolicy.applies(true, 17, 17))
    }

    @Test fun explicitLengthWorksWithoutOptionalSystemMetadata() {
        assertTrue(PinAutoConfirmPolicy.applies(true, 4, -1))
        assertTrue(PinAutoConfirmPolicy.applies(true, 6, -1))
        assertFalse(PinAutoConfirmPolicy.applies(false, 4, -1))
        assertFalse(PinAutoConfirmPolicy.applies(true, 3, -1))
        assertFalse(PinAutoConfirmPolicy.applies(true, 17, -1))
    }

    @Test fun submitsOnlyAtExactLengthWhileAppending() {
        for (target in listOf(4, 6, 8, 16)) {
            for (entered in 0..20) {
                val submit = PinAutoConfirmPolicy.shouldSubmit(target, entered, true, false)
                if (target == entered) assertTrue(submit) else assertFalse(submit)
            }
            assertFalse(PinAutoConfirmPolicy.shouldSubmit(target, target, false, false))
            assertFalse(PinAutoConfirmPolicy.shouldSubmit(target, target, true, true))
        }
    }
}
