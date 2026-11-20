package com.motolauncher.mod.hooks

import android.app.AlertDialog
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import com.motolauncher.mod.R

/** Main-thread UI only. The Shell handler owns selection and revalidates every click. */
internal class NativeThreeColumnPicker(
    private val context: Context,
    private val failure: (Throwable) -> Unit,
) {
    data class Choice(val taskId: Int, val label: String, val packageName: String)

    private val main = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null

    @Suppress("DEPRECATION") // Match the privileged SystemUI window context used by InnerScreenSnapHook.
    fun show(title: Int, choices: List<Choice>, distinguish: Set<Int>, select: (Int) -> Unit, cancel: () -> Unit) {
        main.post {
            try {
                closeOnMain()
                val type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                val display = context.getSystemService(DisplayManager::class.java)
                    ?.getDisplay(Display.DEFAULT_DISPLAY) ?: error("Default display unavailable")
                val windowContext = context.createDisplayContext(display).createWindowContext(type, null)
                val strings = windowContext.moduleStringResources()
                val next = AlertDialog.Builder(windowContext)
                    .setTitle(strings.getString(title))
                    .setItems(choices.map {
                        if (it.taskId in distinguish) strings.getString(R.string.three_column_window_label, it.label, it.taskId)
                        else it.label
                    }.toTypedArray()) { _, index ->
                        try { choices.getOrNull(index)?.let { select(it.taskId) } }
                        catch (t: Throwable) { failure(t) }
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        try { cancel() } catch (t: Throwable) { failure(t) }
                    }
                    .create()
                next.setOnCancelListener {
                    try { cancel() } catch (t: Throwable) { failure(t) }
                }
                checkNotNull(next.window).setType(type)
                dialog = next
                next.show()
            } catch (t: Throwable) {
                try { closeOnMain() } catch (dismissError: Throwable) { failure(dismissError) }
                failure(t)
                try { cancel() } catch (callbackError: Throwable) { failure(callbackError) }
            }
        }
    }

    fun close() {
        main.post { try { closeOnMain() } catch (t: Throwable) { failure(t) } }
    }

    private fun closeOnMain() {
        val current = dialog ?: return
        dialog = null
        current.setOnCancelListener(null)
        current.dismiss()
    }
}
