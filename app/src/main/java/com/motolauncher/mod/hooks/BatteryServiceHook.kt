package com.motolauncher.mod.hooks

import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.current
import com.motolauncher.mod.ErrorLogger
import com.motolauncher.mod.HookConfig

/**
 * system_server battery hooks.
 *
 * Motorola's BatteryService already contains an adaptive-charging controller
 * (`MotoAdaptiveCharging`) that accepts an upper/lower charge limit through the
 * Moto health service. Reusing it avoids writing to sysfs ourselves and keeps
 * the vendor's charging safety logic intact.
 *
 * Both hooks are strictly opt-in and wrapped in runCatching; a missing class or
 * field on another ROM simply disables the feature for that process. Every
 * callback body is guarded separately so that a failure can never escape into
 * the battery service thread (a leaked exception there would take down
 * system_server and, with it, the whole Bluetooth stack).
 */
object BatteryServiceHook : YukiBaseHooker() {

    private const val TAG = "BatteryServiceHook"

    private const val BATTERY_SERVICE = "com.android.server.BatteryService"
    private const val ADAPTIVE_CHARGING = "com.android.server.BatteryService\$MotoAdaptiveCharging"

    /** Set once a hook failed, so the error log is not flooded. */
    private val reportedFailures = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    override fun onHook() {
        hookChargeLimit()
        hookOverTemperatureShutdown()
    }

    /**
     * Charge limit: `MotoAdaptiveCharging.readConfig()` is hooked with an
     * afterHook that overwrites the vendor limits (upper = configured value,
     * lower = upper - hysteresis) and reports that a configuration was
     * available, so the vendor code applies them.
     */
    private fun hookChargeLimit() {
        runCatching {
            if (!hasFields(ADAPTIVE_CHARGING, "mUpperLimit", "mLowerLimit")) {
                reportOnce("charge-limit-fields") {
                    "MotoAdaptiveCharging limit fields not found on this firmware, skipped"
                }
                return@runCatching
            }
            findClass(ADAPTIVE_CHARGING).hook {
                injectMember {
                    method {
                        name = "readConfig"
                        emptyParam()
                    }
                    afterHook {
                        runCatching {
                            if (!chargeLimitEnabled()) return@runCatching

                            val upper = HookConfig.coerceChargeLimit(
                                HookConfig.safeGetInt(
                                    modulePrefs,
                                    HookConfig.KEY_BATTERY_CHARGE_LIMIT_VALUE,
                                    HookConfig.DEFAULT_BATTERY_CHARGE_LIMIT_VALUE
                                )
                            )
                            val lower = (upper - CHARGE_LIMIT_HYSTERESIS).coerceAtLeast(0)

                            // Every field write is guarded on its own: if the
                            // vendor class changed, the original result is kept
                            // instead of failing the battery service thread.
                            var applied = false
                            runCatching {
                                instance?.current {
                                    applied =
                                        runCatching {
                                            field { name = "mUpperLimit" }.set(upper)
                                        }.isSuccess &&
                                            runCatching {
                                                field { name = "mLowerLimit" }.set(lower)
                                            }.isSuccess
                                }
                            }.onFailure {
                                reportOnce("charge-limit-write") {
                                    "MotoAdaptiveCharging limit fields could not be written"
                                }
                            }

                            if (applied) {
                                result = true
                            }
                        }
                    }
                }
            }
        }.onFailure {
            ErrorLogger.log(TAG, "MotoAdaptiveCharging charge-limit hook failed", it)
        }
    }

    /**
     * Over-temperature shutdown: the vendor method is skipped while the
     * feature is enabled. The method is only hooked when reflection proves
     * that it returns void, so the null result can never break a primitive
     * return value.
     */
    private fun hookOverTemperatureShutdown() {
        runCatching {
            if (!isVoidMethod(BATTERY_SERVICE, "shutdownIfOverTempLocked")) {
                reportOnce("overheat-method") {
                    "BatteryService.shutdownIfOverTempLocked is not a void method, skipped"
                }
                return@runCatching
            }
            findClass(BATTERY_SERVICE).hook {
                injectMember {
                    method {
                        name = "shutdownIfOverTempLocked"
                        emptyParam()
                    }
                    beforeHook {
                        runCatching {
                            if (overheatShutdownDisabled()) {
                                // Void method: replacing the result with null
                                // skips the original shutdown path.
                                resultNull()
                            }
                        }
                    }
                }
            }
        }.onFailure {
            ErrorLogger.log(TAG, "BatteryService over-temperature hook failed", it)
        }
    }

    private fun chargeLimitEnabled(): Boolean {
        if (!HookConfig.isBootCompleted()) return false
        return HookConfig.safeGetBoolean(
            modulePrefs,
            HookConfig.KEY_BATTERY_CHARGE_LIMIT,
            HookConfig.DEFAULT_BATTERY_CHARGE_LIMIT
        )
    }

    private fun overheatShutdownDisabled(): Boolean {
        if (!HookConfig.isBootCompleted()) return false
        return HookConfig.safeGetBoolean(
            modulePrefs,
            HookConfig.KEY_BATTERY_DISABLE_OVERHEAT_SHUTDOWN,
            HookConfig.DEFAULT_BATTERY_DISABLE_OVERHEAT_SHUTDOWN
        )
    }

    /** True when every named field exists on [className]. */
    private fun hasFields(className: String, vararg names: String): Boolean = runCatching {
        val clazz = Class.forName(className, false, appClassLoader)
        names.all { name -> clazz.declaredFields.any { it.name == name } }
    }.getOrDefault(false)

    /** True when [methodName] exists on [className] and returns void. */
    private fun isVoidMethod(className: String, methodName: String): Boolean = runCatching {
        val clazz = Class.forName(className, false, appClassLoader)
        val methods = clazz.declaredMethods.filter { it.name == methodName }
        methods.isNotEmpty() && methods.all { it.returnType == Void.TYPE }
    }.getOrDefault(false)

    /** Writes a message to the error log only the first time it happens. */
    private fun reportOnce(key: String, message: () -> String) {
        if (reportedFailures.putIfAbsent(key, true) == null) {
            runCatching { ErrorLogger.log(TAG, message()) }
        }
    }

    /** Vendor charging controller expects a small gap between upper and lower limits. */
    private const val CHARGE_LIMIT_HYSTERESIS = 5
}
