package com.motolauncher.mod

import org.junit.Assert.*
import org.junit.Test

class ReleaseUpdatePolicyTest {
    @Test fun sameReleaseIsRemindedAgainWithoutKillingTheProcess() {
        val prompts = ReleasePromptPolicy()
        assertTrue(prompts.canPrompt("v2.5.8", 0))
        prompts.recordPrompt("v2.5.8", 0)
        assertFalse(prompts.canPrompt("v2.5.8", 1))
        assertTrue(prompts.canPrompt("v2.5.8", 6 * 60 * 60 * 1000L))
        assertTrue(prompts.canPrompt("v2.5.9", 1))
    }

    @Test fun automaticCacheExpiresAndManualChecksAlwaysFetch() {
        var now = 0L
        var calls = 0
        var tag: String? = null
        val cache = ReleaseCheckCache({ now }) { calls++; tag }
        assertEquals(ReleaseCheckResult.Success(null), cache.check())
        tag = "v2.5.8"
        assertEquals(ReleaseCheckResult.Success(null), cache.check())
        assertEquals(1, calls)
        assertEquals(ReleaseCheckResult.Success(tag), cache.check(force = true))
        assertEquals(2, calls)
        now = 15 * 60 * 1000L
        cache.check()
        assertEquals(3, calls)
    }

    @Test fun failedRequestsAreNotReportedAsUpToDateAndCanBeRetried() {
        var now = 0L
        var fail = true
        var calls = 0
        val cache = ReleaseCheckCache({ now }) {
            calls++
            if (fail) error("HTTP 403")
            "v2.5.8"
        }
        assertTrue(cache.check() is ReleaseCheckResult.Failure)
        fail = false
        assertTrue(cache.check() is ReleaseCheckResult.Failure)
        assertEquals(1, calls)
        now = 60 * 1000L
        assertEquals(ReleaseCheckResult.Success("v2.5.8"), cache.check())
        assertEquals(2, calls)
    }

    @Test fun manualRetryBypassesFailureCooldown() {
        var fail = true
        val cache = ReleaseCheckCache({ 0L }) {
            if (fail) error("offline")
            null
        }
        assertTrue(cache.check() is ReleaseCheckResult.Failure)
        fail = false
        assertEquals(ReleaseCheckResult.Success(null), cache.check(force = true))
    }

    @Test fun newPatchVersionIsRecognisedAndInstalledVersionIsNotAnUpdate() {
        assertTrue(ReleaseVersion.isNewer("v2.5.7", "2.5.6"))
        assertFalse(ReleaseVersion.isNewer("v2.5.7", "2.5.7"))
        assertFalse(ReleaseVersion.isNewer("v2.5.6", "2.5.7"))
    }

    @Test fun secondPublishedReleaseIsDetectedAndNotSuppressedByTheFirstPrompt() {
        var now = 0L
        var releases = listOf(PublishedRelease("v2.5.5", false, false))
        val cache = ReleaseCheckCache({ now }) {
            ReleaseSelection.newestTag(releases.asSequence(), "2.5.4")
        }
        val prompts = ReleasePromptPolicy()
        assertEquals(ReleaseCheckResult.Success("v2.5.5"), cache.check())
        prompts.recordPrompt("v2.5.5", now)
        releases = releases + PublishedRelease("v2.5.6", false, false)
        now = 15 * 60 * 1000L
        assertEquals(ReleaseCheckResult.Success("v2.5.6"), cache.check())
        assertTrue(prompts.canPrompt("v2.5.6", now))
        assertFalse(prompts.canPrompt("v2.5.5", now))
    }

    @Test fun selectsHighestStableVersionRegardlessOfLatestDesignationOrPublishOrder() {
        val releases = listOf(
            PublishedRelease("v2.5.5", false, false),
            PublishedRelease("v2.5.8", true, false),
            PublishedRelease("v2.6.0", false, true),
            PublishedRelease("v2.7.0-rc.1", false, false),
            PublishedRelease("v2.5.7", false, false),
            PublishedRelease("v2.5.6", false, false),
            PublishedRelease("latest", false, false)
        )
        assertEquals("v2.5.7", ReleaseSelection.newestTag(releases.asSequence(), "2.5.4"))
        assertNull(ReleaseSelection.newestTag(releases.asSequence(), "2.5.7"))
    }
}
