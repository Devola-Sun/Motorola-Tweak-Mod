package com.motolauncher.mod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {
    @Test fun comparesNumericComponentsRatherThanText() {
        assertTrue(ReleaseVersion.isNewer("v2.10.0", "2.9.9"))
        assertTrue(ReleaseVersion.isNewer("v3.0.0", "2.99.99"))
        assertFalse(ReleaseVersion.isNewer("v2.5.3", "2.5.4"))
        assertFalse(ReleaseVersion.isNewer("v2.5.4", "2.5.4"))
        assertFalse(ReleaseVersion.isNewer("v2.5.4", "2.5.5"))
    }

    @Test fun handlesEquivalentVersionsAndBuildMetadata() {
        assertFalse(ReleaseVersion.isNewer("v2.5.4.0", "2.5.4"))
        assertFalse(ReleaseVersion.isNewer("2.5.4+build.2", "2.5.4+build.1"))
        assertTrue(ReleaseVersion.isNewer("v2.5.5+build.1", "2.5.4"))
    }

    @Test fun respectsPrereleasePrecedence() {
        assertTrue(ReleaseVersion.isNewer("v2.5.4", "2.5.4-rc.1"))
        assertFalse(ReleaseVersion.isNewer("v2.5.4-rc.1", "2.5.4"))
        assertTrue(ReleaseVersion.isNewer("2.5.4-rc.10", "2.5.4-rc.9"))
        assertTrue(ReleaseVersion.isNewer("2.5.4-beta", "2.5.4-alpha"))
        assertTrue(ReleaseVersion.isNewer("2.5.4-rc.1", "2.5.4-rc"))
        assertTrue(ReleaseVersion.isNewer("2.5.4-beta", "2.5.4-1"))
    }

    @Test fun ignoresUnrecognisedTagsAndDoesNotOverflow() {
        for (tag in listOf("", "latest", "release-2.6.0", "2..6", "2.6/other")) {
            assertFalse(ReleaseVersion.isNewer(tag, "2.5.4"))
        }
        assertFalse(ReleaseVersion.isNewer("2.6.0", "unknown"))
        assertTrue(ReleaseVersion.isNewer("999999999999999999999.0", "2.5.4"))
    }
}
