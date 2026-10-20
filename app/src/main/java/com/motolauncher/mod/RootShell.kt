package com.motolauncher.mod

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.TimeUnit

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
                val process = ProcessBuilder("su", "-M", "-c", command)
                    .redirectErrorStream(true)
                    .start()

                if (process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    exitCode = process.exitValue()
                    output = process.inputStream.bufferedReader().use { it.readText() }.trim()
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
                val process = ProcessBuilder("su", "-M", "-c", command).start()
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
                val process = ProcessBuilder("su", "-M", "-c", command)
                    .redirectErrorStream(true)
                    .start()
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
