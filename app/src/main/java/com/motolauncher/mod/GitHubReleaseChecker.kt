package com.motolauncher.mod

import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** Process-local cache also survives settings Activity recreation. Call check() off the UI thread. */
internal object GitHubReleaseChecker {
    const val RELEASE_PAGE = "https://github.com/Devola-Sun/Motorola-Tweak-Mod/releases/tag/"
    private const val API_URL = "https://api.github.com/repos/Devola-Sun/Motorola-Tweak-Mod/releases?per_page=100"
    private val cache = ReleaseCheckCache(SystemClock::elapsedRealtime, ::fetchTag)
    // Accessed only by the main thread.
    private val prompts = ReleasePromptPolicy()

    fun canPrompt(tag: String): Boolean = prompts.canPrompt(tag, SystemClock.elapsedRealtime())

    fun recordPrompt(tag: String) = prompts.recordPrompt(tag, SystemClock.elapsedRealtime())

    fun check(force: Boolean = false): ReleaseCheckResult = cache.check(force).also {
        if (it is ReleaseCheckResult.Failure) {
            Log.d("MotoLauncherMod", "GitHub release check unavailable", it.error)
        }
    }

    private fun fetchTag(): String? {
        val connection = URL(API_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "MockingBatwing/${BuildConfig.VERSION_NAME}")
            val status = connection.responseCode
            check(status == HttpURLConnection.HTTP_OK) { "GitHub HTTP $status" }
            val json = connection.inputStream.bufferedReader().use { JSONArray(it.readText()) }
            val releases = (0 until json.length()).asSequence().map { index ->
                val release = json.getJSONObject(index)
                PublishedRelease(
                    release.getString("tag_name"),
                    release.getBoolean("draft"),
                    release.getBoolean("prerelease")
                )
            }
            return ReleaseSelection.newestTag(releases, BuildConfig.VERSION_NAME)
        } finally {
            connection.disconnect()
        }
    }
}
