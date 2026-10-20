package com.motolauncher.mod

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Forces every hook error to be written to [HookConfig.ERROR_LOG_PATH]
 * (/sdcard/Download/Errorlog) in addition to logcat, so a crash or a silently
 * skipped hook can always be diagnosed later - also on devices where the
 * module cannot be debugged live.
 */
object ErrorLogger {

    private val logFile = File(HookConfig.ERROR_LOG_PATH)

    @Synchronized
    fun log(tag: String, message: String, error: Throwable? = null) {
        runCatching { Log.e(tag, message, error) }
        runCatching {
            logFile.parentFile?.mkdirs()
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val entry = buildString {
                append('[').append(stamp).append("][").append(tag).append("] ").append(message)
                if (error != null) {
                    append('\n').append(Log.getStackTraceString(error))
                }
                append('\n')
            }
            logFile.appendText(entry)
            runCatching { logFile.setReadable(true, false) }
        }
    }
}
