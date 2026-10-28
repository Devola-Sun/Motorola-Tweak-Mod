package com.motolauncher.mod.hooks

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.motolauncher.mod.ErrorLogger
import com.motolauncher.mod.HookConfig
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Replace only Motorola's background View; stock placement and animations remain authoritative. */
object MainDockGlassHook : YukiBaseHooker() {
    private data class State(val original: Drawable?, val glass: DockDrawable)
    private val states = WeakHashMap<View, State>()
    private val reported = mutableSetOf<String>()

    override fun onHook() {
        hookNativeBlurRegions()
        listOf("layoutBgView", "onLayout", "onConfigurationChanged").forEach { methodName ->
            runCatching {
                findClass("com.android.launcher3.Hotseat").hook {
                    injectMember {
                        method { name = methodName }.all()
                        afterHook {
                            runCatching {
                                val hotseat = instance as? View ?: return@runCatching
                                update(hotseat)
                            }.onFailure { report("update main Dock", it) }
                        }
                    }
                }
            }.onFailure { report("hook $methodName", it) }
        }
    }

    /** Motorola submits these blur regions separately from View backgrounds every frame. */
    private fun hookNativeBlurRegions() {
        runCatching {
            findClass("com.android.launcher3.uioverrides.QuickstepLauncher").hook {
                injectMember {
                    method { name = "addBlurTarget" }.all()
                    beforeHook {
                        runCatching {
                            val folderGlass = HookConfig.safeGetBoolean(modulePrefs,
                                HookConfig.KEY_FOLDER_LIQUID_GLASS, false)
                            val dockGlass = HookConfig.isMainDockLiquidGlassEnabled(modulePrefs)
                            if (!folderGlass && !dockGlass) return@runCatching
                            val target = args.getOrNull(2) as? View ?: return@runCatching
                            if (folderGlass && target.javaClass.name == "com.android.launcher3.folder.FolderIcon") {
                                result = null
                                return@runCatching
                            }
                            if (!dockGlass) return@runCatching
                            val hotseat = target.parent as? View ?: return@runCatching
                            if (hotseat.javaClass.name != "com.android.launcher3.Hotseat") return@runCatching
                            if (XposedHelpers.getObjectField(hotseat, "mBgView") === target &&
                                CoverDockHook.isMainDisplay(hotseat)) {
                                // Do not skip doBlurRegions: it must still clear old regions and
                                // submit the remaining widget/search/page-indicator backgrounds.
                                result = null
                            }
                        }.onFailure { report("exclude native glass blur region", it) }
                    }
                }
            }
        }.onFailure { report("hook native glass blur regions", it) }
    }

    private fun update(hotseat: View) {
        val liquid = HookConfig.isMainDockLiquidGlassEnabled(modulePrefs)
        val frost = HookConfig.isMainDockFrostEnabled(modulePrefs)
        if (!liquid && !frost && states.isEmpty()) return
        val bg = XposedHelpers.getObjectField(hotseat, "mBgView") as? View ?: return
        val active = (liquid || frost) && CoverDockHook.isMainDisplay(hotseat)
        val previous = states[bg]
        if (!active) {
            if (previous != null) {
                if (bg.background === previous.glass) bg.background = previous.original
                states.remove(bg)
            }
            return
        }
        if (previous != null && bg.background === previous.glass && previous.glass.liquid == liquid) return
        val original = if (bg.background === previous?.glass) previous?.original else bg.background
        val glass = DockDrawable(bg, original, liquid)
        bg.background = glass
        states[bg] = State(original, glass)
    }

    /** The stock layer is replaced with a transparent base, never drawn underneath glass. */
    private class DockDrawable(view: View, private val original: Drawable?, val liquid: Boolean) : Drawable() {
        private val host = WeakReference(view)
        private val transparentBase = ColorDrawable(Color.TRANSPARENT)
        private var surface: Drawable? = null
        private var night: Boolean? = null
        private var radius = -1f
        private var opacity = 255
        private var filter: ColorFilter? = null

        private fun corner(view: View): Float {
            val stockRadius = (original as? GradientDrawable)?.cornerRadius ?: 0f
            return minOf(if (stockRadius > 0f) stockRadius else 28f * view.resources.displayMetrics.density,
                minOf(bounds.width(), bounds.height()).coerceAtLeast(0) / 2f)
        }

        override fun draw(canvas: Canvas) {
            runCatching {
                val view = host.get() ?: return@runCatching
                val newNight = LauncherGlassSurface.isNight(view)
                val newRadius = corner(view)
                if (surface == null || night != newNight || radius != newRadius) {
                    surface = LauncherGlassSurface.create(view, newRadius, liquid)
                    night = newNight
                    radius = newRadius
                }
                transparentBase.draw(canvas)
                surface?.let {
                    it.bounds = bounds
                    it.alpha = opacity
                    it.colorFilter = filter
                    it.draw(canvas)
                }
            }.onFailure {
                report("draw main Dock", it)
                original?.bounds = bounds
                original?.draw(canvas)
            }
        }

        override fun onBoundsChange(bounds: Rect) { transparentBase.bounds = bounds }
        override fun getPadding(padding: Rect): Boolean = original?.getPadding(padding) ?: super.getPadding(padding)
        override fun getOutline(outline: Outline) {
            val view = host.get() ?: return
            outline.setRoundRect(bounds, corner(view))
        }
        override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
        override fun getAlpha(): Int = opacity
        override fun setColorFilter(colorFilter: ColorFilter?) { filter = colorFilter; invalidateSelf() }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun report(stage: String, error: Throwable) {
        if (reported.add(stage)) ErrorLogger.log("MotoLauncherGlass", stage, error)
    }
}
