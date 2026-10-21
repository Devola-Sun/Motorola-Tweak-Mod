package com.motolauncher.mod

import android.util.Log
import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import com.motolauncher.mod.hooks.BatteryServiceHook
import com.motolauncher.mod.hooks.CameraHook
import com.motolauncher.mod.hooks.ControlCenterHooks
import com.motolauncher.mod.hooks.CoverDockHook
import com.motolauncher.mod.hooks.CorePatchHooker
import com.motolauncher.mod.hooks.FlagSecureFrameworkHook
import com.motolauncher.mod.hooks.FolderPreviewHook
import com.motolauncher.mod.hooks.StatusBarHooks
import com.motolauncher.mod.hooks.WidgetResizeHook

/**
 * YukiHookAPI entry point.
 *
 * The KSP processor (`com.highcapable.yukihookapi:ksp-xposed`) reads this
 * annotation and generates both `assets/xposed_init` and the real Xposed entry
 * class at compile time.
 */
@InjectYukiHookWithXposed
object HookEntry : IYukiHookXposedInit {

    private const val MODULE_TAG = "MotoLauncherMod"

    /**
     * Dedicated tag for the scope markers. The launcher and the system
     * framework log a lot of lines, so the markers would be pushed out of the
     * logcat window when they share a tag with the verbose output - the
     * settings app would then show a working scope as inactive.
     */
    private const val SCOPE_TAG = "MotoLauncherModScope"

    /** Writes a scope marker that [com.motolauncher.mod.ScopeDetector] reads. */
    private fun logScopeMarker(packageName: String) {
        runCatching {
            Log.i(SCOPE_TAG, "scope=$packageName")
        }
    }

    override fun onInit() {
        YukiHookAPI.configs {
            // Keep per-callback debug output off: BubbleTextView calls this
            // hook very frequently, and verbose logging would flood logcat.
            isDebug = false
            debugLog {
                tag = MODULE_TAG
            }
        }
    }

    override fun onHook() {
        YukiHookAPI.encase {
            loadApp(name = LauncherHooker.TARGET_PACKAGE) {
                logScopeMarker(LauncherHooker.TARGET_PACKAGE)
                // Read the same bridge that MainActivity writes to. LSPosed's
                // New XSharedPreferences makes the module prefs visible inside
                // the launcher process.
                val prefs = prefs(HookConfig.PREFS_NAME)

                runCatching {
                    Log.i(
                        MODULE_TAG,
                        "Settings loaded: hide_labels=${prefs.getBoolean(HookConfig.KEY_HIDE_LABELS, HookConfig.DEFAULT_HIDE_LABELS)}, " +
                            "icon_shape=${prefs.getString(HookConfig.KEY_ICON_SHAPE, HookConfig.DEFAULT_ICON_SHAPE)}, " +
                            "grid=${prefs.getInt(HookConfig.KEY_GRID_COLUMNS, HookConfig.DEFAULT_GRID_COLUMNS)}" +
                            "x${prefs.getInt(HookConfig.KEY_GRID_ROWS, HookConfig.DEFAULT_GRID_ROWS)}, " +
                            "dock=${prefs.getInt(HookConfig.KEY_DOCK_ICONS, HookConfig.DEFAULT_DOCK_ICONS)}"
                    )
                }.onFailure {
                    Log.w(MODULE_TAG, "Could not read shared preferences", it)
                }

                LauncherHooker.install(this, prefs)
                loadHooker(CoverDockHook)
                loadHooker(FolderPreviewHook)
                loadHooker(WidgetResizeHook)
                FlagSecureFrameworkHook.hook(this)
            }

            loadApp(name = HookConfig.SYSTEM_UI_PACKAGE) {
                logScopeMarker(HookConfig.SYSTEM_UI_PACKAGE)
                val prefs = prefs(HookConfig.PREFS_NAME)
                runCatching {
                    Log.i(
                        MODULE_TAG,
                        "SystemUI settings loaded: seconds=${prefs.getBoolean(HookConfig.KEY_SB_SHOW_SECONDS, HookConfig.DEFAULT_SB_SHOW_SECONDS)}, " +
                            "hideBatteryPercent=${prefs.getBoolean(HookConfig.KEY_SB_HIDE_BATTERY_PERCENT, HookConfig.DEFAULT_SB_HIDE_BATTERY_PERCENT)}, " +
                            "hideVolte=${prefs.getBoolean(HookConfig.KEY_SB_HIDE_VOLTE, HookConfig.DEFAULT_SB_HIDE_VOLTE)}, " +
                            "iosIcons=${prefs.getBoolean(HookConfig.KEY_SB_IOS_ICONS, HookConfig.DEFAULT_SB_IOS_ICONS)}, " +
                            "padding=${prefs.getBoolean(HookConfig.KEY_SB_ENABLE_CUSTOM_PADDING, HookConfig.DEFAULT_SB_ENABLE_CUSTOM_PADDING)}, " +
                            "swapSliders=${prefs.getBoolean(HookConfig.KEY_CC_SWAP_SLIDERS, HookConfig.DEFAULT_CC_SWAP_SLIDERS)}, " +
                            "iosConnectivity=${prefs.getBoolean(HookConfig.KEY_CC_IOS_CONNECTIVITY, HookConfig.DEFAULT_CC_IOS_CONNECTIVITY)}"
                    )
                }.onFailure {
                    Log.w(MODULE_TAG, "Could not read SystemUI prefs", it)
                }

                loadHooker(StatusBarHooks)
                loadHooker(ControlCenterHooks)
                FlagSecureFrameworkHook.hook(this)
            }

            // System framework ("android" / system_server). CorePatch-style
            // installer policy bypass and the FLAG_SECURE removal live here so
            // every app benefits.
            loadSystem {
                logScopeMarker(HookConfig.FRAMEWORK_PACKAGE)
                val prefs = prefs(HookConfig.PREFS_NAME)
                val safeMode = runCatching {
                    prefs.getBoolean(HookConfig.KEY_SAFE_MODE, HookConfig.DEFAULT_SAFE_MODE)
                }.getOrDefault(HookConfig.DEFAULT_SAFE_MODE)

                // Safe mode: on devices other than the razr fold the framework
                // flags can crash system_server / cause a boot loop, so the
                // framework hooks are only installed on the reference device.
                val frameworkHooksAllowed = !safeMode || HookConfig.isRazrFoldDevice()
                if (!frameworkHooksAllowed) {
                    val reason =
                        "Safe mode: framework hooks skipped on non-razr-fold device " +
                            "(${android.os.Build.MODEL})"
                    Log.i(MODULE_TAG, reason)
                    runCatching { ErrorLogger.log(MODULE_TAG, reason) }
                    return@loadSystem
                }

                runCatching {
                    Log.i(
                        MODULE_TAG,
                        "System framework settings loaded: " +
                            "disableFlagSecure=${prefs.getBoolean(HookConfig.KEY_DISABLE_FLAG_SECURE, HookConfig.DEFAULT_DISABLE_FLAG_SECURE)}, " +
                            "disableCameraSound=${prefs.getBoolean(HookConfig.KEY_DISABLE_CAMERA_SOUND, HookConfig.DEFAULT_DISABLE_CAMERA_SOUND)}, " +
                            "bypassSignatures=${prefs.getBoolean(HookConfig.KEY_COREPATCH_BYPASS_SIGNATURES, HookConfig.DEFAULT_COREPATCH_BYPASS_SIGNATURES)}, " +
                            "allowDowngrade=${prefs.getBoolean(HookConfig.KEY_COREPATCH_ALLOW_DOWNGRADE, HookConfig.DEFAULT_COREPATCH_ALLOW_DOWNGRADE)}, " +
                            "bypassDigest=${prefs.getBoolean(HookConfig.KEY_COREPATCH_BYPASS_DIGEST, HookConfig.DEFAULT_COREPATCH_BYPASS_DIGEST)}, " +
                            "safeMode=$safeMode"
                    )
                }.onFailure {
                    Log.w(MODULE_TAG, "Could not read system framework prefs", it)
                }

                FlagSecureFrameworkHook.hook(this)
                CorePatchHooker.hook(this)
                runCatching { loadHooker(CameraHook) }.onFailure {
                    Log.w(MODULE_TAG, "CameraHook could not be loaded", it)
                }
                runCatching { loadHooker(BatteryServiceHook) }.onFailure {
                    Log.w(MODULE_TAG, "BatteryServiceHook could not be loaded", it)
                }
            }
        }
    }
}
