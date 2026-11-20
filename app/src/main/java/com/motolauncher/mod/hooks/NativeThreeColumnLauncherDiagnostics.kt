package com.motolauncher.mod.hooks

import android.app.ActivityManager.RunningTaskInfo
import android.os.SystemClock
import com.motolauncher.mod.ErrorLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap

/** Read-only launcher-side evidence: a void Shell call does not prove the picker accepted it. */
internal object NativeThreeColumnLauncherDiagnostics {
    private const val TAG = "NativeThreeColumnLauncher"
    private val errors = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var lastCallback = "none"

    fun install(loader: ClassLoader, enabled: () -> Boolean) {
        val listener = Class.forName(
            "com.android.quickstep.util.SplitSelectStateController\$SplitFromDesktopController\$SplitSelectListener",
            false, loader,
        )
        val request = listener.getDeclaredMethod(
            "requestEnterThreeAppsSplitSelectByGesture", arrayOf<RunningTaskInfo>().javaClass,
            Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
        XposedBridge.hookMethod(request, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) = observe("picker response") {
                if (!enabled()) return@observe
                val tasks = (param.args[0] as? Array<*>)?.joinToString(",") { (it as? RunningTaskInfo)?.taskId.toString() }
                val result = if (param.hasThrowable()) param.throwable.javaClass.simpleName else param.result
                lastCallback = "uptime=${SystemClock.uptimeMillis()} tasks=[$tasks] flag=${param.args[1]} code=${param.args[2]} result=$result"
            }
        })
        val service = Class.forName("com.android.quickstep.TouchInteractionService", false, loader)
        val dump = service.getDeclaredMethod("dump", FileDescriptor::class.java, PrintWriter::class.java, Array<String>::class.java)
        XposedBridge.hookMethod(dump, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) = observe("picker dump") {
                if (!param.hasThrowable() && enabled()) (param.args[1] as PrintWriter).println("    $TAG $lastCallback")
            }
        })
    }

    private inline fun observe(event: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) {
            runCatching { if (errors.add(event)) ErrorLogger.log(TAG, event, t) }
        }
    }
}
