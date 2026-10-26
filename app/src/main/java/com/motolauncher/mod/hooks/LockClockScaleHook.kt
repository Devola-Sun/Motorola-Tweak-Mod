package com.motolauncher.mod.hooks

import android.app.KeyguardManager
import android.os.PowerManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.motolauncher.mod.ErrorLogger
import com.motolauncher.mod.HookConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** Enlarge stock faces without changing text sizes, clock-size flows or notification layout. */
internal object LockClockScaleHook : YukiBaseHooker() {
    private const val TAG = "MotoLockClockScale"
    private val errors = ConcurrentHashMap.newKeySet<String>()
    private val states = WeakHashMap<View, State>()
    private var dozing = false

    private class State(view: View) {
        var nativeX = view.scaleX
        var nativeY = view.scaleY
        var factor = 1f
        var applying = false
    }

    override fun onHook() {
        // Preserve SystemUI's native animation values; apply our multiplier exactly once.
        listOf("setScaleX", "setScaleY").forEach { name ->
            runCatching {
                XposedBridge.hookMethod(View::class.java.getDeclaredMethod(name, Float::class.javaPrimitiveType),
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            runCatching {
                                val state = states[param.thisObject as? View] ?: return@runCatching
                                if (state.applying) return@runCatching
                                val native = param.args[0] as Float
                                if (name == "setScaleX") state.nativeX = native else state.nativeY = native
                                if (state.factor != 1f) param.args[0] = native * state.factor
                            }.onFailure { report(name, it) }
                        }
                    })
            }.onFailure { report("install $name", it) }
        }

        hook("com.android.systemui.shared.clocks.ClockRegistry") { clazz ->
            clazz.declaredMethods.filter { it.name == "createCurrentClock" && it.parameterCount == 0 }
                .forEach { method ->
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            runCatching {
                                if (param.hasThrowable()) return@runCatching
                                val controller = param.result ?: return@runCatching
                                listOf("getLargeClock", "getSmallClock").forEach { getter ->
                                    runCatching {
                                        val face = XposedHelpers.callMethod(controller, getter)
                                        val layout = XposedHelpers.callMethod(face, "getLayout")
                                        val views = XposedHelpers.callMethod(layout, "getViews") as? List<*>
                                        views?.filterIsInstance<View>()?.forEach { observe(it) }
                                    }.onFailure { report("face $getter", it) }
                                }
                            }.onFailure { report("clock registry", it) }
                        }
                    })
                }
        }
        hook("com.android.systemui.plugins.clocks.DefaultClockFaceLayout") { clazz ->
            XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        if (!param.hasThrowable()) param.args.filterIsInstance<View>().forEach { observe(it) }
                    }.onFailure { report("default face", it) }
                }
            })
        }
        listOf("com.android.keyguard.AnimatableClockView",
            "com.android.systemui.shared.clocks.AnimatableClockView").forEach { name ->
            hook(name) { clazz ->
                XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            if (!param.hasThrowable()) observe(param.thisObject as View)
                        }.onFailure { report("legacy face", it) }
                    }
                })
            }
        }
        runCatching {
            val inflate = LayoutInflater::class.java.getDeclaredMethod("inflate",
                Int::class.javaPrimitiveType, ViewGroup::class.java, Boolean::class.javaPrimitiveType)
            XposedBridge.hookMethod(inflate, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        if (param.hasThrowable()) return@runCatching
                        val root = param.result as? View ?: return@runCatching
                        if (root.context.packageName == HookConfig.SYSTEM_UI_PACKAGE) discover(root)
                    }.onFailure { report("inflated faces", it) }
                }
            })
        }.onFailure { report("install inflater", it) }
        hook("com.android.systemui.statusbar.StatusBarStateControllerImpl") { clazz ->
            clazz.declaredMethods.filter {
                it.name == "setIsDozing" &&
                    it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
            }.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            if (param.hasThrowable()) return@runCatching
                            dozing = param.args[0] as Boolean
                            states.keys.toList().forEach { update(it) }
                        }.onFailure { report("doze state", it) }
                    }
                })
            }
        }
    }

    private fun discover(view: View) {
        val id = if (view.id == View.NO_ID) "" else
            runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
        if (LockClockScalePolicy.isFace(id, view.javaClass.name)) observe(view)
        else if (view is ViewGroup) for (index in 0 until view.childCount) discover(view.getChildAt(index))
    }

    private fun observe(view: View) {
        if (states.containsKey(view)) return
        states[view] = State(view)
        val listener = ViewTreeObserver.OnPreDrawListener {
            runCatching { update(view) }.onFailure { report("update face", it) }
            true
        }
        val attachment = object : View.OnAttachStateChangeListener {
            private var observer: ViewTreeObserver? = null
            override fun onViewAttachedToWindow(v: View) {
                runCatching {
                    observer = v.viewTreeObserver
                    observer?.addOnPreDrawListener(listener)
                    update(v)
                }.onFailure { report("attach face", it) }
            }

            override fun onViewDetachedFromWindow(v: View) {
                runCatching {
                    observer?.let { if (it.isAlive) it.removeOnPreDrawListener(listener) }
                    observer = null
                    states[v]?.let { apply(v, it, 1f) }
                }.onFailure { report("detach face", it) }
            }
        }
        view.addOnAttachStateChangeListener(attachment)
        if (view.isAttachedToWindow) attachment.onViewAttachedToWindow(view)
    }

    private fun update(view: View) {
        val state = states[view] ?: return
        // Legacy/factory discovery can identify a child as well as its face container.
        // Only the outer face is enlarged, so the multiplier never compounds.
        val nested = generateSequence(view.parent) { it.parent }
            .any { it is View && states.containsKey(it) }
        val percent = HookConfig.safeGetInt(modulePrefs,
            HookConfig.KEY_LOCK_CLOCK_SIZE_PERCENT, HookConfig.DEFAULT_LOCK_CLOCK_SIZE_PERCENT)
        val factor = if (nested || percent == HookConfig.DEFAULT_LOCK_CLOCK_SIZE_PERCENT) 1f else
            LockClockScalePolicy.factor(percent,
                view.context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true,
                dozing, view.context.getSystemService(PowerManager::class.java)?.isInteractive == true)
        apply(view, state, factor)
    }

    private fun apply(view: View, state: State, factor: Float) {
        if (state.factor == factor) return
        val previous = state.factor
        state.applying = true
        try {
            view.scaleX = state.nativeX * factor
            view.scaleY = state.nativeY * factor
            state.factor = factor
        } catch (error: Throwable) {
            runCatching {
                view.scaleX = state.nativeX * previous
                view.scaleY = state.nativeY * previous
            }.onFailure { report("restore scale", it) }
            throw error
        } finally {
            state.applying = false
        }
    }

    private fun hook(name: String, install: (Class<*>) -> Unit) {
        runCatching { install(Class.forName(name, false, appClassLoader)) }.onFailure {
            if (it is ClassNotFoundException) Log.i(TAG, "Optional clock entry absent: $name")
            else report("install $name", it)
        }
    }

    private fun report(stage: String, error: Throwable) {
        if (errors.add(stage)) ErrorLogger.log(TAG, stage, error)
    }
}
