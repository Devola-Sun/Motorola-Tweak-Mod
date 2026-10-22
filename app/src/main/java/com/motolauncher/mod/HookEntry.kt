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
import de.robv.android.xposed.XposedBridge

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

    private fun logInfo(message: String) {
        runCatching { Log.i(MODULE_TAG, message) }
        runCatching { XposedBridge.log("[$MODULE_TAG] $message") }
    }

    private fun logWarn(message: String, error: Throwable? = null) {
        runCatching { Log.w(MODULE_TAG, message, error) }
        runCatching {
            if (error != null) {
                XposedBridge.log("[$MODULE_TAG] $message: $error")
                XposedBridge.log(error)
            } else {
                XposedBridge.log("[$MODULE_TAG] $message")
            }
        }
    }

    override fun onInit() {
        YukiHookAPI.configs {
            // Keep per-callback debug output off: BubbleTextView calls this
            // hook very frequently, and verbose logging would flood logcat.
            isDebug = false
            isEnableHookSharedPreferences = true
            debugLog {
                tag = MODULE_TAG
            }
        }
    }

    override fun onHook() {
        YukiHookAPI.encase {
            loadApp(name = LauncherHooker.TARGET_PACKAGE) {
                runCatching {
                    // Only skip explicit secondary processes (e.g. ":appprediction").
                    // Note: in VectorLegacyBridge or early package load, YukiHookAPI
                    // may populate processName with "android" when lpparam.processName
                    // is null, so never require processName == TARGET_PACKAGE here.
                    if (processName.startsWith("${LauncherHooker.TARGET_PACKAGE}:")) {
                        return@loadApp
                    }
                    logScopeMarker(LauncherHooker.TARGET_PACKAGE)
                    val prefs = runCatching { prefs(HookConfig.PREFS_NAME) }.getOrNull()

                    runCatching {
                        logInfo(
                            "Settings loaded: hide_labels=${HookConfig.safeGetBoolean(prefs, HookConfig.KEY_HIDE_LABELS, HookConfig.DEFAULT_HIDE_LABELS)}, " +
                                "icon_shape=${HookConfig.safeGetString(prefs, HookConfig.KEY_ICON_SHAPE, HookConfig.DEFAULT_ICON_SHAPE)}, " +
                                "grid=${HookConfig.safeGetInt(prefs, HookConfig.KEY_GRID_COLUMNS, HookConfig.DEFAULT_GRID_COLUMNS)}" +
                                "x${HookConfig.safeGetInt(prefs, HookConfig.KEY_GRID_ROWS, HookConfig.DEFAULT_GRID_ROWS)}, " +
                                "dock=${HookConfig.safeGetInt(prefs, HookConfig.KEY_DOCK_ICONS, HookConfig.DEFAULT_DOCK_ICONS)}, " +
                                "coverDock=${HookConfig.isCoverDockEnabled(prefs)}"
                        )
                    }

                    runCatching { LauncherHooker.install(this, prefs) }.onFailure {
                        logWarn("LauncherHooker could not be installed", it)
                    }
                    runCatching { loadHooker(CoverDockHook) }.onFailure {
                        logWarn("CoverDockHook could not be loaded", it)
                    }
                    runCatching { loadHooker(FolderPreviewHook) }.onFailure {
                        logWarn("FolderPreviewHook could not be loaded", it)
                    }
                    runCatching { loadHooker(WidgetResizeHook) }.onFailure {
                        logWarn("WidgetResizeHook could not be loaded", it)
                    }
                    runCatching { FlagSecureFrameworkHook.hook(this) }.onFailure {
                        logWarn("FlagSecureFrameworkHook could not be loaded", it)
                    }
                }.onFailure {
                    logWarn("Failed in loadApp(${LauncherHooker.TARGET_PACKAGE})", it)
                }
            }

            loadApp(name = HookConfig.SYSTEM_UI_PACKAGE) {
                runCatching {
                    // Only hook the real SystemUI process; do not inject into other
                    // packages (like com.moto.dragdrop) that merely load SystemUI's
                    // APK resources via createPackageContext. Note that YukiHookAPI
                    // uses "android" as the fallback when lpparam.processName is null
                    // (e.g. under VectorLegacyBridge), so allow "android" as well.
                    if (processName.isNotEmpty() &&
                        processName != "android" &&
                        processName != HookConfig.SYSTEM_UI_PACKAGE
                    ) {
                        return@loadApp
                    }
                    logScopeMarker(HookConfig.SYSTEM_UI_PACKAGE)
                    val prefs = runCatching { prefs(HookConfig.PREFS_NAME) }.getOrNull()
                    runCatching {
                        logInfo(
                            "SystemUI settings loaded: seconds=${HookConfig.safeGetBoolean(prefs, HookConfig.KEY_SB_SHOW_SECONDS, HookConfig.DEFAULT_SB_SHOW_SECONDS)}, " +
                                "hideBatteryPercent=${HookConfig.safeGetBoolean(prefs, HookConfig.KEY_SB_HIDE_BATTERY_PERCENT, HookConfig.DEFAULT_SB_HIDE_BATTERY_PERCENT)}, " +
                                "hideVolte=${HookConfig.safeGetBoolean(prefs, HookConfig.KEY_SB_HIDE_VOLTE, HookConfig.DEFAULT_SB_HIDE_VOLTE)}, " +
                                "iosIcons=${HookConfig.safeGetBoolean(prefs, HookConfig.KEY_SB_IOS_ICONS, HookConfig.DEFAULT_SB_IOS_ICONS)}, " +
                                "padding=${HookConfig.safeGetBoolean(prefs, HookConfig.KEY_SB_ENABLE_CUSTOM_PADDING, HookConfig.DEFAULT_SB_ENABLE_CUSTOM_PADDING)}, " +
                                "swapSliders=${HookConfig.safeGetBoolean(prefs, HookConfig.KEY_CC_SWAP_SLIDERS, HookConfig.DEFAULT_CC_SWAP_SLIDERS)}, " +
                                "iosConnectivity=${HookConfig.safeGetBoolean(prefs, HookConfig.KEY_CC_IOS_CONNECTIVITY, HookConfig.DEFAULT_CC_IOS_CONNECTIVITY)}"
                        )
                    }

                    runCatching { loadHooker(StatusBarHooks) }.onFailure {
                        logWarn("StatusBarHooks could not be loaded", it)
                    }
                    runCatching { loadHooker(ControlCenterHooks) }.onFailure {
                        logWarn("ControlCenterHooks could not be loaded", it)
                    }
                    runCatching { FlagSecureFrameworkHook.hook(this) }.onFailure {
                        logWarn("FlagSecureFrameworkHook could not be loaded", it)
                    }
                }.onFailure {
                    logWarn("Failed in loadApp(${HookConfig.SYSTEM_UI_PACKAGE})", it)
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
                    val safeMode = if (isReferenceDevice) {
                        HookConfig.DEFAULT_SAFE_MODE
                    } else {
                        val prefs = runCatching { prefs(HookConfig.PREFS_NAME) }.getOrNull()
                        HookConfig.safeGetBoolean(
                            prefs,
                            HookConfig.KEY_SAFE_MODE,
                            HookConfig.DEFAULT_SAFE_MODE
                        )
                    }

                    val frameworkHooksAllowed = !safeMode || isReferenceDevice
                    if (!frameworkHooksAllowed) {
                        val reason =
                            "Safe mode: framework hooks skipped on non-razr-fold device " +
                                "(${android.os.Build.MODEL})"
                        logInfo(reason)
                        runCatching { ErrorLogger.log(MODULE_TAG, reason) }
                        return@loadSystem
                    }

                    logInfo("System framework settings loaded: isReferenceDevice=$isReferenceDevice, safeMode=$safeMode")

                    runCatching { FlagSecureFrameworkHook.hook(this) }.onFailure {
                        logWarn("FlagSecureFrameworkHook could not be loaded", it)
                    }
                    runCatching { CorePatchHooker.hook(this) }.onFailure {
                        logWarn("CorePatchHooker could not be loaded", it)
                    }
                    runCatching { loadHooker(CameraHook) }.onFailure {
                        logWarn("CameraHook could not be loaded", it)
                    }
                    runCatching { loadHooker(BatteryServiceHook) }.onFailure {
                        logWarn("BatteryServiceHook could not be loaded", it)
                    }
                }.onFailure {
                    logWarn("Failed in loadSystem", it)
                }
            }
        }
    }
}
