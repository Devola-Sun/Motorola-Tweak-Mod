package com.motolauncher.mod.hooks

import android.util.Log
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.motolauncher.mod.ErrorLogger
import com.motolauncher.mod.HookConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Opt-in compatibility hooks for reactive and legacy SystemUI clock pipelines.
 * Use SystemUI's own large-clock view/animations, never a second clock overlay.
 * Notification counts, privacy, media sessions and unlock handling remain stock.
 * Reactive clock flows are mapped without changing notification/media state.
 */
object LockClockHook : YukiBaseHooker() {
    private const val TAG = "MotoLauncherMod"
    // KeyguardClockSwitch.LARGE / SMALL in the legacy AOSP pipeline.
    private const val LARGE = 0
    private const val SMALL = 1
    private val reportedErrors = ConcurrentHashMap.newKeySet<String>()
    private val reportedStates = ConcurrentHashMap.newKeySet<String>()

    private val sizeStreams = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val centerStreams = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    private fun registerStream(stream: Any, centered: Boolean, depth: Int = 0) {
        if (depth > 2) return
        val targets = if (centered) centerStreams else sizeStreams
        if (targets.put(stream, true) != null) return
        // ReadonlyStateFlow delegates to StateFlowImpl; collect may be inlined
        // past the read-only facade by the ROM's optimizer.
        stream.javaClass.declaredFields.filter {
            it.type.name in setOf("kotlinx.coroutines.flow.StateFlow", "kotlinx.coroutines.flow.MutableStateFlow",
                "kotlinx.coroutines.flow.StateFlowImpl")
        }.forEach { field ->
            field.isAccessible = true
            field.get(stream)?.let { registerStream(it, centered, depth + 1) }
        }
        if (stream.javaClass.name == "kotlinx.coroutines.flow.StateFlowImpl" && enabled()) {
            // collect() reads the backing state directly on this ROM. A getValue
            // override does not alter those emissions. Seed the real state using
            // its normal setter so subscribers are notified, rather than changing
            // _state directly (which would bypass StateFlow's synchronization).
            val backing = stream.javaClass.getDeclaredField("_state").apply { isAccessible = true }
            val raw = backing.get(stream)
            val replacement = mapValue(raw, centered)
            if (raw != replacement) {
                stream.javaClass.getDeclaredMethod("setValue", Any::class.java)
                    .apply { isAccessible = true }.invoke(stream, replacement)
            }
            Log.i(TAG, "LockClock seeded actual ${if (centered) "center" else "size"} state: " +
                "$raw -> ${backing.get(stream)}")
        }
        Log.i(TAG, "LockClock tracked ${stream.javaClass.name} (${if (centered) "center" else "size"})")
    }

    /** SystemUI's ClockSize enum is loaded by the host, never by this module. */
    private fun mapSize(value: Any?): Any? = runCatching {
        val active = enabled()
        if (reportedStates.add("enabled=$active")) Log.i(TAG, "LockClock preference enabled=$active")
        if (!active || value !is Enum<*> ||
            value.declaringJavaClass.name != "com.android.systemui.keyguard.shared.model.ClockSize") {
            return@runCatching value
        }
        val large = value.declaringJavaClass.enumConstants
            ?.firstOrNull { it.name == "LARGE" } ?: return@runCatching value
        if (value.name != "SMALL" && value.name != "LARGE") return@runCatching value
        if (reportedStates.add("size=${value.name}")) {
            Log.i(TAG, "LockClock reactive emission ${value.javaClass.name}.${value.name} -> LARGE")
        }
        large
    }.onFailure { report("reactive emission", it) }.getOrDefault(value)

    private fun mapValue(value: Any?, centered: Boolean): Any? {
        if (!centered) return mapSize(value)
        return runCatching {
            if (enabled() && value is Boolean) {
                if (reportedStates.add("center=$value")) {
                    Log.i(TAG, "LockClock center emission $value -> true")
                }
                true
            } else value
        }.onFailure { report("center emission", it) }.getOrDefault(value)
    }

    private fun installReactive(): Int {
        // The ROM keeps public final ReadonlyStateFlow fields, with no getters.
        // Intercept only registered clock streams, not all StateFlows or notifications.
        listOf("kotlinx.coroutines.flow.ReadonlyStateFlow", "kotlinx.coroutines.flow.StateFlowImpl").forEach { streamName ->
            runCatching {
                val stream = Class.forName(streamName, false, appClassLoader)
                stream.declaredMethods.filter { it.name == "getValue" && it.parameterCount == 0 }
                    .forEach { method ->
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                runCatching {
                                    if (param.hasThrowable()) return@runCatching
                                    val centered = centerStreams.containsKey(param.thisObject)
                                    if (!centered && !sizeStreams.containsKey(param.thisObject)) return@runCatching
                                    param.result = mapValue(param.result, centered)
                                }.onFailure { report("state value", it) }
                            }
                        })
                    }
                // R8-inlined collectors bypass both collect() and getValue().
                // Change the value at StateFlowImpl's synchronized write boundary
                // instead, and let SystemUI's own collectors receive the update.
                stream.declaredMethods.filter {
                    (it.name == "updateState" || it.name == "compareAndSet") &&
                        it.parameterTypes.contentEquals(arrayOf(Any::class.java, Any::class.java)) ||
                        it.name == "setValue" &&
                        it.parameterTypes.contentEquals(arrayOf(Any::class.java))
                }
                    .forEach { method ->
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                runCatching {
                                    if (!enabled()) return@runCatching
                                    val centered = centerStreams.containsKey(param.thisObject)
                                    if (!centered && !sizeStreams.containsKey(param.thisObject)) return@runCatching
                                    val index = param.args.lastIndex
                                    val raw = param.args[index]
                                    val mapped = mapValue(raw, centered)
                                    param.args[index] = mapped
                                    if (raw != mapped && reportedStates.add("write=${method.name}:$raw")) {
                                        Log.i(TAG, "LockClock actual write ${method.name}: $raw -> $mapped")
                                    }
                                }.onFailure { report("state write", it) }
                            }

                            override fun afterHookedMethod(param: MethodHookParam) {
                                runCatching {
                                    if (param.hasThrowable() || !enabled() || method.name != "updateState") {
                                        return@runCatching
                                    }
                                    val centered = centerStreams.containsKey(param.thisObject)
                                    if (!centered && !sizeStreams.containsKey(param.thisObject)) return@runCatching
                                    val actual = param.thisObject.javaClass.getDeclaredField("_state")
                                        .apply { isAccessible = true }.get(param.thisObject)
                                    if (reportedStates.add("stored=$centered:$actual")) {
                                        Log.i(TAG, "LockClock verified stored ${if (centered) "center" else "size"}=$actual")
                                    }
                                }.onFailure { report("verify state write", it) }
                            }
                        })
                        Log.i(TAG, "LockClock write boundary hooked: $streamName.${method.name}")
                    }
            }.onFailure { report("stream hooks: $streamName", it) }
        }
        var installed = 0
        listOf(
            "com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor",
            "com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel",
            "com.android.systemui.keyguard.ui.viewmodel.ClockViewModel"
        ).forEach { name ->
            runCatching {
                val clazz = Class.forName(name, false, appClassLoader)
                val fields = clazz.declaredFields.filter {
                    it.name in setOf("clockSize", "dynamicClockSize", "clockShouldBeCentered") &&
                        it.type.name == "kotlinx.coroutines.flow.ReadonlyStateFlow"
                }
                if (fields.isNotEmpty()) {
                    XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            runCatching {
                                fields.forEach { field ->
                                    field.isAccessible = true
                                    val value = field.get(param.thisObject) ?: return@forEach
                                    registerStream(value, field.name == "clockShouldBeCentered")
                                    Log.i(TAG, "LockClock registered $name.${field.name}; enabled=${enabled()}")
                                }
                            }.onFailure { report("reactive field: $name", it) }
                        }
                    })
                    installed++
                }
            }.onFailure {
                if (it is ClassNotFoundException) Log.i(TAG, "LockClock optional reactive class absent: $name")
                else report("reactive install: $name", it)
            }
        }
        return installed
    }

    override fun onHook() {
        LockNotificationLayout.install(appClassLoader) { enabled() }
        var installed = installReactive()
        // Prefer the size decision itself: its result also feeds stock positioning.
        runCatching {
            val clazz = Class.forName(
                "com.android.systemui.shade.NotificationPanelViewController", false, appClassLoader
            )
            clazz.declaredMethods.filter {
                it.name == "getClockSize" && it.parameterCount == 0 &&
                    it.returnType == Int::class.javaPrimitiveType
            }.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            if (!enabled() || param.hasThrowable()) return@runCatching
                            if (param.result == SMALL) param.result = LARGE
                        }.onFailure { report("clock size", it) }
                    }
                })
                installed++
            }
        }.onFailure {
            if (it is ClassNotFoundException) Log.i(TAG, "LockClock optional legacy size decision absent")
            else report("legacy size decision", it)
        }

        // Vendor builds may inline/remove getClockSize. Guard the controller
        // boundary too; do not hook View visibility or falsify notification state.
        listOf(
            "com.android.keyguard.KeyguardStatusViewController",
            "com.android.keyguard.KeyguardClockSwitchController"
        ).forEach { name ->
            runCatching {
                val clazz = Class.forName(name, false, appClassLoader)
                clazz.declaredMethods.filter {
                    it.name == "displayClock" && it.parameterTypes.contentEquals(
                        arrayOf(Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                    )
                }.forEach { method ->
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            runCatching {
                                if (!enabled()) return@runCatching
                                if (param.args[0] == SMALL) param.args[0] = LARGE
                            }.onFailure { report("display clock", it) }
                        }
                    })
                    installed++
                }
            }.onFailure {
                if (it is ClassNotFoundException) Log.i(TAG, "LockClock optional legacy class absent: $name")
                else report("legacy controller: $name", it)
            }
        }
        if (installed == 0) {
            report("unsupported", IllegalStateException("No compatible keyguard clock entry points"))
        } else {
            Log.i(TAG, "LockClock hooks installed: $installed; enabled=${enabled()}; layout unverified")
        }
    }

    private fun enabled(): Boolean = HookConfig.safeGetBoolean(
        modulePrefs, HookConfig.KEY_LOCK_KEEP_LARGE_CLOCK, HookConfig.DEFAULT_LOCK_KEEP_LARGE_CLOCK
    )

    private fun report(stage: String, error: Throwable) {
        if (reportedErrors.add(stage)) ErrorLogger.log(TAG, "LockClock $stage", error)
    }
}
