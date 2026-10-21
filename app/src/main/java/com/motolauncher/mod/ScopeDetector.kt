package com.motolauncher.mod

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * Detects the scopes this module is really hooked into.
 *
 * The authoritative source is LSPosed's own config database
 * (/data/adb/lspd/config/modules_config.db): it stores the scope rows and the
 * module's enabled state. The database (WAL included) is copied into the app
 * cache with root and read back with [SQLiteDatabase].
 *
 * Logcat markers written by the hooks themselves ("... loaded") are used as a
 * second signal for "actually loaded into the process right now".
 */
object ScopeDetector {

    private const val TAG = "MotoLauncherMod"

    /**
     * The module's own package name as registered in LSPosed. It follows the
     * applicationId, so it is taken from BuildConfig instead of a literal.
     */
    private val MODULE_PACKAGE: String = BuildConfig.APPLICATION_ID

    /** LSPosed stores the system framework scope as "system" or "android". */
    private val FRAMEWORK_SCOPE_KEYS = setOf("system", "android")

    /**
     * Dedicated tag for the scope markers. The launcher logs a lot of lines,
     * so the markers would be pushed out of the logcat window when they share
     * a tag with the verbose output.
     */
    private const val SCOPE_LOG_TAG = "MotoLauncherModScope"

    private const val LOGCAT_COMMAND = "logcat -d -t 3000 -s MotoLauncherMod"

    private const val SCOPE_LOGCAT_COMMAND = "logcat -d -t 500 -s $SCOPE_LOG_TAG"

    /**
     * The processes that carry a hook. The framework scope is served by
     * system_server, the other two by their own app process. The command
     * prints the name of every process that is currently running.
     */
    private const val PROCESS_COMMAND =
        "for p in com.motorola.launcher3 com.android.systemui system_server; do " +
            "if pidof \$p >/dev/null 2>&1; then echo \$p; fi; done"

    data class Result(
        val moduleEnabled: Boolean,
        val scopedPackages: Set<String>,
        val livePackages: Set<String>,
        val resolved: Boolean
    ) {
        /**
         * Packages whose hooks are (or will be after a restart) active.
         *
         * The scope stored by LSPosed is the authoritative source; the log
         * markers are an additional signal. Both are merged, otherwise a
         * scope whose marker rotated out of the logcat buffer would be shown
         * as inactive even though the hook is installed.
         */
        val hooked: Set<String>
            get() = when {
                !moduleEnabled -> emptySet()
                else -> scopedPackages + livePackages
            }
    }

    private data class DbScope(val enabled: Boolean, val packages: Set<String>)

    fun detect(context: Context, callback: (Result) -> Unit) {
        val cacheDir = File(context.cacheDir, "lspd").apply { mkdirs() }
        val dbFile = File(cacheDir, "modules_config.db")

        val copyCommand = buildString {
            append("rm -f '${dbFile.absolutePath}'*; ")
            append("cp /data/adb/lspd/config/modules_config.db* '${cacheDir.absolutePath}/' 2>/dev/null; ")
            append("chmod 644 '${cacheDir.absolutePath}'/modules_config.db* 2>/dev/null; true")
        }

        RootShell.run(copyCommand) { copyOk, copyOutput ->
            if (!copyOk) Log.w(TAG, "ScopeDetector: db copy failed: $copyOutput")
            val scoped = if (copyOk) readScopeDb(dbFile) else null

            RootShell.run(LOGCAT_COMMAND) { logOk, output ->
                val live = if (logOk) parseLive(output) else emptySet()
                RootShell.run(SCOPE_LOGCAT_COMMAND) { scopeLogOk, scopeOutput ->
                    val markers = if (scopeLogOk) parseScopeMarkers(scopeOutput) else emptySet()
                    RootShell.run(PROCESS_COMMAND) { processOk, processOutput ->
                        // The logcat buffer rotates far too fast to be a
                        // reliable source (the launcher writes thousands of
                        // lines per second), so the running processes decide
                        // whether a scoped hook is actually active.
                        val running = if (processOk) parseProcesses(processOutput) else emptySet()
                        val active = scoped?.packages
                            ?.filter { isProcessRunning(it, running) }
                            ?.toSet()
                            .orEmpty()
                        val result = Result(
                            moduleEnabled = scoped?.enabled ?: true,
                            scopedPackages = scoped?.packages ?: emptySet(),
                            livePackages = live + markers + active,
                            resolved = scoped != null || live.isNotEmpty() ||
                                markers.isNotEmpty() || active.isNotEmpty()
                        )
                        Log.i(TAG, "ScopeDetector: enabled=${result.moduleEnabled} " +
                            "scoped=${result.scopedPackages} live=${result.livePackages} " +
                            "hooked=${result.hooked}")
                        callback(result)
                    }
                }
            }
        }
    }

    private fun readScopeDb(dbFile: File): DbScope? = runCatching {
        if (!dbFile.exists()) return@runCatching null
        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        db.use { database ->
            val enabled = database.rawQuery(
                "select enabled from modules_state where module_pkg_name = ? and user_id = 0",
                arrayOf(MODULE_PACKAGE)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) != 0 else true }

            val packages = mutableSetOf<String>()
            database.rawQuery(
                "select app_pkg_name from scope where module_pkg_name = ? and user_id = 0",
                arrayOf(MODULE_PACKAGE)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    packages += normalize(name)
                }
            }
            DbScope(enabled, packages)
        }
    }.onFailure { Log.w(TAG, "ScopeDetector: db read failed", it) }.getOrNull()

    /** LSPosed uses "system" for the system framework scope. */
    private fun normalize(scopeName: String): String =
        if (scopeName in FRAMEWORK_SCOPE_KEYS) HookConfig.FRAMEWORK_PACKAGE else scopeName

    private fun parseLive(output: String): Set<String> {
        val live = mutableSetOf<String>()
        if (output.contains("Settings loaded: hide_labels")) live += HookConfig.TARGET_PACKAGE
        if (output.contains("SystemUI settings loaded")) live += HookConfig.SYSTEM_UI_PACKAGE
        if (output.contains("System framework settings loaded")) live += HookConfig.FRAMEWORK_PACKAGE
        return live
    }

    /**
     * Parses the dedicated scope markers ("scope=com.android.systemui") that
     * every hooked process writes once when the module is loaded.
     */
    private fun parseScopeMarkers(output: String): Set<String> {
        val markers = mutableSetOf<String>()
        output.lineSequence().forEach { line ->
            val index = line.indexOf(MARKER_PREFIX)
            if (index < 0) return@forEach
            val name = line.substring(index + MARKER_PREFIX.length)
                .trim()
                .takeWhile { it.isLetterOrDigit() || it == '.' || it == '_' }
            if (name.isNotEmpty()) markers += name
        }
        return markers
    }

    private const val MARKER_PREFIX = "scope="

    /**
     * Maps a scoped package to the process that carries the hook.
     */
    private fun isProcessRunning(packageName: String, running: Set<String>): Boolean =
        when (packageName) {
            HookConfig.FRAMEWORK_PACKAGE -> "system_server" in running
            else -> packageName in running
        }

    /** Parses the process names printed by [PROCESS_COMMAND]. */
    private fun parseProcesses(output: String): Set<String> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
}
