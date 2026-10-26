package com.motolauncher.mod.hooks

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.motolauncher.mod.ErrorLogger
import com.motolauncher.mod.HookConfig
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** GradientDrawable compatibility preserves FolderAnimationManager's color/alpha animations. */
object FolderGlassHook : YukiBaseHooker() {
    private val reported = mutableSetOf<String>()
    private val previews = WeakHashMap<Any, PreviewPaints>()

    private fun enabled() = HookConfig.safeGetBoolean(modulePrefs, HookConfig.KEY_FOLDER_LIQUID_GLASS, false)

    override fun onHook() {
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
                                val background = XposedHelpers.getObjectField(folder, "mBackground") as? GradientDrawable
                                if (background != null && background !is FolderDrawable) {
                                    XposedHelpers.setObjectField(folder, "mBackground", FolderDrawable(folder, background))
                                }
                                // Motorola's fullscreen folders draw the content background instead.
                                val content = XposedHelpers.getObjectField(folder, "mContent") as? View
                                val contentBackground = content?.background as? GradientDrawable
                                if (content != null && contentBackground != null && contentBackground !is FolderDrawable) {
                                    content.background = FolderDrawable(content, contentBackground)
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
                            // Ask the native IconShape to draw both layers, including big-folder aspect ratio.
                            XposedHelpers.callMethod(shape, "drawShape", canvas, x, y, radius, paints.body, ratio)
                            XposedHelpers.callMethod(shape, "drawShape", canvas, x, y, radius, paints.rim, ratio)
                            result = null
                        }.onFailure { report("draw folder preview", it) }
                    }
                }
            }
        }.onFailure { report("hook folder preview", it) }
    }

    private class FolderDrawable(view: View, private val original: GradientDrawable) : GradientDrawable() {
        private val host = WeakReference(view)
        private var surface: Drawable? = null
        private var night: Boolean? = null
        private var radius = -1f

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
                val newRadius = cornerRadius
                if (surface == null || night != newNight || radius != newRadius) {
                    surface = LauncherGlassSurface.create(view, newRadius, liquid = true)
                    night = newNight
                    radius = newRadius
                }
                surface?.let {
                    it.bounds = bounds
                    // Native fullscreen/open-close animations may animate the fill to transparent.
                    it.alpha = alpha * Color.alpha(color?.defaultColor ?: Color.WHITE) / 255
                    it.draw(canvas)
                }
            }.onFailure {
                report("draw folder", it)
                original.bounds = bounds
                original.draw(canvas)
            }
        }
    }

    private class PreviewPaints {
        val body = Paint(Paint.ANTI_ALIAS_FLAG)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private var geometry: List<Any>? = null

        fun update(x: Float, y: Float, radius: Float, ratio: Float, host: View) {
            val night = LauncherGlassSurface.isNight(host)
            val density = host.resources.displayMetrics.density
            val key = listOf(x, y, radius, ratio, night, density)
            if (geometry == key) return
            geometry = key
            body.shader = LinearGradient(x, y, x, y + radius * 2f * ratio,
                if (night) intArrayOf(Color.argb(82, 65, 74, 90), Color.argb(112, 24, 30, 42))
                else intArrayOf(Color.argb(100, 245, 250, 255), Color.argb(62, 165, 188, 210)),
                null, Shader.TileMode.CLAMP)
            rim.strokeWidth = (0.75f * density).coerceAtLeast(1f)
            rim.shader = LinearGradient(x, y, x + radius * 2f, y + radius * 2f * ratio,
                intArrayOf(Color.argb(142, 255, 255, 255), Color.argb(16, 255, 255, 255),
                    Color.argb(76, 255, 255, 255)), null, Shader.TileMode.CLAMP)
        }
    }

    private fun report(stage: String, error: Throwable) {
        if (reported.add(stage)) ErrorLogger.log("MotoLauncherGlass", stage, error)
    }
}
