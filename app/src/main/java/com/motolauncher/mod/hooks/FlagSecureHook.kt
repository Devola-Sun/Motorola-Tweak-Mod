package com.motolauncher.mod.hooks

import android.util.Log
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.motolauncher.mod.ErrorLogger
import com.motolauncher.mod.HookConfig

/**
 * Allows screenshots / screen recording of FLAG_SECURE windows when enabled.
 *
 * Screenshots are captured by SystemUI through android.window.ScreenCapture.
 * Setting CaptureArgs.mCaptureSecureLayers to true and making
 * ScreenshotHardwareBuffer.containsSecureLayers() return false removes the
 * secure-layer block for the system screenshot / recording path.
 */
object FlagSecureHook : YukiBaseHooker() {

    private const val TAG = "MotoLauncherMod"

    private val enabled: Boolean
        get() = modulePrefs.getBoolean(
            HookConfig.KEY_DISABLE_FLAG_SECURE,
            HookConfig.DEFAULT_DISABLE_FLAG_SECURE
        )

    override fun onHook() {
        runCatching {
            findClass("android.window.ScreenCapture").hook {
                injectMember {
                    method {
                        name = "nativeCaptureDisplay"
                        paramCount = 2
                    }
                    beforeHook {
                        if (enabled) markCaptureSecure(args)
                    }
                }
                injectMember {
                    method {
                        name = "nativeCaptureLayers"
                        paramCount = 3
                    }
                    beforeHook {
                        if (enabled) markCaptureSecure(args)
                    }
                }
            }
        }.onFailure {
            ErrorLogger.log(TAG, "Failed to hook ScreenCapture", it)
        }

        runCatching {
            findClass("android.window.ScreenCapture\$ScreenshotHardwareBuffer").hook {
                injectMember {
                    method {
                        name = "containsSecureLayers"
                        emptyParam()
                    }
                    afterHook {
                        if (enabled) result = false
                    }
                }
            }
        }.onFailure {
            ErrorLogger.log(TAG, "Failed to hook ScreenshotHardwareBuffer", it)
        }

        Log.i(TAG, "FlagSecure hooks installed")
    }

    private fun markCaptureSecure(args: Array<Any?>) {
        val captureArgs = args.getOrNull(0) ?: return
        var type: Class<*>? = captureArgs.javaClass
        while (type != null) {
            val field = runCatching {
                type.getDeclaredField("mCaptureSecureLayers")
            }.getOrNull()
            if (field != null) {
                runCatching {
                    field.isAccessible = true
                    field.setBoolean(captureArgs, true)
                }
                return
            }
            type = type.superclass
        }
    }
}
