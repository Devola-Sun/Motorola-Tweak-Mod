package com.motolauncher.mod.hooks

import android.util.Log
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.motolauncher.mod.ErrorLogger
import com.motolauncher.mod.HookConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** Change shade presentation only; Ranking, channels, DND and alerting are untouched. */
object SilentNotificationHook : YukiBaseHooker() {
    private const val TAG = "MotoSilentNotification"
    private val sections = SilentNotificationSections()
    private val errors = ConcurrentHashMap.newKeySet<String>()
    private var loggedPromotion = false
    private val shadeLimits = WeakHashMap<Any, SilentNotificationShadeLimit>()
    private val hookedUpdaters = mutableSetOf<Class<*>>()

    private fun enabled() = HookConfig.safeGetBoolean(modulePrefs,
        HookConfig.KEY_NOTIFICATION_KEEP_SILENT_VISIBLE, HookConfig.DEFAULT_NOTIFICATION_KEEP_SILENT_VISIBLE)

    override fun onHook() {
        installShadeLimitHook()
        runCatching {
            val coordinator = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.collection.coordinator.RankingCoordinator", appClassLoader)
            XposedBridge.hookAllConstructors(coordinator, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        if (!param.hasThrowable()) registerSections(param.thisObject)
                    }.onFailure { report("register sections", it) }
                }
            })
            // Also catch coordinators created before hook installation and ROMs
            // that finish initializing their sectioners after construction.
            XposedBridge.hookAllMethods(coordinator, "attach", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching { registerSections(param.thisObject) }
                        .onFailure { report("register sections at attach", it) }
                }
            })
        }.onFailure { report("install section hooks (stock behavior retained)", it) }

        // Install independently: an unavailable coordinator must not prevent the
        // presentation hook from loading, or vice versa.
        runCatching {
            val row = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", appClassLoader)
            XposedBridge.hookAllMethods(row, "setIsMinimized", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        if (enabled() && param.args.firstOrNull() == true) param.args[0] = false
                    }.onFailure { report("row presentation", it) }
                }
            })
        }.onFailure { report("install row hook (stock behavior retained)", it) }
    }

    private fun installShadeLimitHook() {
        runCatching {
            val stack = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout", appClassLoader)
            XposedBridge.hookAllConstructors(stack, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        if (param.hasThrowable()) return@runCatching
                        val host = param.thisObject
                        shadeLimits.getOrPut(host) { SilentNotificationShadeLimit() }
                        // R8 inlines updateChildren into this per-stack pre-draw
                        // listener. Apply the limit before the native algorithm,
                        // not after its view states have already been generated.
                        val updater = XposedHelpers.getObjectField(host, "mChildrenUpdater") ?: return@runCatching
                        if (updater.javaClass in hookedUpdaters) return@runCatching
                        val preDraw = updater.javaClass.getDeclaredMethod("onPreDraw")
                        XposedBridge.hookMethod(preDraw, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                runCatching {
                                    val owner = XposedHelpers.getObjectField(param.thisObject, "this$0")
                                        ?: return@runCatching
                                    updateShadeLimit(owner)
                                }.onFailure { report("shade limit before layout", it) }
                            }
                        })
                        hookedUpdaters.add(updater.javaClass)
                    }.onFailure { report("register shade limit", it) }
                }
            })
            listOf("requestChildrenUpdate", "updateContentHeight", "onMeasure", "setExpandedHeight").forEach { name ->
                XposedBridge.hookAllMethods(stack, name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching { updateShadeLimit(param.thisObject) }
                            .onFailure { report("shade limit $name", it) }
                    }
                })
            }
        }.onFailure { report("install shade limit", it) }
    }

    private fun updateShadeLimit(host: Any) {
        val policy = shadeLimits[host] ?: return
        val ambient = XposedHelpers.getObjectField(host, "mAmbientState") ?: return
        val current = XposedHelpers.getIntField(host, "mMaxDisplayedNotifications")
        val replacement = policy.update(
            current = current,
            enabled = enabled(),
            statusBarState = XposedHelpers.getIntField(ambient, "mStatusBarState"),
            shadeExpanded = XposedHelpers.getBooleanField(ambient, "mShadeExpanded"),
            dozing = XposedHelpers.getBooleanField(ambient, "mDozing")
        )
        if (replacement != current) {
            XposedHelpers.setIntField(host, "mMaxDisplayedNotifications", replacement)
            Log.i(TAG, "Shade notification limit $current -> $replacement")
        }
    }

    private fun registerSections(coordinator: Any) {
        val minimized = XposedHelpers.getObjectField(coordinator, "mMinimizedNotifSectioner") ?: return
        val silent = XposedHelpers.getObjectField(coordinator, "mSilentNotifSectioner") ?: return
        sections.register(minimized, silent) { membership ->
            XposedBridge.hookMethod(membership, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        if (!enabled() || param.hasThrowable()) return@runCatching
                        val stockResult = param.result as? Boolean ?: return@runCatching
                        val result = sections.route(param.thisObject, stockResult) { original, originalMembership ->
                            // Ask the ROM's original predicate, not an importance heuristic.
                            XposedBridge.invokeOriginalMethod(originalMembership, original, param.args) == true
                        }
                        if (result != stockResult) param.result = result
                        if (result && !stockResult && !loggedPromotion) {
                            loggedPromotion = true
                            Log.i(TAG, "Minimized notification retained as silent card")
                        }
                    }.onFailure { report("section membership", it) }
                }
            })
            Log.i(TAG, "Hooked section predicate ${membership.declaringClass.name}; enabled=${enabled()}")
        }
    }

    private fun report(stage: String, error: Throwable) {
        if (errors.add(stage)) ErrorLogger.log(TAG, stage, error)
    }
}
