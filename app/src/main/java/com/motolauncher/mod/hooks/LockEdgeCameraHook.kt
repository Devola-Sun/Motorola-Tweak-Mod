package com.motolauncher.mod.hooks

import android.app.KeyguardManager
import android.content.Intent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.motolauncher.mod.ErrorLogger
import com.motolauncher.mod.HookConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import kotlin.math.abs

/** Right-edge left-swipe camera shortcut, restricted to the keyguard. */
object LockEdgeCameraHook : YukiBaseHooker() {
    private const val TAG = "MotoLauncherMod"
    private data class Gesture(val startX: Float, val startY: Float, var triggered: Boolean = false)
    // dispatchTouchEvent is invoked by nested ViewGroups for the same event;
    // share one gesture state to avoid launching the camera more than once.
    private var gesture: Gesture? = null

    override fun onHook() {
        runCatching {
            XposedBridge.hookAllMethods(ViewGroup::class.java, "dispatchTouchEvent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching { handle(param) }.onFailure {
                        ErrorLogger.log(TAG, "Lock edge camera gesture failed", it)
                    }
                }
            })
        }.onFailure { ErrorLogger.log(TAG, "Lock edge camera hook install failed", it) }
    }

    private fun handle(param: XC_MethodHook.MethodHookParam) {
        if (!HookConfig.safeGetBoolean(modulePrefs, HookConfig.KEY_LOCK_EDGE_CAMERA,
                HookConfig.DEFAULT_LOCK_EDGE_CAMERA)) return
        val view = param.thisObject as? View ?: return
        val event = param.args.firstOrNull() as? MotionEvent ?: return
        val context = view.context
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked != true) {
            gesture = null
            return
        }
        val edge = 24f * context.resources.displayMetrics.density
        val threshold = 96f * context.resources.displayMetrics.density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesture = if (event.rawX >= context.resources.displayMetrics.widthPixels - edge)
                    Gesture(event.rawX, event.rawY) else null
            }
            MotionEvent.ACTION_MOVE -> {
                val current = gesture ?: return
                val dx = current.startX - event.rawX
                val dy = abs(event.rawY - current.startY)
                if (dx >= threshold && dx > dy * 1.5f && !current.triggered) {
                    current.triggered = true
                    val intent = Intent("android.media.action.STILL_IMAGE_CAMERA_SECURE").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    }
                    context.startActivity(intent)
                }
                if (current.triggered) param.result = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val completed = gesture
                gesture = null
                if (completed?.triggered == true) param.result = true
            }
        }
    }
}
