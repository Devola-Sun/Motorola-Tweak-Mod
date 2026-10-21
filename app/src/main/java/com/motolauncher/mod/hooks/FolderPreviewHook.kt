package com.motolauncher.mod.hooks

import android.content.Context
import android.util.Log
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.type.java.BooleanType
import com.highcapable.yukihookapi.hook.type.java.FloatType
import com.highcapable.yukihookapi.hook.type.java.IntType
import com.motolauncher.mod.HookConfig
import de.robv.android.xposed.XposedHelpers

/**
 * Folder presentation tweaks.
 *
 * - The folder icon on the workspace previews up to 9 apps in a 3x3 grid.
 * - Opening a folder shows a fixed 4x4 page (16 apps) so the grid fills the
 *   folder width and stays centered on screen instead of shrinking to 4x1.
 *
 * Launcher3's "big folder" preview path already draws a clean 3x3 layout. For
 * ordinary folders the FolderInfo span is temporarily presented as big (2x2)
 * only while the folder icon is drawn, then restored. The opened folder uses
 * its normal FolderInfo span so the workspace cell stays 1x1.
 */
object FolderPreviewHook : YukiBaseHooker() {

    private val is3x3: Boolean
        get() = HookConfig.safeGetBoolean(
            modulePrefs,
            HookConfig.KEY_FOLDER_PREVIEW_3X3,
            HookConfig.DEFAULT_FOLDER_PREVIEW_3X3
        )

    /**
     * Preview icons are drawn into a 3x3 cell grid. The outer margin and the
     * gap between icons are kept equal on both axes (1:1 cells), while the
     * inner gap is slightly tighter than the default so the preview does not
     * look sparse.
     */
    private const val PREVIEW_MARGIN_DP = 4.5f
    private const val PREVIEW_GAP_DP = 3.0f
    private const val TAG = "MotoLauncherMod"

    /** Saved spanX/spanY and the FolderInfo they belong to during preview draw. */
    private val savedDrawInfo = ThreadLocal<Any?>()
    private val savedDrawSpans = ThreadLocal<IntArray?>()
    private val forced3x3Preview = ThreadLocal<Boolean>()

    override fun onHook() {
        // Allow up to 9 preview items instead of 4.
        runCatching {
            findClass("com.android.launcher3.folder.ClippedFolderIconLayoutRule").hook {
                injectMember {
                    method {
                        name = "getMaxNumItemsInPreview"
                        param(BooleanType)
                    }
                    beforeHook {
                        runCatching {
                            if (is3x3) result = 9
                        }
                    }
                }
            }
        }.onFailure {
            Log.w(TAG, "Could not hook ClippedFolderIconLayoutRule", it)
        }

        runCatching {
            findClass("com.android.launcher3.folder.FolderGridOrganizer").hook {
                // FolderGridOrganizer limits the upper-left quadrant to 2x2 for
                // folders with more than 4 items. Let the first 9 items through so
                // the launcher preview can show a 3x3 grid.
                injectMember {
                    method {
                        name = "isItemInPreview"
                        param(IntType, IntType)
                    }
                    beforeHook {
                        runCatching {
                            if (is3x3 && args(1).int() < 9) {
                                result = true
                            }
                        }
                    }
                }

                // The opened folder content is laid out by this organizer. Use a
                // fixed 4x4 page for ordinary (1x1) folders: ranks then fill from
                // the top-left and the page width matches the folder width, which
                // also centers the icons on screen.
                injectMember {
                    method {
                        name = "setContentSize"
                        param(IntType)
                    }
                    afterHook {
                        runCatching {
                            if (!is3x3) return@runCatching

                            val organizer = instance
                            val isBigFolder = runCatching {
                                XposedHelpers.getBooleanField(organizer, "mBigFolder")
                            }.getOrDefault(false)
                            if (isBigFolder) return@runCatching

                            XposedHelpers.setIntField(organizer, "mMaxCountX", 4)
                            XposedHelpers.setIntField(organizer, "mMaxCountY", 4)
                            XposedHelpers.setIntField(organizer, "mCountX", 4)
                            XposedHelpers.setIntField(organizer, "mCountY", 4)
                            XposedHelpers.setIntField(organizer, "mMaxItemsPerPage", 16)
                        }
                    }
                }
            }
        }.onFailure {
            Log.w(TAG, "Could not hook FolderGridOrganizer", it)
        }

        // FolderIcon draws preview icons through PreviewItemManager.draw.
        // The big-folder branch of drawPreviewItem implements the 3x3 layout,
        // so present the folder as big only for the duration of this draw.
        runCatching {
            findClass("com.android.launcher3.folder.PreviewItemManager").hook {
                injectMember {
                    method {
                        name = "draw"
                        param("android.graphics.Canvas")
                    }
                    beforeHook {
                        runCatching {
                            if (!is3x3) return@runCatching

                            val icon = runCatching {
                                XposedHelpers.getObjectField(instance, "mIcon")
                            }.getOrNull() ?: return@runCatching
                            val info = runCatching {
                                XposedHelpers.getObjectField(icon, "mInfo")
                            }.getOrNull() ?: return@runCatching

                            val spanX = runCatching { XposedHelpers.getIntField(info, "spanX") }
                                .getOrDefault(1)
                            val spanY = runCatching { XposedHelpers.getIntField(info, "spanY") }
                                .getOrDefault(1)

                            // Real big folders already use the 3x3 path.
                            if (spanX == 2 || spanY == 2) return@runCatching

                            savedDrawInfo.set(info)
                            savedDrawSpans.set(intArrayOf(spanX, spanY))
                            forced3x3Preview.set(true)
                            XposedHelpers.setIntField(info, "spanX", 2)
                            XposedHelpers.setIntField(info, "spanY", 2)
                        }
                    }
                    afterHook {
                        try {
                            val info = savedDrawInfo.get() ?: return@afterHook
                            val saved = savedDrawSpans.get() ?: return@afterHook
                            runCatching {
                                XposedHelpers.setIntField(info, "spanX", saved[0])
                                XposedHelpers.setIntField(info, "spanY", saved[1])
                            }
                        } finally {
                            forced3x3Preview.remove()
                            savedDrawInfo.remove()
                            savedDrawSpans.remove()
                        }
                    }
                }

                // drawPreviewItem receives the per-cell preview size. Shrinking
                // that size makes each of the nine preview icons slightly smaller
                // without changing the 3x3 grid balance.
                injectMember {
                    method {
                        name = "drawPreviewItem"
                        param(
                            "android.graphics.Canvas",
                            "com.android.launcher3.folder.PreviewItemDrawingParams",
                            "android.graphics.PointF",
                            BooleanType,
                            "android.graphics.Path",
                            IntType,
                            FloatType
                        )
                    }
                    beforeHook {
                        runCatching {
                            if (forced3x3Preview.get() != true) return@runCatching

                            // Build a square 3x3 grid with equal gaps on both axes.
                            // The outer margin stays at the original 4.5dp, while the
                            // gap between icons is reduced to 3dp.
                            val originalCell = args(6).float()
                            val context = runCatching {
                                XposedHelpers.getObjectField(instance, "mContext") as Context
                            }.getOrNull()
                            val density = context?.resources?.displayMetrics?.density ?: 1f
                            val inset = 3f * density
                            val available = 3f * originalCell
                            val margin = PREVIEW_MARGIN_DP * density
                            val gap = PREVIEW_GAP_DP * density
                            val iconSize = (available - 2f * margin - 2f * gap) / 3f
                            if (iconSize <= 0f) return@runCatching

                            // drawPreviewItem uses this gap after we replace its cell
                            // size. The point is shifted so the rendered grid uses the
                            // tighter `gap` instead, while keeping equal outer margins.
                            val methodGap = (available - 3f * iconSize) / 4f

                            val index = args(5).int()
                            val column = index % 3
                            val row = index / 3

                            val shiftX = (margin - 1.5f * inset) + column * gap
                            val shiftY = (margin - methodGap) + row * (gap - methodGap)

                            val point = args(2).any() as? android.graphics.PointF
                            if (point != null) {
                                args[2] = android.graphics.PointF(
                                    point.x + shiftX,
                                    point.y + shiftY
                                )
                            }
                            args[6] = iconSize + inset
                        }
                    }
                }
            }
        }.onFailure {
            Log.w(TAG, "Could not hook PreviewItemManager", it)
        }

        Log.i(TAG, "Folder hooks installed: 3x3 launcher preview, 4x4 opened content")
    }
}
