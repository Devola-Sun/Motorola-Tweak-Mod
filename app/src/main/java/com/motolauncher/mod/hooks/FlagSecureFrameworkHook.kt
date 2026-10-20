package com.motolauncher.mod.hooks

import android.os.SystemClock
import android.util.Log
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.motolauncher.mod.ErrorLogger
import com.motolauncher.mod.HookConfig
import java.lang.reflect.Method

/**
 * FLAG_SECURE (WindowManager.LayoutParams.FLAG_SECURE = 0x2000) removal for the
 * system framework ("android" / system_server) process.
 *
 * Server side (affects every window on the device):
 * - `WindowState.isSecureLocked()` always reports "not secure"
 * - `SurfaceControl` / `SurfaceControl.Builder` / `SurfaceControl.Transaction`
 *   `setSecure(...)` is forced to `false`, so the layer is never marked secure
 *   in SurfaceFlinger (this also covers `adb shell screencap` / screenrecord)
 *
 * Client side:
 * - `Window.setFlags` / `Window.addFlags` drop the FLAG_SECURE bit so an app
 *   can never mark its own window secure
 *
 * Every lookup is wrapped in runCatching (a missing method on a future
 * firmware must never crash system_server) and the hooks only take effect
 * while the settings toggle is on.
 */
object FlagSecureFrameworkHook : YukiBaseHooker() {

    /** Entry used by HookEntry to install this hooker into a scope. */
    fun hook(scope: PackageParam) = scope.loadHooker(this)

    private const val TAG = "MotoLauncherMod"

    /** WindowManager.LayoutParams.FLAG_SECURE */
    private const val FLAG_SECURE = 0x2000

    /** Prefs are re-read at most this often; window callbacks are frequent. */
    private const val PREFS_TTL_MS = 1000L

    @Volatile
    private var cachedEnabled = HookConfig.DEFAULT_DISABLE_FLAG_SECURE

    @Volatile
    private var cachedAt = 0L

    private val enabled: Boolean
        get() {
            val now = SystemClock.uptimeMillis()
            if (now - cachedAt >= PREFS_TTL_MS) {
                cachedAt = now
                cachedEnabled = runCatching {
                    modulePrefs.getBoolean(
                        HookConfig.KEY_DISABLE_FLAG_SECURE,
                        HookConfig.DEFAULT_DISABLE_FLAG_SECURE
                    )
                }.getOrDefault(HookConfig.DEFAULT_DISABLE_FLAG_SECURE)
            }
            return cachedEnabled
        }

    override fun onHook() {
        runCatching { hookWindowState() }
            .onFailure { ErrorLogger.log(TAG, "FlagSecure: WindowState hook failed", it) }
        runCatching { hookSurfaceControl() }
            .onFailure { ErrorLogger.log(TAG, "FlagSecure: SurfaceControl hook failed", it) }
        runCatching { hookWindowFlags() }
            .onFailure { ErrorLogger.log(TAG, "FlagSecure: Window flags hook failed", it) }

        Log.i(TAG, "FlagSecure framework hooks installed")
    }

    private fun findMethods(className: String, methodName: String): List<Method> = runCatching {
        Class.forName(className, false, appClassLoader)
            .declaredMethods
            .filter { it.name == methodName }
    }.getOrDefault(emptyList())

    /** server: WindowState.isSecureLocked() -> false */
    private fun hookWindowState() {
        runCatching {
            if (findMethods("com.android.server.wm.WindowState", "isSecureLocked").isEmpty()) {
                ErrorLogger.log(TAG, "FlagSecure: isSecureLocked not found on this firmware, skipped")
                return@runCatching
            }
            findClass("com.android.server.wm.WindowState").hook {
                injectMember {
                    method {
                        name = "isSecureLocked"
                        paramCount(0..2)
                    }.all()
                    afterHook {
                        if (enabled) result = false
                    }
                }
            }
        }.onFailure { ErrorLogger.log(TAG, "FlagSecure: isSecureLocked hook failed", it) }
    }

    /** server: SurfaceControl*.setSecure(...) -> false (layer never secure) */
    private fun hookSurfaceControl() {
        listOf(
            "android.view.SurfaceControl",
            "android.view.SurfaceControl\$Builder",
            "android.view.SurfaceControl\$Transaction"
        ).forEach { className ->
            runCatching {
                if (findMethods(className, "setSecure").isEmpty()) {
                    ErrorLogger.log(TAG, "FlagSecure: setSecure not found in $className, skipped")
                    return@runCatching
                }
                findClass(className).hook {
                    injectMember {
                        method {
                            name = "setSecure"
                            paramCount(1..3)
                        }.all()
                        beforeHook {
                            if (!enabled) return@beforeHook
                            args.forEachIndexed { index, value ->
                                if (value is Boolean) args[index] = false
                            }
                        }
                    }
                }
            }.onFailure { ErrorLogger.log(TAG, "FlagSecure: setSecure hook failed in $className", it) }
        }
    }

    /** client: Window.setFlags / addFlags can never set FLAG_SECURE */
    private fun hookWindowFlags() {
        runCatching {
            findClass("android.view.Window").hook {
                injectMember {
                    method {
                        name = "setFlags"
                        paramCount = 2
                    }.all()
                    beforeHook {
                        if (!enabled) return@beforeHook
                        val flags = args.getOrNull(0) as? Int ?: return@beforeHook
                        if (flags and FLAG_SECURE != 0) {
                            args[0] = flags and FLAG_SECURE.inv()
                        }
                    }
                }
                injectMember {
                    method {
                        name = "addFlags"
                        paramCount = 1
                    }.all()
                    beforeHook {
                        if (!enabled) return@beforeHook
                        val flags = args.getOrNull(0) as? Int ?: return@beforeHook
                        if (flags and FLAG_SECURE != 0) {
                            args[0] = flags and FLAG_SECURE.inv()
                        }
                    }
                }
            }
        }.onFailure { ErrorLogger.log(TAG, "FlagSecure: Window flags hook failed", it) }
    }
}
