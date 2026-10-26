package com.motolauncher.mod

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Process-local cache also survives settings Activity recreation. Call check() off the UI thread. */
internal object GitHubReleaseChecker {
    const val RELEASE_PAGE = "https://github.com/Devola-Sun/Motorola-Tweak-Mod/releases/tag/"
    private const val API_URL = "https://api.github.com/repos/Devola-Sun/Motorola-Tweak-Mod/releases/latest"
    private const val SUCCESS_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val FAILURE_INTERVAL_MS = 15 * 60 * 1000L
    private var nextCheckAt = 0L
    private var cachedTag: String? = null

    // Accessed only by the main thread; avoid repeating the dialog on each resume/rotation.
    var lastPromptedTag: String? = null

    @Synchronized
    fun check(): String? {
        if (SystemClock.elapsedRealtime() < nextCheckAt) return cachedTag
        return try {
            cachedTag = fetchTag()
            nextCheckAt = SystemClock.elapsedRealtime() + SUCCESS_INTERVAL_MS
            cachedTag
        } catch (error: Exception) {
            nextCheckAt = SystemClock.elapsedRealtime() + FAILURE_INTERVAL_MS
            Log.d("MotoLauncherMod", "GitHub release check unavailable", error)
            cachedTag
        }
    }

    private fun fetchTag(): String? {
        val connection = URL(API_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "MockingBatwing/${BuildConfig.VERSION_NAME}")
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return null
            check(status == HttpURLConnection.HTTP_OK) { "GitHub HTTP $status" }
            val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            if (json.getBoolean("draft") || json.getBoolean("prerelease")) return null
            return json.getString("tag_name").takeIf {
                ReleaseVersion.isNewer(it, BuildConfig.VERSION_NAME)
            }
        } finally {
            connection.disconnect()
        }
    }
}
