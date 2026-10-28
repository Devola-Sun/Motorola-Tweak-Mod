package com.motolauncher.mod.hooks

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.motolauncher.mod.ErrorLogger
import com.motolauncher.mod.HookConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** GradientDrawable compatibility preserves FolderAnimationManager's color/alpha animations. */
object FolderGlassHook : YukiBaseHooker() {
    private val reported = mutableSetOf<String>()
    private val previews = WeakHashMap<Any, PreviewPaints>()
    private val pageCells = WeakHashMap<View, Pair<Int, Int>>()

    private fun enabled() = HookConfig.safeGetBoolean(modulePrefs, HookConfig.KEY_FOLDER_LIQUID_GLASS, false)

    override fun onHook() {
        hookNativeFolderBlur()
        hookSquareContainer()
        listOf("onFinishInflate", "onAttachedToWindow", "onLayout").forEach { methodName ->
            runCatching {
                findClass("com.android.launcher3.folder.Folder").hook {
                    injectMember {
                        method { name = methodName; superClass() }.all()
                        afterHook {
                            runCatching {
                                val folder = instance as? View ?: return@runCatching
                                if (folder.javaClass.name != "com.android.launcher3.folder.Folder") return@runCatching
                                if (!enabled()) return@runCatching
                                val utilities = XposedHelpers.findClass("com.android.launcher3.Utilities", appClassLoader)
                                val fullscreen = XposedHelpers.callStaticMethod(utilities, "isFullscreenFolderEnabled") == true
                                val background = XposedHelpers.getObjectField(folder, "mBackground") as? GradientDrawable
                                if (!fullscreen && background != null && background !is FolderDrawable) {
                                    XposedHelpers.setObjectField(folder, "mBackground", FolderDrawable(folder, background))
                                }
                                // Fullscreen dispatchDraw never draws mBackground. Its content's
                                // stock background is permanently alpha=0 until a drag starts.
                                // Give that actual drawing host its own visible glass container.
                                val content = XposedHelpers.getObjectField(folder, "mContent") as? View
                                val contentBackground = content?.background as? GradientDrawable
                                if (fullscreen && content != null && contentBackground != null && contentBackground !is FolderDrawable) {
                                    val padding = intArrayOf(content.paddingLeft, content.paddingTop,
                                        content.paddingRight, content.paddingBottom)
                                    content.background = FolderDrawable(content, contentBackground, fullscreenContainer = true)
                                    content.setPadding(padding[0], padding[1], padding[2], padding[3])
                                }
                            }.onFailure { report("update folder", it) }
                        }
                    }
                }
            }.onFailure { report("hook folder $methodName", it) }
        }
        runCatching {
            findClass("com.android.launcher3.folder.PreviewBackground").hook {
                injectMember {
                    method { name = "drawBackground"; param(Canvas::class.java) }
                    beforeHook {
                        runCatching {
                            if (!enabled()) return@runCatching
                            val canvas = args[0] as? Canvas ?: return@runCatching
                            val preview = instance
                            val host = XposedHelpers.getObjectField(preview, "mInvalidateDelegate") as? View
                                ?: return@runCatching
                            val x = (XposedHelpers.callMethod(preview, "getOffsetX") as Number).toFloat()
                            val y = (XposedHelpers.callMethod(preview, "getOffsetY") as Number).toFloat()
                            val radius = (XposedHelpers.callMethod(preview, "getScaledRadius") as Number).toFloat()
                            val ratio = (XposedHelpers.callMethod(preview, "getVRatio") as Number).toFloat()
                            if (radius <= 0f || ratio <= 0f) return@runCatching
                            val big = XposedHelpers.getBooleanField(preview, "mBigFolder")
                            val shapeClass = XposedHelpers.findClass("com.android.launcher3.graphics.IconShape",
                                preview.javaClass.classLoader)
                            val shape = XposedHelpers.callStaticMethod(shapeClass, "getShape", big)
                            val paints = previews.getOrPut(preview) { PreviewPaints() }
                            paints.update(x, y, radius, ratio, host)
                            // Keep the native silhouette, including big-folder aspect ratio.
                            XposedHelpers.callMethod(shape, "drawShape", canvas, x, y, radius, paints.body, ratio)
                            XposedHelpers.callMethod(shape, "drawShape", canvas, x, y, radius, paints.reflection, ratio)
                            XposedHelpers.callMethod(shape, "drawShape", canvas, x, y, radius, paints.rim, ratio)
                            result = null
                        }.onFailure { report("draw folder preview", it) }
                    }
                }
            }
        }.onFailure { report("hook folder preview", it) }
        runCatching {
            findClass("com.android.launcher3.folder.PreviewBackground").hook {
                injectMember {
                    method { name = "drawBackgroundStroke"; param(Canvas::class.java) }
                    beforeHook {
                        runCatching {
                            // All-apps folders otherwise paint an additional stock fill over glass.
                            if (enabled()) result = null
                        }.onFailure { report("skip stock folder overlay", it) }
                    }
                }
            }
        }.onFailure { report("hook stock folder overlay", it) }
    }

    private fun hookSquareContainer() {
        runCatching {
            val folderClass = XposedHelpers.findClass("com.android.launcher3.folder.Folder", appClassLoader)
            XposedBridge.hookAllMethods(folderClass, "getContentHeight", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        if (!enabled()) return@runCatching
                        val utilities = XposedHelpers.findClass("com.android.launcher3.Utilities", appClassLoader)
                        if (XposedHelpers.callStaticMethod(utilities, "isFullscreenFolderEnabled") != true) return@runCatching
                        val profile = param.args.firstOrNull() ?: return@runCatching
                        val inv = XposedHelpers.getObjectField(profile, "inv")
                        val columns = (XposedHelpers.callStaticMethod(profile.javaClass,
                            "getFolderColumns", inv) as Number).toInt()
                        param.result = XposedHelpers.getIntField(profile, "folderCellWidthPx") * columns +
                            2 * XposedHelpers.getIntField(profile, "folderContentPaddingLeftRight")
                    }.onFailure { report("size square folder", it) }
                }
            })
        }.onFailure { report("hook square folder height", it) }
        runCatching {
            // FolderPagedView does NOT declare onMeasure on this ROM. Hook its
            // actual implementation, not a finder-selected ancestor such as View.
            val pagedView = XposedHelpers.findClass("com.android.launcher3.PagedView", appClassLoader)
            val measure = pagedView.getDeclaredMethod("onMeasure", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType)
            XposedBridge.hookMethod(measure, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val content = param.thisObject as? ViewGroup ?: return@runCatching
                        if (content.javaClass.name != "com.android.launcher3.folder.FolderPagedView") return@runCatching
                        val square = enabled() && (content.background as? FolderDrawable)?.fullscreenContainer == true
                        val width = View.MeasureSpec.getSize(param.args[0] as Int)
                        if (square && width <= 0) return@runCatching
                        // Commit the measure spec before optional vendor grid reflection:
                        // a grid API mismatch must never leave a tall container behind.
                        if (square) param.args[1] = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
                        for (index in 0 until content.childCount) {
                            val page = content.getChildAt(index)
                            runCatching {
                                if (square) {
                                    val original = pageCells.getOrPut(page) {
                                        XposedHelpers.getIntField(page, "mFixedCellWidth") to
                                            XposedHelpers.getIntField(page, "mFixedCellHeight")
                                    }
                                    val rows = XposedHelpers.getIntField(page, "mCountY")
                                    val border = XposedHelpers.getObjectField(page, "mBorderSpace") as android.graphics.Point
                                    val height = FolderGlassPolicy.squareCellHeight(width,
                                        content.paddingTop + content.paddingBottom + page.paddingTop + page.paddingBottom,
                                        rows, border.y)
                                    if (height > 0) XposedHelpers.callMethod(page, "setCellDimensions", original.first, height)
                                } else {
                                    pageCells.remove(page)?.let { original ->
                                        XposedHelpers.callMethod(page, "setCellDimensions", original.first, original.second)
                                    }
                                }
                            }.onFailure { report("fit square folder grid", it) }
                        }
                    }.onFailure { report("measure square folder content", it) }
                }
            })
        }.onFailure { report("hook square folder measurement", it) }
    }

    private fun hookNativeFolderBlur() {
        runCatching {
            val floatingView = XposedHelpers.findClass("com.android.launcher3.AbstractFloatingView", appClassLoader)
            findClass("com.android.launcher3.folder.Folder").hook {
                injectMember {
                    method { name = "updateWindowForBlurs" }.all()
                    beforeHook {
                        runCatching {
                            if (args.size != 3) return@runCatching
                            val progress = args[2] as? Float ?: return@runCatching
                            if (progress == 0f) return@runCatching
                            val caller = args[0] ?: return@runCatching
                            val context = args[1] ?: return@runCatching
                            val folderCaller = caller.javaClass.name.startsWith("com.android.launcher3.folder.")
                            // Launcher also reapplies the effect after resume / blur-strategy changes.
                            // Do not affect widget sheets/stack editors that reuse this same method.
                            val folderOnResume = caller === context &&
                                XposedHelpers.callStaticMethod(floatingView, "getTopOpenViewWithType", context, 1) != null
                            if (folderCaller || folderOnResume) {
                                // Keep the native animation and cleanup. The shade's existing
                                // slider controls this background too, independently of glass.
                                args[2] = FolderGlassPolicy.blurProgress(progress, blurPercent())
                            }
                        }.onFailure { report("scale open-folder blur", it) }
                    }
                }
            }
        }.onFailure { report("hook open-folder blur", it) }
    }

    private fun blurPercent(): Int = HookConfig.safeGetInt(modulePrefs,
        HookConfig.KEY_CC_BACKGROUND_BLUR_PERCENT, HookConfig.DEFAULT_CC_BACKGROUND_BLUR_PERCENT)

    private class FolderDrawable(view: View, private val original: GradientDrawable,
        val fullscreenContainer: Boolean = false) : GradientDrawable() {
        private val host = WeakReference(view)
        private var surface: Drawable? = null
        private var night: Boolean? = null
        private var radius = -1f
        private var sharedBackdrop = false
        private val surfaceBounds = Rect()
        private val stockFillAlpha = Color.alpha(original.color?.defaultColor ?: Color.WHITE)

        init {
            cornerRadius = original.cornerRadius
            color = original.color ?: android.content.res.ColorStateList.valueOf(Color.WHITE)
            alpha = original.alpha
            bounds = original.bounds
        }

        override fun draw(canvas: Canvas) {
            runCatching {
                if (!enabled()) {
                    original.bounds = bounds
                    original.alpha = alpha
                    original.draw(canvas)
                    return@runCatching
                }
                val view = host.get() ?: return@runCatching
                val newNight = LauncherGlassSurface.isNight(view)
                val newRadius = if (fullscreenContainer) LiquidGlassSurface.cardRadius(view, false) else cornerRadius
                val newBackdrop = blurPercent() > 0
                if (surface == null || night != newNight || radius != newRadius || sharedBackdrop != newBackdrop) {
                    // Same optical material as notification cards and control-center
                    // sliders, over one native launcher blur (never blur the app icons).
                    surface = LiquidGlassSurface.create(view, newRadius, blurAvailable = false,
                        keyguard = false, night = newNight, sharedBackdrop = newBackdrop) {
                        report("create folder container", it)
                    }
                    night = newNight
                    radius = newRadius
                    sharedBackdrop = newBackdrop
                }
                surface?.let {
                    updateSurfaceBounds()
                    it.bounds = surfaceBounds
                    // Fullscreen visibility is animated on the Folder view itself;
                    // its deliberately transparent stock drawable is not a glass fade.
                    it.alpha = FolderGlassPolicy.containerOpacity(fullscreenContainer, alpha,
                        Color.alpha(color?.defaultColor ?: Color.WHITE), stockFillAlpha)
                    it.draw(canvas)
                }
            }.onFailure {
                report("draw folder", it)
                original.bounds = bounds
                original.draw(canvas)
            }
        }

        private fun updateSurfaceBounds() {
            surfaceBounds.set(bounds)
            if (fullscreenContainer) {
                val side = FolderGlassPolicy.containerSide(bounds.width(), bounds.height())
                surfaceBounds.right = surfaceBounds.left + side
                surfaceBounds.bottom = surfaceBounds.top + side
            }
        }

        override fun getOutline(outline: Outline) {
            runCatching {
                if (!enabled() || !fullscreenContainer) {
                    super.getOutline(outline)
                    return@runCatching
                }
                updateSurfaceBounds()
                val view = host.get() ?: return@runCatching
                outline.setRoundRect(surfaceBounds, LiquidGlassSurface.cardRadius(view, false))
            }.onFailure {
                report("outline square folder", it)
                super.getOutline(outline)
            }
        }
    }

    private class PreviewPaints {
        val body = Paint(Paint.ANTI_ALIAS_FLAG)
        val reflection = Paint(Paint.ANTI_ALIAS_FLAG)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private var geometry: List<Any>? = null

        fun update(x: Float, y: Float, radius: Float, ratio: Float, host: View) {
            val night = LauncherGlassSurface.isNight(host)
            val density = host.resources.displayMetrics.density
            val key = listOf(x, y, radius, ratio, night, density)
            if (geometry == key) return
            geometry = key
            body.shader = LinearGradient(x, y, x, y + radius * 2f * ratio,
                LauncherGlassPalette.bodyColors(night), null, Shader.TileMode.CLAMP)
            reflection.shader = LinearGradient(x, y, x + radius * 2f, y + radius * 2f * ratio,
                LauncherGlassPalette.reflectionColors(night), floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP)
            rim.strokeWidth = (LauncherGlassPalette.RIM_WIDTH_DP * density).coerceAtLeast(1f)
            rim.shader = LinearGradient(x, y, x + radius * 2f, y + radius * 2f * ratio,
                LauncherGlassPalette.rimColors(), floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
        }
    }

    private fun report(stage: String, error: Throwable) {
        if (reported.add(stage)) ErrorLogger.log("MotoLauncherGlass", stage, error)
    }
}
