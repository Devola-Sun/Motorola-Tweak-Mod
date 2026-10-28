package com.motolauncher.mod

internal sealed interface ReleaseCheckResult {
    data class Success(val tag: String?) : ReleaseCheckResult
    data class Failure(val error: Exception) : ReleaseCheckResult
}

/** A manual check always reaches GitHub, including after a cached failure. */
internal class ReleaseCheckCache(
    private val clock: () -> Long,
    private val fetch: () -> String?
) {
    private var nextCheckAt = 0L
    private var cached: ReleaseCheckResult? = null

    @Synchronized
    fun check(force: Boolean = false): ReleaseCheckResult {
        cached?.let { if (!force && clock() < nextCheckAt) return it }
        val result = try {
            ReleaseCheckResult.Success(fetch())
        } catch (error: Exception) {
            ReleaseCheckResult.Failure(error)
        }
        cached = result
        nextCheckAt = clock() + if (result is ReleaseCheckResult.Success) {
            15 * 60 * 1000L
        } else {
            60 * 1000L
        }
        return result
    }
}

internal data class PublishedRelease(val tag: String, val draft: Boolean, val prerelease: Boolean)

internal object ReleaseSelection {
    /** Do not depend on GitHub's manually selectable `latest` designation or list order. */
    fun newestTag(releases: Sequence<PublishedRelease>, installed: String): String? {
        var newest = installed
        var selected: String? = null
        releases.forEach { release ->
            if (!release.draft && !release.prerelease && ReleaseVersion.isStable(release.tag) &&
                ReleaseVersion.isNewer(release.tag, newest)) {
                newest = release.tag
                selected = release.tag
            }
        }
        return selected
    }
}

/** Suppress resume/rotation duplicates, but never silence a release indefinitely. */
internal class ReleasePromptPolicy {
    private var lastTag: String? = null
    private var remindAt = 0L

    fun canPrompt(tag: String, now: Long): Boolean = tag != lastTag || now >= remindAt

    fun recordPrompt(tag: String, now: Long) {
        lastTag = tag
        remindAt = now + 6 * 60 * 60 * 1000L
    }
}
