package com.motolauncher.mod.hooks

import android.view.ViewGroup
import com.motolauncher.mod.ErrorLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Scales the shared notification/control-center window's original system blur. */
internal class ControlCenterNativeBlur {
    private class WindowState(root: Any) {
        val root = WeakReference(root)
        var utility: WeakReference<Any>? = null
        var method: Method? = null
        var requestedRadius = 0
        var opaque = false
        var appliedRadius = 0
    }

    private val windows = WeakHashMap<ViewGroup, WindowState>()
    private var replaying = false
    private var supported = false
    private val reported = mutableSetOf<String>()
    private var percent: () -> Int = { 100 }

    fun install(loader: ClassLoader, backgroundBlurPercent: () -> Int) {
        percent = backgroundBlurPercent
        runCatching {
            val clazz = XposedHelpers.findClass("com.android.systemui.statusbar.BlurUtils", loader)
            val method = clazz.declaredMethods.firstOrNull {
                it.name == "applyBlur" && it.parameterTypes.map { type -> type.name } ==
                    listOf("android.view.ViewRootImpl", "int", "boolean")
            } ?: error("Supported window blur entry point unavailable")
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        if (replaying) return@runCatching
                        val entry = windows.entries.firstOrNull {
                            it.value.root.get() === param.args[0] && it.key.isAttachedToWindow
                        } ?: return@runCatching
                        val state = entry.value
                        val radius = param.args[1] as Int
                        val opaque = param.args[2] as Boolean
                        state.utility = WeakReference(param.thisObject)
                        state.method = method
                        state.requestedRadius = radius
                        state.opaque = opaque
                        val output = ControlCenterBlurPolicy.radius(radius, percent())
                        state.appliedRadius = output
                        // Leave opaque, depth bookkeeping and all other windows untouched.
                        param.args[1] = output
                    }.onFailure { report("intercept native blur", it) }
                }
            })
            supported = true
        }.onFailure { report("install native blur replacement", it) }
    }

    fun attach(panel: ViewGroup) {
        if (!supported) return
        val root = XposedHelpers.callMethod(panel, "getViewRootImpl") ?: return
        windows[panel] = WindowState(root)
    }

    fun update(panel: ViewGroup) {
        val state = windows[panel] ?: return
        applyPending(state, ControlCenterBlurPolicy.radius(state.requestedRadius, percent()))
    }

    fun detach(panel: ViewGroup) {
        windows[panel]?.let {
            applyPending(it, it.requestedRadius)
        }
        windows.remove(panel)
    }

    private fun applyPending(state: WindowState, radius: Int) {
        if (radius == state.appliedRadius) return
        val utility = state.utility?.get() ?: return
        val root = state.root.get() ?: return
        val method = state.method ?: return
        // Reapply once on ownership changes, even if the native spring has stopped.
        // No timers, additional animation or SurfaceControl transactions per idle frame.
        replaying = true
        try {
            method.invoke(utility, root, radius, state.opaque)
            state.appliedRadius = radius
        } finally {
            replaying = false
        }
    }

    private fun report(stage: String, error: Throwable) {
        if (reported.add(stage)) ErrorLogger.log("MotoControlCenterGlass", stage, error)
    }
}
