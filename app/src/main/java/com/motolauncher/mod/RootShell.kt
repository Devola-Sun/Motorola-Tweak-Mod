package com.motolauncher.mod

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.TimeUnit
import java.io.IOException

/**
 * Runs one of the fixed root commands off the main thread.
 *
 * The exit code is reported as success. stdout/stderr (merged) are returned for
 * display and are also written to logcat under the MotoLauncherMod tag.
 */
object RootShell {

    private const val TAG = "MotoLauncherMod"
    private const val TIMEOUT_SECONDS = 20L

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun start(command: String, mergeErrors: Boolean = true): Process {
        var failure: IOException? = null
        // Some root managers omit su from the application's PATH.
        for (binary in listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "su")) {
            try {
                return ProcessBuilder(binary, "-M", "-c", command)
                    .redirectErrorStream(mergeErrors).start()
            } catch (error: IOException) {
                failure = error
            }
        }
        throw IOException("suを起動できません。KernelSU / SukiSU / Magiskでこのアプリのroot権限を許可し、アプリを開き直してください。", failure)
    }

    fun run(command: String, callback: (success: Boolean, output: String) -> Unit) {
        Log.i(TAG, "Root command start: $command")

        Thread {
            var success = false
            var exitCode = -1
            var output = ""

            runCatching {
                // -M (KernelSU/Magisk) enters the global mount namespace.
                // Without it, an app process's su cannot see another app's
                // data directory (for example the launcher database).
                val process = start(command)
                val captured = StringBuilder()
                // Drain while running: waiting before reading can deadlock on
                // logcat output larger than the process pipe buffer.
                val reader = Thread {
                    runCatching {
                        process.inputStream.bufferedReader().use { stream ->
                            val buffer = CharArray(4096)
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                synchronized(captured) {
                                    captured.append(buffer, 0, count)
                                    if (captured.length > 1_048_576) captured.delete(0, captured.length - 1_048_576)
                                }
                            }
                        }
                    }
                }.apply { isDaemon = true; start() }

                if (process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    exitCode = process.exitValue()
                    reader.join(1000)
                    output = synchronized(captured) { captured.toString().trim() }
                    success = exitCode == 0
                } else {
                    process.destroyForcibly()
                    output = "su command timed out after ${TIMEOUT_SECONDS}s"
                }
            }.onFailure {
                output = it.message ?: it.javaClass.simpleName
            }

            if (!success && exitCode >= 0) {
                output = buildString {
                    append("exit=").append(exitCode)
                    if (output.isNotBlank()) append('\n').append(output)
                }
            }

            Log.i(TAG, "Root command end: success=$success exit=$exitCode output=$output")
            mainHandler.post { callback(success, output) }
        }.start()
    }

    /**
     * Runs a root command and returns raw stdout bytes (stderr is returned as
     * text). Used to read the launcher database through `cat`.
     */
    fun runBytes(
        command: String,
        callback: (success: Boolean, output: String, bytes: ByteArray) -> Unit
    ) {
        Log.i(TAG, "Root byte command start: $command")

        Thread {
            var success = false
            var output = ""
            var bytes = ByteArray(0)

            runCatching {
                val process = start(command, mergeErrors = false)
                bytes = process.inputStream.readBytes()
                output = process.errorStream.bufferedReader().use { it.readText() }.trim()
                success = process.waitFor() == 0
            }.onFailure {
                output = it.message ?: it.javaClass.simpleName
            }

            Log.i(TAG, "Root byte command end: success=$success bytes=${bytes.size} output=$output")
            mainHandler.post { callback(success, output, bytes) }
        }.start()
    }

    /**
     * Runs a root command and feeds raw bytes to its stdin. Used for
     * `cat > /data/data/.../<file>` during a restore.
     */
    fun runWithInput(
        command: String,
        input: ByteArray,
        callback: (success: Boolean, output: String) -> Unit
    ) {
        Log.i(TAG, "Root input command start: $command (${input.size} bytes)")

        Thread {
            var success = false
            var output = ""

            runCatching {
                val process = start(command)
                process.outputStream.use { it.write(input) }
                output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                success = process.waitFor() == 0
            }.onFailure {
                output = it.message ?: it.javaClass.simpleName
            }

            Log.i(TAG, "Root input command end: success=$success output=$output")
            mainHandler.post { callback(success, output) }
        }.start()
    }
}
