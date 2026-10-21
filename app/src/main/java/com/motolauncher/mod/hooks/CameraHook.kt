package com.motolauncher.mod.hooks

import android.util.Log
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.type.java.IntType
import com.motolauncher.mod.ErrorLogger
import com.motolauncher.mod.HookConfig

/**
 * Camera shutter sound suppression for the system framework ("android" /
 * system_server) process.
 *
 * On a Japanese ROM the camera shutter sound is enforced by the audio policy:
 * the "system" usage is forced with
 * `AudioSystem.setForceUse(AudioSystem.FOR_SYSTEM,
 * AudioSystem.FORCE_SYSTEM_ENFORCED)`, so the shutter sound is routed to the
 * `AUDIO_STREAM_ENFORCED_AUDIBLE` stream, which the user can neither mute nor
 * lower.
 *
 * (`dumpsys media.audio_policy` shows `Force use for system: 11` and the
 * `STRATEGY_ENFORCED_AUDIBLE` strategy while the sound is enforced.)
 *
 * The module releases that forced configuration while the settings toggle is
 * on:
 * - `AudioSystem#setForceUse(int, int)`: a forced system configuration is
 *   rewritten to `FORCE_NONE` before it reaches the audio policy
 * - `AudioSystem#setForceUse(FOR_SYSTEM, FORCE_NONE)` is called directly, so
 *   the configuration that is already active is released as well (the audio
 *   policy applies it after the module was loaded, so the call is repeated a
 *   few times)
 * - `MediaActionSound#mustPlayShutterSound()` reports "no shutter sound
 *   needed", so nothing re-enables the enforcement
 * - `AudioService#isCameraSoundForced()` reports "not forced" (safety net for
 *   camera apps that use the Camera1 API)
 *
 * Safety rules:
 * - every lookup and callback body is wrapped in runCatching, so a missing
 *   method on another firmware can never take system_server down
 * - while the toggle is off the original arguments / result are passed through
 *   untouched
 */
object CameraHook : YukiBaseHooker() {

    /** Entry used by HookEntry to install this hooker into a scope. */
    fun hook(scope: PackageParam) = scope.loadHooker(this)

    private const val TAG = "MotoLauncherMod"

    /** The audio service of the system framework. */
    private const val AUDIO_SERVICE = "com.android.server.audio.AudioService"

    /** The audio system of the framework. */
    private const val AUDIO_SYSTEM = "android.media.AudioSystem"

    /** The sound player of the framework (shutter sound policy). */
    private const val MEDIA_ACTION_SOUND = "android.media.MediaActionSound"

    /** `android.media.AudioSystem.FOR_SYSTEM` */
    private const val FOR_SYSTEM = 4

    /** `android.media.AudioSystem.FORCE_SYSTEM_ENFORCED` */
    private const val FORCE_SYSTEM_ENFORCED = 11

    /** `android.media.AudioSystem.FORCE_NONE` */
    private const val FORCE_NONE = 0

    /** Delays (ms) of the repeated release of the forced configuration. */
    private val RELEASE_DELAYS = longArrayOf(0L, 2_000L, 5_000L, 10_000L, 20_000L, 30_000L, 60_000L)

    override fun onHook() {
        runCatching { hookSetForceUse() }
            .onFailure { ErrorLogger.log(TAG, "Camera: setForceUse hook failed", it) }
        runCatching { hookMustPlayShutterSound() }
            .onFailure { ErrorLogger.log(TAG, "Camera: mustPlayShutterSound hook failed", it) }
        runCatching { hookCameraSoundForced() }
            .onFailure { ErrorLogger.log(TAG, "Camera: shutter sound hook failed", it) }
        runCatching { hookAudioServiceInit() }
            .onFailure { ErrorLogger.log(TAG, "Camera: audio service hook failed", it) }
        runCatching { startReleaseWorker() }
            .onFailure { ErrorLogger.log(TAG, "Camera: release worker failed", it) }

        Log.i(TAG, "Camera shutter sound hook installed")
    }

    /**
     * `AudioSystem#setForceUse(int usage, int config)`: the enforced system
     * configuration is turned back into "none" while the toggle is on.
     */
    private fun hookSetForceUse() {
        runCatching {
            findClass(AUDIO_SYSTEM).hook {
                injectMember {
                    method {
                        name = "setForceUse"
                        param(IntType, IntType)
                    }
                    beforeHook {
                        runCatching {
                            if (!HookConfig.isBootCompleted()) return@runCatching
                            if (!HookConfig.isCameraSoundDisabled(modulePrefs)) {
                                return@runCatching
                            }
                            val usage = args.getOrNull(0) as? Int ?: return@runCatching
                            val config = args.getOrNull(1) as? Int ?: return@runCatching
                            if (usage == FOR_SYSTEM && config == FORCE_SYSTEM_ENFORCED) {
                                // Release the enforced system configuration so
                                // the camera shutter sound is not forced.
                                args[1] = FORCE_NONE
                            }
                        }
                    }
                }
            }
        }.onFailure {
            ErrorLogger.log(TAG, "Camera: could not hook $AUDIO_SYSTEM#setForceUse", it)
        }
    }

    /**
     * `MediaActionSound#mustPlayShutterSound()` always reports "no shutter
     * sound needed" while the toggle is on, so the platform does not
     * re-enable the enforced configuration.
     */
    private fun hookMustPlayShutterSound() {
        runCatching {
            findClass(MEDIA_ACTION_SOUND).hook {
                injectMember {
                    method {
                        name = "mustPlayShutterSound"
                        emptyParam()
                    }
                    afterHook {
                        runCatching {
                            if (!HookConfig.isBootCompleted()) return@runCatching
                            if (!HookConfig.isCameraSoundDisabled(modulePrefs)) {
                                return@runCatching
                            }
                            result = false
                        }
                    }
                }
            }
        }.onFailure {
            ErrorLogger.log(
                TAG,
                "Camera: could not hook $MEDIA_ACTION_SOUND#mustPlayShutterSound",
                it
            )
        }
    }

    /**
     * `AudioService#isCameraSoundForced()` always reports "not forced" while
     * the toggle is on.
     */
    private fun hookCameraSoundForced() {
        runCatching {
            findClass(AUDIO_SERVICE).hook {
                injectMember {
                    method {
                        name = "isCameraSoundForced"
                        emptyParam()
                    }
                    afterHook {
                        runCatching {
                            if (!HookConfig.isBootCompleted()) return@runCatching
                            if (!HookConfig.isCameraSoundDisabled(modulePrefs)) {
                                return@runCatching
                            }
                            result = false
                            releaseForSystemForceUse()
                        }
                    }
                }
            }
        }.onFailure {
            ErrorLogger.log(
                TAG,
                "Camera: could not hook $AUDIO_SERVICE#isCameraSoundForced",
                it
            )
        }
    }

    /**
     * The audio service is created while the system_server starts, which is
     * the first moment at which the audio policy is reachable - the forced
     * configuration is released right after it was created.
     */
    private fun hookAudioServiceInit() {
        runCatching {
            findClass(AUDIO_SERVICE).hook {
                injectMember {
                    allConstructors()
                    afterHook {
                        runCatching {
                            if (!HookConfig.isBootCompleted()) return@runCatching
                            if (!HookConfig.isCameraSoundDisabled(modulePrefs)) return@runCatching
                            releaseForSystemForceUse()
                        }
                    }
                }
            }
        }.onFailure {
            ErrorLogger.log(TAG, "Camera: could not hook $AUDIO_SERVICE constructor", it)
        }
    }

    /**
     * Releases the forced configuration repeatedly, because the camera HAL
     * applies it after the system_server has started.
     *
     * The worker is a short living daemon thread; every call is wrapped in
     * runCatching, so a failure can never take system_server down.
     */
    private fun startReleaseWorker() {
        Thread {
            RELEASE_DELAYS.forEach { delay ->
                if (delay > 0L) {
                    runCatching { Thread.sleep(delay) }.onFailure { return@Thread }
                }
                runCatching {
                    if (!HookConfig.isBootCompleted()) return@runCatching
                    if (!HookConfig.isCameraSoundDisabled(modulePrefs)) return@runCatching
                    releaseForSystemForceUse()
                }
            }
        }.apply {
            name = "moto-launcher-camera-release"
            isDaemon = true
            start()
        }
    }

    /**
     * Calls `AudioSystem.setForceUse(FOR_SYSTEM, FORCE_NONE)`.
     *
     * @return true when the audio policy accepted the call.
     */
    private fun releaseForSystemForceUse(): Boolean = runCatching {
        val clazz = Class.forName(AUDIO_SYSTEM, false, appClassLoader)
        val method = clazz.getMethod(
            "setForceUse",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        val result = method.invoke(null, FOR_SYSTEM, FORCE_NONE) as? Int ?: -1
        if (result == 0) {
            Log.i(TAG, "Camera: released the enforced system audio configuration")
        } else {
            Log.w(TAG, "Camera: setForceUse returned $result")
        }
        result == 0
    }.getOrElse {
        ErrorLogger.log(TAG, "Camera: could not release the enforced camera sound", it)
        false
    }
}
