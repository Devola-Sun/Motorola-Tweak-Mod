package com.motolauncher.mod.hooks

import android.util.Log
import android.view.View
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.motolauncher.mod.HookConfig
import de.robv.android.xposed.XposedHelpers

/**
 * Removes Launcher3's widget resize restrictions.
 *
 * The stock resize frame only activates an axis when the widget provider
 * declares RESIZE_HORIZONTAL/VERTICAL and only allows the provider's
 * min/max spans. For a normal 1x1 workspace we expose all four handles and
 * allow any span from 1 cell up to the current grid size.
 */
object WidgetResizeHook : YukiBaseHooker() {

    private const val TAG = "MotoLauncherMod"

    private val enabled: Boolean
        get() = HookConfig.safeGetBoolean(
            modulePrefs,
            HookConfig.KEY_WIDGET_FREE_RESIZE,
            HookConfig.DEFAULT_WIDGET_FREE_RESIZE
        )

    override fun onHook() {
        runCatching {
            findClass("com.android.launcher3.AppWidgetResizeFrame").hook {
                injectMember {
                    method {
                        name = "setupForWidget"
                        paramCount = 3
                    }
                    afterHook {
                        runCatching {
                            if (!enabled) return@runCatching

                            val frame = instance
                            val widgetView = runCatching {
                                XposedHelpers.getObjectField(frame, "mWidgetView")
                            }.getOrNull() ?: return@runCatching

                            // Widget stacks have their own resize contract; keep their
                            // original behavior.
                            val inStack = runCatching {
                                XposedHelpers.callMethod(widgetView, "isInStack") as? Boolean
                            }.getOrNull() ?: false
                            if (inStack) return@runCatching

                            val cellLayout = runCatching {
                                XposedHelpers.getObjectField(frame, "mCellLayout")
                            }.getOrNull() ?: return@runCatching
                            val countX = runCatching {
                                XposedHelpers.callMethod(cellLayout, "getCountX") as? Int
                            }.getOrNull() ?: return@runCatching
                            val countY = runCatching {
                                XposedHelpers.callMethod(cellLayout, "getCountY") as? Int
                            }.getOrNull() ?: return@runCatching

                            XposedHelpers.setIntField(frame, "mMinHSpan", 1)
                            XposedHelpers.setIntField(frame, "mMaxHSpan", countX)
                            XposedHelpers.setIntField(frame, "mMinVSpan", 1)
                            XposedHelpers.setIntField(frame, "mMaxVSpan", countY)
                            XposedHelpers.setBooleanField(frame, "mHorizontalResizeActive", true)
                            XposedHelpers.setBooleanField(frame, "mVerticalResizeActive", true)

                            val handles = runCatching {
                                XposedHelpers.getObjectField(frame, "mDragHandles") as? Array<*>
                            }.getOrNull()
                            handles?.forEach { handle ->
                                (handle as? View)?.visibility = View.VISIBLE
                            }

                            Log.i(TAG, "Widget resize limits removed: ${countX}x$countY")
                        }
                    }
                }
            }
        }.onFailure {
            Log.w(TAG, "Could not hook AppWidgetResizeFrame", it)
        }
    }
}
