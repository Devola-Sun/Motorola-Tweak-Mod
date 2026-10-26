package com.motolauncher.mod.hooks

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.ImageView
import android.widget.SeekBar
import com.motolauncher.mod.ErrorLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/** Optical changes only, confined to the vendor control-center subtree. */
internal object ControlCenterGlassHook {
    private const val STATE = "motorolaTweak.controlCenterGlass"
    private const val OBSERVED = "$STATE.observed"
    private val reported = mutableSetOf<String>()
    private var blurCheckedAt = -1L
    private var blurEnabled = false
    private val nativeBlur = ControlCenterNativeBlur()

    fun install(loader: ClassLoader, backgroundBlurPercent: () -> Int, enabled: () -> Boolean) {
        nativeBlur.install(loader, backgroundBlurPercent)
        runCatching {
            val panel = XposedHelpers.findClass("com.android.systemui.qs.QSModernPanel", loader)
            XposedBridge.hookAllConstructors(panel, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val view = param.thisObject as? ViewGroup ?: return@runCatching
                        if (XposedHelpers.getAdditionalInstanceField(view, OBSERVED) == true) return@runCatching
                        XposedHelpers.setAdditionalInstanceField(view, OBSERVED, true)
                        observe(view, enabled)
                    }.onFailure { report("observe control center", it) }
                }
            })
            // Prepare at the native layout boundary, before the first display list and
            // BackgroundBlurDrawable's pre-draw collection for the opening animation.
            for (method in listOf("onLayout", "onFinishInflate")) {
                XposedBridge.hookAllMethods(panel, method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            if (param.hasThrowable() || !enabled()) return@runCatching
                            val view = param.thisObject as? ViewGroup ?: return@runCatching
                            if (view.isAttachedToWindow) visit(view, true, blurAvailable(view), ancestorAlpha(view))
                        }.onFailure { report("prepare panel glass", it) }
                    }
                })
            }
        }.onFailure { report("install control-center glass", it) }
        installTileUpdates(loader, enabled)
        installDrawableWriters(enabled)
    }

    /** Catch replacements at their source, rather than repairing them one frame later. */
    private fun installDrawableWriters(enabled: () -> Boolean) {
        for ((owner, method) in listOf(View::class.java to "setBackgroundDrawable",
            ImageView::class.java to "setImageDrawable")) {
            runCatching {
                XposedHelpers.findAndHookMethod(owner, method, Drawable::class.java, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val view = param.thisObject as? View ?: return@runCatching
                            val state = XposedHelpers.getAdditionalInstanceField(view, STATE) as? State
                                ?: return@runCatching
                            val original = param.args[0] as? Drawable ?: return@runCatching
                            if (original is GlassDrawable || !enabled()) return@runCatching
                            val previous = when (method) {
                                "setBackgroundDrawable" -> if (state.backdrop) state.background else return@runCatching
                                "setImageDrawable" -> if (state.tileImage) state.image else return@runCatching
                                else -> return@runCatching
                            }
                            val glass = wrap(view, original, previous, blurAvailable(view))
                            when (method) {
                                "setBackgroundDrawable" -> state.background = glass
                                "setImageDrawable" -> state.image = glass
                            }
                            param.args[0] = glass
                        }.onFailure { report("wrap native drawable writer: $method", it) }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            (param.args[0] as? GlassDrawable)?.attachOriginal()
                        }.onFailure { report("attach native drawable writer: $method", it) }
                    }
                })
            }.onFailure { report("install native drawable writer: $method", it) }
        }
    }

    private fun installTileUpdates(loader: ClassLoader, enabled: () -> Boolean) {
        for (name in listOf("QSTileViewImpl", "QSTileViewModernImpl", "QSTileViewModernFixedImpl")) {
            runCatching {
                val clazz = XposedHelpers.findClassIfExists("com.android.systemui.qs.tileimpl.$name", loader)
                    ?: return@runCatching
                // Only hook methods actually declared by this ROM. The panel observer
                // remains the fallback for vendor-specific background writers.
                clazz.declaredMethods.filter {
                    it.name in setOf("handleStateChanged", "updateResources", "onConfigurationChanged",
                        "updateBackground", "updateBackgroundColors", "onLayout")
                }.forEach { method ->
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            runCatching {
                                if (param.hasThrowable() || !enabled()) return@runCatching
                                val view = param.thisObject as? View ?: return@runCatching
                                if (!view.isAttachedToWindow || !insidePanel(view)) return@runCatching
                                visit(view, true, blurAvailable(view), ancestorAlpha(view))
                            }.onFailure { report("native tile glass update", it) }
                        }
                    })
                }
            }.onFailure { report("install tile updates: $name", it) }
        }
    }

    private fun insidePanel(view: View): Boolean {
        var parent = view.parent as? View
        while (parent != null) {
            if (XposedHelpers.getAdditionalInstanceField(parent, OBSERVED) == true) return true
            parent = parent.parent as? View
        }
        return false
    }

    private fun observe(panel: ViewGroup, enabled: () -> Boolean) {
        val attachment = object : View.OnAttachStateChangeListener {
            private var observer: ViewTreeObserver? = null
            private var listener: ViewTreeObserver.OnPreDrawListener? = null
            private var applied = false

            override fun onViewAttachedToWindow(v: View) {
                runCatching {
                    nativeBlur.attach(panel)
                    hidden = false
                    if (enabled()) {
                        applied = true
                        visit(panel, true, blurAvailable(panel), ancestorAlpha(panel))
                    }
                    listener = ViewTreeObserver.OnPreDrawListener {
                        runCatching {
                            val active = enabled()
                            // The background slider also works with Liquid Glass OFF.
                            nativeBlur.update(panel)
                            if (!active && !applied) return@runCatching
                            applied = active
                            val opacity = ancestorAlpha(panel)
                            val blur = active && blurAvailable(panel)
                            // Invisible panels still need one pass to unregister their
                            // previous blur regions, then do no subtree work while hidden.
                            if (!active || opacity > 0f || !hidden) visit(panel, active, blur, opacity)
                            hidden = opacity == 0f
                        }.onFailure {
                            runCatching { visit(panel, false, false) }
                                .onFailure { failure -> report("rollback glass", failure) }
                            report("apply control-center glass", it)
                        }
                        true
                    }
                    observer = v.viewTreeObserver
                    observer?.addOnPreDrawListener(listener)
                }.onFailure { report("attach control-center glass", it) }
            }

            override fun onViewDetachedFromWindow(v: View) {
                runCatching {
                    listener?.let { if (observer?.isAlive == true) observer?.removeOnPreDrawListener(it) }
                    observer = null
                    listener = null
                    nativeBlur.detach(panel)
                    visit(panel, false, false)
                    applied = false
                }.onFailure { report("detach control-center glass", it) }
            }

            private var hidden = false
        }
        panel.addOnAttachStateChangeListener(attachment)
        if (panel.isAttachedToWindow) attachment.onViewAttachedToWindow(panel)
    }

    private class State {
        var background: GlassDrawable? = null
        var image: GlassDrawable? = null
        var sliderOverlay: Drawable? = null
        var sliderRadius = -1f
        val sliderBounds = Rect()
        val sliderOutline = Outline()
        var classified = false
        var backdrop = false
        var tileImage = false
    }

    private fun visit(view: View, enabled: Boolean, blur: Boolean, opacity: Float = 0f) {
        val existing = XposedHelpers.getAdditionalInstanceField(view, STATE) as? State
        if (!enabled) {
            existing?.let { restore(view, it) }
        } else {
            val state = existing ?: State().also {
                XposedHelpers.setAdditionalInstanceField(view, STATE, it)
            }
            if (!state.classified) {
                val name = view.javaClass.name
                state.backdrop = name.contains("QSTileView") ||
                    name.endsWith("CompactControlCenterLayout\$TileButton") ||
                    name == "com.android.systemui.qs.QSModernFixedPanel" ||
                    name == "com.motorola.systemui.prc.media.MediaPanelModernQS"
                state.tileImage = view is ImageView && isTileBackdrop(view)
                state.classified = true
            }
            if (state.backdrop || state.tileImage || view is SeekBar) {
                // No padding, LayoutParams, clipping, visibility or touch changes.
                if (state.backdrop) {
                    view.background?.let { original ->
                        val glass = wrap(view, original, state.background, blur)
                        state.background = glass
                        if (view.background !== glass) setBackground(view, glass)
                        // View normally supplies these bounds only in draw(). Supply
                        // the initial silhouette now so the blur is ready beforehand.
                        if (glass.bounds.isEmpty) glass.setBounds(0, 0, view.width, view.height)
                        glass.attachOriginal()
                    }
                }
                if (state.tileImage && view is ImageView) {
                    view.drawable?.let { original ->
                        val glass = wrap(view, original, state.image, blur)
                        state.image = glass
                        if (view.drawable !== glass) view.setImageDrawable(glass)
                        glass.attachOriginal()
                    }
                }
                if (view is SeekBar) styleSliderOverlay(view, state)
                state.background?.prepareSurface()
                state.image?.prepareSurface()
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val child = view.getChildAt(index)
                val alpha = if (child.visibility == View.VISIBLE) opacity * child.alpha else 0f
                visit(child, enabled, blur, alpha)
            }
        }
    }

    private fun ancestorAlpha(view: View): Float {
        if (!view.isAttachedToWindow || view.windowVisibility != View.VISIBLE) return 0f
        var alpha = 1f
        var current: View? = view
        while (current != null) {
            if (current.visibility != View.VISIBLE) return 0f
            alpha *= current.alpha
            if (alpha <= 0f) return 0f
            current = current.parent as? View
        }
        return alpha.coerceIn(0f, 1f)
    }

    private fun isTileBackdrop(view: View): Boolean {
        var parent = view.parent as? View
        repeat(3) {
            val ancestor = parent ?: return false
            if (ancestor.javaClass.name.contains("QSTileViewModernFixed") ||
                ancestor is ControlCenterHooks.AirplaneModeButtonView) {
                // The vendor background image is separate from the icon image.
                return resourceName(view) in setOf("background", "tile_background", "bg") ||
                    (ancestor is ControlCenterHooks.AirplaneModeButtonView &&
                        (view.parent as? ViewGroup)?.indexOfChild(view) == 0) ||
                    runCatching { XposedHelpers.getObjectField(ancestor, "mBg") === view }
                        .onFailure { report("locate fixed-tile background", it) }.getOrDefault(false)
            }
            parent = ancestor.parent as? View
        }
        return false
    }

    private fun styleSliderOverlay(view: SeekBar, state: State) {
        // Vendor code casts track layers AND their children to concrete drawable
        // types during color/size updates. Never replace any part of that tree.
        val track = view.progressDrawable ?: run {
            state.sliderOverlay?.let { view.overlay.remove(it) }
            state.sliderOverlay = null
            return
        }
        state.sliderBounds.set(track.bounds)
        if (state.sliderBounds.isEmpty) return
        state.sliderOutline.setEmpty()
        track.getOutline(state.sliderOutline)
        val radius = if (state.sliderOutline.radius >= 0f) state.sliderOutline.radius else
            minOf(state.sliderBounds.width(), state.sliderBounds.height()) / 2f
        state.sliderBounds.offset(view.paddingLeft, view.paddingTop)
        if (state.sliderOverlay == null || state.sliderRadius != radius) {
            state.sliderOverlay?.let { view.overlay.remove(it) }
            state.sliderOverlay = LiquidGlassSurface.create(view, radius, false,
                keyguard = false, night = true, sharedBackdrop = true) {
                report("slider glass overlay", it)
            }.also {
                // Keep the native fill/position and thumb legible under the reflection.
                it.alpha = 110
                view.overlay.add(it)
            }
            state.sliderRadius = radius
        }
        state.sliderOverlay?.let {
            if (it.bounds != state.sliderBounds) it.bounds = state.sliderBounds
        }
    }

    private fun wrap(view: View, original: Drawable, previous: GlassDrawable?, blur: Boolean): GlassDrawable {
        if (original === previous && previous.blur == blur) return previous
        val native = if (original === previous) previous.original else original
        previous?.release()
        return GlassDrawable(view, native, blur)
    }

    private fun restore(view: View, state: State) {
        // Remove membership first: restoring native drawables must bypass writer hooks.
        XposedHelpers.removeAdditionalInstanceField(view, STATE)
        state.background?.let { if (view.background === it) setBackground(view, it.original) }
        state.image?.let { if (view is ImageView && view.drawable === it) view.setImageDrawable(it.original) }
        state.sliderOverlay?.let { view.overlay.remove(it) }
        state.sliderOverlay = null
        state.background?.release()
        state.image?.release()
        XposedHelpers.removeAdditionalInstanceField(view, STATE)
    }

    private fun setBackground(view: View, drawable: Drawable) {
        val left = view.paddingLeft
        val top = view.paddingTop
        val right = view.paddingRight
        val bottom = view.paddingBottom
        view.background = drawable
        view.setPadding(left, top, right, bottom)
    }

    /** Retains native state colors/ripple/animations and exact native silhouette. */
    private class GlassDrawable(view: View, val original: Drawable, val blur: Boolean) : Drawable(), Drawable.Callback {
        private val outline = Outline()
        private val rect = Rect()
        private val density = view.resources.displayMetrics.density
        private val fallbackRadius = 24f * density
        private var radius = -1f
        private var surface: Drawable? = null
        private var failed = false
        // The view owns this drawable; never store the view in a global weak-map value.
        private val target = java.lang.ref.WeakReference(view)

        init {
            bounds = original.bounds
            state = original.state
            level = original.level
        }

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            if (failed) {
                original.draw(canvas)
                return
            }
            runCatching { drawGlass(canvas) }.onFailure {
                failed = true
                report("draw control-center glass", it)
                original.draw(canvas)
            }
        }

        private fun drawGlass(canvas: Canvas) {
            prepareSurface()
            surface?.let { tint ->
                if (tint.bounds != rect) tint.bounds = rect
                if (tint.alpha != original.alpha) tint.alpha = original.alpha
                tint.draw(canvas)
            }
            // Keep native accent/ripple state without alpha setters during drawing.
            val save = canvas.saveLayerAlpha(bounds.left.toFloat(), bounds.top.toFloat(),
                bounds.right.toFloat(), bounds.bottom.toFloat(), 122)
            try {
                original.draw(canvas)
            } finally {
                canvas.restoreToCount(save)
            }
        }

        fun prepareSurface() {
            if (failed || bounds.isEmpty) return
            original.getOutline(outline)
            val rounded = outline.getRect(rect)
            if (!rounded || rect.isEmpty) rect.set(bounds)
            val corner = if (rounded && outline.radius >= 0f) outline.radius else
                minOf(fallbackRadius, rect.width() / 2f, rect.height() / 2f)
            if (surface == null || radius != corner) {
                val view = target.get() ?: return
                surface?.callback = null
                radius = corner
                // SystemUI controls use a dark palette even over a light wallpaper.
                // One shared native shade blur; all optical cues are recorded in the
                // same display list as the control. No delayed per-tile blur regions.
                surface = LiquidGlassSurface.create(view, corner, false, keyguard = false, night = true,
                    sharedBackdrop = blur) {
                    report("control-center backdrop blur (using translucent fallback)", it)
                }.also { it.callback = this }
            }
        }

        fun attachOriginal() {
            // View/ImageView clear the old drawable's callback during replacement.
            // Reconnect only after assignment so native color and ripple updates work.
            original.callback = this
        }

        fun release() {
            if (original.callback === this) original.callback = null
            surface?.callback = null
        }

        override fun onBoundsChange(bounds: Rect) { original.bounds = bounds }
        override fun onStateChange(state: IntArray): Boolean = original.setState(state)
        override fun isStateful(): Boolean = original.isStateful
        override fun onLevelChange(level: Int): Boolean = original.setLevel(level)
        override fun getOutline(outline: Outline) { original.getOutline(outline) }
        override fun getPadding(padding: Rect): Boolean = original.getPadding(padding)
        override fun getIntrinsicWidth(): Int = original.intrinsicWidth
        override fun getIntrinsicHeight(): Int = original.intrinsicHeight
        override fun getMinimumWidth(): Int = original.minimumWidth
        override fun getMinimumHeight(): Int = original.minimumHeight
        override fun setAlpha(alpha: Int) { original.alpha = alpha }
        override fun getAlpha(): Int = original.alpha
        override fun setColorFilter(filter: ColorFilter?) { original.colorFilter = filter }
        override fun setTintList(tint: android.content.res.ColorStateList?) { original.setTintList(tint) }
        override fun setTintMode(mode: android.graphics.PorterDuff.Mode?) { original.setTintMode(mode) }
        override fun setTintBlendMode(mode: android.graphics.BlendMode?) { original.setTintBlendMode(mode) }
        override fun onLayoutDirectionChanged(layoutDirection: Int): Boolean = original.setLayoutDirection(layoutDirection)
        override fun setHotspot(x: Float, y: Float) { original.setHotspot(x, y) }
        override fun setHotspotBounds(left: Int, top: Int, right: Int, bottom: Int) {
            original.setHotspotBounds(left, top, right, bottom)
        }
        override fun jumpToCurrentState() { original.jumpToCurrentState() }
        override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
            val changed = super.setVisible(visible, restart)
            return original.setVisible(visible, restart) || changed
        }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun invalidateDrawable(who: Drawable) { invalidateSelf() }
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) { scheduleSelf(what, `when`) }
        override fun unscheduleDrawable(who: Drawable, what: Runnable) { unscheduleSelf(what) }
    }

    private fun blurAvailable(view: View): Boolean {
        val now = SystemClock.uptimeMillis()
        if (blurCheckedAt < 0 || now - blurCheckedAt >= 1000L) {
            blurEnabled = view.context.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true
            blurCheckedAt = now
        }
        return blurEnabled
    }

    private fun resourceName(view: View): String =
        if (view.id == View.NO_ID || view.id == 0 || view.id ushr 24 == 0) ""
        else runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")

    private fun report(stage: String, error: Throwable) {
        if (reported.add(stage)) ErrorLogger.log("MotoControlCenterGlass", stage, error)
    }
}
