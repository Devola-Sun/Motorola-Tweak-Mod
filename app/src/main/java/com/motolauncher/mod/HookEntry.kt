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
                runCatching {
                    // YukiHookAPI falls back to "android" when it cannot
                    // resolve the real process name, so an equality check
                    // would skip the launcher process itself. Only secondary
                    // processes ("pkg:sub") are left out.
                    if (processName.contains(":")) {
                        return@loadApp
                    }
                    logScopeMarker(LauncherHooker.TARGET_PACKAGE)
                    val prefs = runCatching { prefs(HookConfig.PREFS_NAME) }.getOrNull()

                    // The log is written unconditionally (not only after the
                    // boot completed) so the settings app can always read the
                    // scope state from logcat.
                    runCatching {
                        Log.i(
                            MODULE_TAG,
                            "Settings loaded: hide_labels=${HookConfig.safeGetBoolean(prefs, HookConfig.KEY_HIDE_LABELS, HookConfig.DEFAULT_HIDE_LABELS)}, " +
                                "icon_shape=${HookConfig.safeGetString(prefs, HookConfig.KEY_ICON_SHAPE, HookConfig.DEFAULT_ICON_SHAPE)}, " +
                                "grid=${HookConfig.safeGetInt(prefs, HookConfig.KEY_GRID_COLUMNS, HookConfig.DEFAULT_GRID_COLUMNS)}" +
                                "x${HookConfig.safeGetInt(prefs, HookConfig.KEY_GRID_ROWS, HookConfig.DEFAULT_GRID_ROWS)}, " +
                                "dock=${HookConfig.safeGetInt(prefs, HookConfig.KEY_DOCK_ICONS, HookConfig.DEFAULT_DOCK_ICONS)}, " +
                                "bootCompleted=${HookConfig.isBootCompleted()}"
                        )
                    }

                    runCatching { LauncherHooker.install(this, prefs) }.onFailure {
                        Log.w(MODULE_TAG, "LauncherHooker could not be installed", it)
                    }
                    runCatching { loadHooker(CoverDockHook) }.onFailure {
                        Log.w(MODULE_TAG, "CoverDockHook could not be loaded", it)
                    }
                    runCatching { loadHooker(FolderPreviewHook) }.onFailure {
                        Log.w(MODULE_TAG, "FolderPreviewHook could not be loaded", it)
                    }
                    runCatching { loadHooker(WidgetResizeHook) }.onFailure {
                        Log.w(MODULE_TAG, "WidgetResizeHook could not be loaded", it)
                    }
                    runCatching { FlagSecureFrameworkHook.hook(this) }.onFailure {
                        Log.w(MODULE_TAG, "FlagSecureFrameworkHook could not be loaded", it)
                    }
                }
            }

            loadApp(name = HookConfig.SYSTEM_UI_PACKAGE) {
                runCatching {
                    // Only hook the real SystemUI process; do not inject into other
                    // packages (like com.moto.dragdrop) that merely load SystemUI's
                    // APK resources via createPackageContext.
                    // YukiHookAPI reports "android" as the process name when it
                    // cannot resolve it, so only secondary processes
                    // ("pkg:sub") are excluded here.
                    if (processName.contains(":")) {
                        return@loadApp
                    }
                    logScopeMarker(HookConfig.SYSTEM_UI_PACKAGE)
                    val prefs = runCatching { prefs(HookConfig.PREFS_NAME) }.getOrNull()

                    // Written unconditionally so the scope state is visible in
                    // logcat even while the device is still booting.
                    runCatching {
                        Log.i(
                            MODULE_TAG,
                            "SystemUI settings loaded: seconds=${HookConfig.safeGetBoolean(prefs, HookConfig.KEY_SB_SHOW_SECONDS, HookConfig.DEFAULT_SB_SHOW_SECONDS)}, " +
                                "hideBatteryPercent=${HookConfig.safeGetBoolean(prefs, HookConfig.KEY_SB_HIDE_BATTERY_PERCENT, HookConfig.DEFAULT_SB_HIDE_BATTERY_PERCENT)}, " +
                                "hideVolte=${HookConfig.safeGetBoolean(prefs, HookConfig.KEY_SB_HIDE_VOLTE, HookConfig.DEFAULT_SB_HIDE_VOLTE)}, " +
                                "iosIcons=${HookConfig.safeGetBoolean(prefs, HookConfig.KEY_SB_IOS_ICONS, HookConfig.DEFAULT_SB_IOS_ICONS)}, " +
                                "padding=${HookConfig.safeGetBoolean(prefs, HookConfig.KEY_SB_ENABLE_CUSTOM_PADDING, HookConfig.DEFAULT_SB_ENABLE_CUSTOM_PADDING)}, " +
                                "swapSliders=${HookConfig.safeGetBoolean(prefs, HookConfig.KEY_CC_SWAP_SLIDERS, HookConfig.DEFAULT_CC_SWAP_SLIDERS)}, " +
                                "iosConnectivity=${HookConfig.safeGetBoolean(prefs, HookConfig.KEY_CC_IOS_CONNECTIVITY, HookConfig.DEFAULT_CC_IOS_CONNECTIVITY)}, " +
                                "bootCompleted=${HookConfig.isBootCompleted()}"
                        )
                    }

                    runCatching { loadHooker(StatusBarHooks) }.onFailure {
                        Log.w(MODULE_TAG, "StatusBarHooks could not be loaded", it)
                    }
                    runCatching { loadHooker(ControlCenterHooks) }.onFailure {
                        Log.w(MODULE_TAG, "ControlCenterHooks could not be loaded", it)
                    }
                    runCatching { FlagSecureFrameworkHook.hook(this) }.onFailure {
                        Log.w(MODULE_TAG, "FlagSecureFrameworkHook could not be loaded", it)
                    }
                }
            }

            // System framework ("android" / system_server). CorePatch-style
            // installer policy bypass and the FLAG_SECURE removal live here so
            // every app benefits.
            loadSystem {
                runCatching {
                    logScopeMarker(HookConfig.FRAMEWORK_PACKAGE)

                    // Safe mode: on devices other than the razr fold the framework
                    // flags can crash system_server / cause a boot loop, so the
                    // framework hooks are only installed on the reference device.
                    // Note: at system_server startBootstrapServices time (especially
                    // during soft reboot), LSPosedBridge has not yet received its
                    // service binder, so do not force XSharedPreferences loading on
                    // the reference device during bootstrap.
                    val isReferenceDevice = HookConfig.isRazrFoldDevice()

                    // On the reference device the prefs are not read during
                    // the bootstrap (LSPosedBridge has no binder yet), so the
                    // framework log falls back to the defaults there.
                    val frameworkPrefs =
                        if (isReferenceDevice) {
                            null
                        } else {
                            runCatching { prefs(HookConfig.PREFS_NAME) }.getOrNull()
                        }

                    val safeMode = if (isReferenceDevice) {
                        HookConfig.DEFAULT_SAFE_MODE
                    } else {
                        HookConfig.safeGetBoolean(
                            frameworkPrefs,
                            HookConfig.KEY_SAFE_MODE,
                            HookConfig.DEFAULT_SAFE_MODE
                        )
                    }

                    // Written unconditionally so the scope state is visible in
                    // logcat even while the device is still booting.
                    runCatching {
                        Log.i(
                            MODULE_TAG,
                            "System framework settings loaded: safeMode=$safeMode, " +
                                "disableFlagSecure=${HookConfig.safeGetBoolean(frameworkPrefs, HookConfig.KEY_DISABLE_FLAG_SECURE, HookConfig.DEFAULT_DISABLE_FLAG_SECURE)}, " +
                                "disableCameraSound=${HookConfig.safeGetBoolean(frameworkPrefs, HookConfig.KEY_DISABLE_CAMERA_SOUND, HookConfig.DEFAULT_DISABLE_CAMERA_SOUND)}, " +
                                "corepatchBypassSignatures=${HookConfig.safeGetBoolean(frameworkPrefs, HookConfig.KEY_COREPATCH_BYPASS_SIGNATURES, HookConfig.DEFAULT_COREPATCH_BYPASS_SIGNATURES)}, " +
                                "corepatchAllowDowngrade=${HookConfig.safeGetBoolean(frameworkPrefs, HookConfig.KEY_COREPATCH_ALLOW_DOWNGRADE, HookConfig.DEFAULT_COREPATCH_ALLOW_DOWNGRADE)}, " +
                                "corepatchBypassDigest=${HookConfig.safeGetBoolean(frameworkPrefs, HookConfig.KEY_COREPATCH_BYPASS_DIGEST, HookConfig.DEFAULT_COREPATCH_BYPASS_DIGEST)}, " +
                                "bootCompleted=${HookConfig.isBootCompleted()}"
                        )
                    }

                    val frameworkHooksAllowed = !safeMode || isReferenceDevice
                    if (!frameworkHooksAllowed) {
                        val reason =
                            "Safe mode: framework hooks skipped on non-razr-fold device " +
                                "(${android.os.Build.MODEL})"
                        Log.i(MODULE_TAG, reason)
                        runCatching { ErrorLogger.log(MODULE_TAG, reason) }
                        return@loadSystem
                    }

                    runCatching { FlagSecureFrameworkHook.hook(this) }.onFailure {
                        Log.w(MODULE_TAG, "FlagSecureFrameworkHook could not be loaded", it)
                    }
                    runCatching { CorePatchHooker.hook(this) }.onFailure {
                        Log.w(MODULE_TAG, "CorePatchHooker could not be loaded", it)
                    }
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
}
