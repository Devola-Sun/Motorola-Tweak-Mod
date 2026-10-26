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

    /**
     * The scope markers are written under their own tag. The second command is
     * a fallback for ROMs whose logcat ignores the `-s` filter: it dumps the
     * tail of the buffer and greps the tag out of it.
     */
    private const val SCOPE_LOGCAT_COMMAND =
        "logcat -d -t 1000 -s $SCOPE_LOG_TAG 2>/dev/null; " +
            "logcat -d -t 1000 2>/dev/null | grep -F $SCOPE_LOG_TAG"

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
         * The latest LSPosed configuration is authoritative. Old log markers
         * must not re-enable scopes the user has just removed.
         */
        val hooked: Set<String>
            get() = when {
                !moduleEnabled -> emptySet()
                else -> scopedPackages
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

            if (scoped != null) {
                Log.i(TAG, "ScopeDetector: enabled=${scoped.enabled} scoped=${scoped.packages}")
                callback(Result(scoped.enabled, scoped.packages, emptySet(), true))
                return@run
            }

            RootShell.run(LOGCAT_COMMAND) { logOk, output ->
                val live = if (logOk) parseLive(output) else emptySet()
                RootShell.run(SCOPE_LOGCAT_COMMAND) { scopeLogOk, scopeOutput ->
                    val markers = if (scopeLogOk) parseScopeMarkers(scopeOutput) else emptySet()
                    // The markers are also parsed from the module tag output,
                    // so a scope is detected even if the dedicated tag was
                    // filtered out by the logcat implementation.
                    val allMarkers = markers + parseScopeMarkers(output)
                    RootShell.run(PROCESS_COMMAND) { processOk, processOutput ->
                        val running = if (processOk) parseProcesses(processOutput) else emptySet()
                        val active = (live + allMarkers).filter { isProcessRunning(it, running) }.toSet()
                        val result = Result(
                            moduleEnabled = true,
                            scopedPackages = active,
                            livePackages = active,
                            resolved = active.isNotEmpty()
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

    /**
     * Reads the module's scope from LSPosed's config database.
     *
     * Current LSPosed (and Vector) keep the module itself in the `modules`
     * table (`module_pkg_name`, `enabled`) and the scoped packages in the
     * `scope` table, which only refers to the module through the auto
     * increment `mid` column. The scope rows are therefore read through a
     * join on `mid`.
     *
     * Older LSPosed releases stored `module_pkg_name` inside the `scope` table
     * and the enabled flag in `modules_state`; that layout is used as a
     * fallback so both generations are supported.
     */
    private fun readScopeDb(dbFile: File): DbScope? = runCatching {
        if (!dbFile.exists()) return@runCatching null
        val database = openDatabase(dbFile) ?: return@runCatching null
        database.use { db ->
            DbScope(
                enabled = readEnabled(db),
                packages = readPackages(db)
            )
        }
    }.onFailure { Log.w(TAG, "ScopeDetector: db read failed", it) }.getOrNull()

    /**
     * Opens the copied database. A WAL database that was copied while the
     * daemon was running may have to be recovered first, which a read-only
     * handle cannot do, so the read-write attempt is the fallback (the copy
     * lives in the app cache and may be modified safely).
     */
    private fun openDatabase(dbFile: File): SQLiteDatabase? {
        val readOnly = runCatching {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
        }.getOrNull()
        if (readOnly != null) return readOnly

        return runCatching {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            )
        }.getOrNull()
    }

    /** The module's enabled flag: `modules.enabled` (new) or `modules_state` (old). */
    private fun readEnabled(db: SQLiteDatabase): Boolean {
        val fromModules = runCatching {
            db.rawQuery(
                "select enabled from modules where module_pkg_name = ?",
                arrayOf(MODULE_PACKAGE)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) != 0 else null }
        }.getOrNull()
        if (fromModules != null) return fromModules

        val fromState = runCatching {
            db.rawQuery(
                "select enabled from modules_state where module_pkg_name = ? and user_id = 0",
                arrayOf(MODULE_PACKAGE)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) != 0 else null }
        }.getOrNull()
        return fromState ?: true
    }

    /** The scoped packages of this module. */
    private fun readPackages(db: SQLiteDatabase): Set<String> {
        val columns = db.rawQuery("PRAGMA table_info(scope)", null).use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
        if ("module_pkg_name" in columns) {
            return queryNames(
                db,
                "select app_pkg_name from scope where module_pkg_name = ? and user_id = 0",
                arrayOf(MODULE_PACKAGE)
            ).map { normalize(it) }.toSet()
        }
        val names = mutableListOf<String>()

        // Current LSPosed / Vector: the scope rows only hold the module id.
        names += queryNames(
            db,
            "select scope.app_pkg_name from scope inner join modules on scope.mid = modules.mid " +
                "where modules.module_pkg_name = ? and scope.user_id = 0",
            arrayOf(MODULE_PACKAGE)
        )
        if (names.isEmpty()) {
            names += queryNames(
                db,
                "select scope.app_pkg_name from scope inner join modules on scope.mid = modules.mid " +
                    "where modules.module_pkg_name = ?",
                arrayOf(MODULE_PACKAGE)
            )
        }

        // Legacy LSPosed: the module package name was stored in the scope table.
        if (names.isEmpty()) {
            names += queryNames(
                db,
                "select app_pkg_name from scope where module_pkg_name = ? and user_id = 0",
                arrayOf(MODULE_PACKAGE)
            )
        }
        if (names.isEmpty()) {
            names += queryNames(
                db,
                "select app_pkg_name from scope where module_pkg_name = ?",
                arrayOf(MODULE_PACKAGE)
            )
        }

        return names.map { normalize(it) }.toSet()
    }

    /** Runs a query and collects the first column; a failing query is not fatal. */
    private fun queryNames(db: SQLiteDatabase, sql: String, args: Array<String>): List<String> =
        runCatching {
            val result = mutableListOf<String>()
            db.rawQuery(sql, args).use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    result += name
                }
            }
            result
        }.onFailure { Log.w(TAG, "ScopeDetector: query failed: $sql", it) }
            .getOrDefault(emptyList())

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
                .takeWhile { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
            if (name.isNotEmpty()) markers += normalize(name)
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
