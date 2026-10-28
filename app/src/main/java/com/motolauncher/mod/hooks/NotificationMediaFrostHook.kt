package com.motolauncher.mod.hooks

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.ImageView
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.motolauncher.mod.ErrorLogger
import com.motolauncher.mod.HookConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Restyles native media views in place; expansion, privacy and session binding stay native. */
object NotificationMediaFrostHook : YukiBaseHooker() {
    private const val TAG = "MotoNotificationFrost"
    private const val DRAW_ORIGINAL = "motorolaTweak.frost.originalBackground"
    private val reported = mutableSetOf<String>()
    private val notifications = WeakHashMap<View, Glass>()
    private val media = WeakHashMap<View, MediaState>()
    private val observed = WeakHashMap<View, Boolean>()
    private val applying = WeakHashMap<View, Boolean>()
    private var blurCheckedAt = -1L
    private var blurEnabled = false

    private data class Glass(val drawable: Drawable, val night: Boolean, val blur: Boolean, val keyguard: Boolean)
    private class MediaState(
        var original: Drawable?,
        var tint: ColorStateList?,
        val padding: Rect,
        var glass: Glass? = null,
        val layers: WeakHashMap<View, HiddenLayer> = WeakHashMap(),
        val artwork: DynamicMediaArtwork = DynamicMediaArtwork()
    )
    private data class HiddenLayer(var background: Drawable?, var alpha: Float, var hideImage: Boolean)
    private enum class MediaHost { UNKNOWN, NOTIFICATION, DYNAMIC, CONTROL_CENTER }

    /** Drawable callbacks must not retain the weak-map key through its value. */
    private class WeakCallback(view: View) : Drawable.Callback {
        private val target = WeakReference(view)
        override fun invalidateDrawable(who: Drawable) { target.get()?.invalidate() }
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            target.get()?.scheduleDrawable(who, what, `when`)
        }
        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            target.get()?.unscheduleDrawable(who, what)
        }
    }

    override fun onHook() {
        val loader = appClassLoader ?: return
        KeyguardPanelVisibility.install(loader)
        val notificationRow = runCatching {
            XposedHelpers.findClass("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", loader)
        }.onFailure { report("find notification content owner", it) }.getOrNull()
        runCatching {
            val clazz = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.row.NotificationBackgroundView", loader
            )
            val isolatedSurface = runCatching { KeyguardNotificationSurface(clazz) }
                .onFailure { report("install isolated notification geometry helper", it) }.getOrNull()
            XposedBridge.hookAllMethods(clazz, "onDraw", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val view = param.thisObject as? View ?: return@runCatching
                        if (!enabled()) {
                            notifications.remove(view)
                            return@runCatching
                        }
                        if (!view.isAttachedToWindow) return@runCatching
                        val original = XposedHelpers.getObjectField(view, "mBackground") as? Drawable
                            ?: return@runCatching
                        val glass = glassFor(view, notifications[view], ripple = true)
                        notifications[view] = glass
                        if (glass.drawable.callback == null) glass.drawable.callback = WeakCallback(view)
                        glass.drawable.state = original.state
                        glass.drawable.level = original.level
                        glass.drawable.alpha = original.alpha
                        if (SystemUiGlassPalette.isolateNotificationDraw(glass.night, glass.keyguard) &&
                            isolatedSurface != null) {
                            val canvas = param.args.firstOrNull() as? Canvas ?: return@runCatching
                            // Merely swapping mBackground leaves Motorola's separate
                            // light/lock-screen blur/tint pass in onDraw intact. Draw only
                            // our material via the native geometry helper instead.
                            // Dark shade keeps its existing native path.
                            isolatedSurface.draw(view, canvas, glass.drawable)
                            param.result = null
                            return@runCatching
                        }
                        // Swap only during the native draw: retain its roundness, group clipping,
                        // actual height, dismiss/expansion animations and original tint bookkeeping.
                        param.setObjectExtra(DRAW_ORIGINAL, original)
                        XposedHelpers.setObjectField(view, "mBackground", glass.drawable)
                    }.onFailure { report("notification surface", it) }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val original = param.getObjectExtra(DRAW_ORIGINAL) as? Drawable ?: return@runCatching
                        XposedHelpers.setObjectField(param.thisObject, "mBackground", original)
                    }.onFailure { report("restore notification surface", it) }
                }
            })
            XposedBridge.hookAllMethods(clazz, "onDetachedFromWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { notifications.remove(param.thisObject as? View) }
                        .onFailure { report("notification detach", it) }
                }
            })
        }.onFailure { report("install notification hook", it) }

        // Observe AOSP notification/keyguard cards only. The vendor ModernQS panel is
        // exclusively a control-center surface: leave its native background/blur alone.
        // The independent compact control-center layout hooks remain untouched.
        var installed = false
        for (name in listOf(
            "com.android.systemui.util.animation.TransitionLayout",
            "com.motorola.systemui.prc.media.MediaPanelKeyguard"
        )) {
            runCatching {
                val clazz = XposedHelpers.findClassIfExists(name, loader) ?: return@runCatching
                XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching { (param.thisObject as? ViewGroup)?.let(::observeMedia) }
                            .onFailure { report("observe media", it) }
                    }
                })
                // TransitionLayout's own pre-draw callback can run AFTER ours while
                // scrubbing. Reapply at the native state boundary, not one frame later.
                clazz.declaredMethods.filter {
                    it.name in setOf("applyCurrentState", "applyCurrentStateToView", "setState", "onLayout")
                }.forEach { method ->
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            runCatching {
                                if (param.hasThrowable()) return@runCatching
                                val view = param.thisObject as? ViewGroup ?: return@runCatching
                                if (observed.containsKey(view) && view.isAttachedToWindow) updateMedia(view)
                            }.onFailure { report("native media state", it) }
                        }
                    })
                }
                clazz.declaredMethods.filter {
                    it.name in setOf("draw", "dispatchDraw") &&
                        it.parameterTypes.contentEquals(arrayOf(Canvas::class.java))
                }.forEach { method ->
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            runCatching {
                                val view = param.thisObject as? ViewGroup ?: return@runCatching
                                if (observed.containsKey(view) && view.isAttachedToWindow) updateMedia(view)
                            }.onFailure { report("native media draw", it) }
                        }
                    })
                }
                installed = true
            }.onFailure { report("install $name", it) }
        }
        if (!installed) report("install media hook", IllegalStateException("No supported media view class"))

        // A vendor/inlined state writer can bypass the named TransitionLayout hooks
        // and run after OnPreDraw. Guard the actual display-list recording boundary
        // as well. Membership is checked before prefs/reflection/tree traversal.
        for ((owner, method) in listOf(View::class.java to "draw", ViewGroup::class.java to "dispatchDraw")) {
            runCatching {
                XposedHelpers.findAndHookMethod(owner, method, Canvas::class.java, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val drawn = param.thisObject as? View ?: return@runCatching
                            if (drawn is ViewGroup && notificationRow?.isInstance(drawn) == true) {
                                val night = drawn.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                                    Configuration.UI_MODE_NIGHT_YES
                                if (enabled() && !night) LightGlassContentStyle.apply(drawn, media = false)
                                else LightGlassContentStyle.restore(drawn)
                                return@runCatching
                            }
                            LockMediaCardStyle.owner(drawn)?.let { root ->
                                if (root.isAttachedToWindow) updateMedia(root)
                                return@runCatching
                            }
                            val view = drawn as? ViewGroup ?: return@runCatching
                            if (!observed.containsKey(view) || !view.isAttachedToWindow) return@runCatching
                            updateMedia(view)
                        }.onFailure { report("media draw boundary", it) }
                    }
                })
            }.onFailure { report("install media $method boundary", it) }
        }

        // HWUI can redraw an invalidated child without recording its parent's
        // dispatchDraw again. Enforce the layout at the child recording boundary too.
        runCatching {
            XposedHelpers.findAndHookMethod(ViewGroup::class.java, "drawChild",
                Canvas::class.java, View::class.java, Long::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val child = param.args[1] as? View ?: return@runCatching
                            val root = LockMediaCardStyle.owner(child) ?: return@runCatching
                            if (root.isAttachedToWindow) updateMedia(root)
                        }.onFailure { report("media child draw boundary", it) }
                    }
                })
        }.onFailure { report("install media child boundary", it) }

        // Motorola keyguard cards are not necessarily TransitionLayouts. Discover the
        // actual inflated media layout, rather than requiring an AOSP root ID/class.
        runCatching {
            XposedBridge.hookAllMethods(LayoutInflater::class.java, "inflate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val id = param.args.firstOrNull() as? Int ?: return@runCatching
                        val inflater = param.thisObject as LayoutInflater
                        val layout = inflater.context.resources.getResourceEntryName(id)
                        if (!layout.contains("media", ignoreCase = true)) return@runCatching
                        val root = param.result as? ViewGroup ?: return@runCatching
                        discoverMedia(root)
                    }.onFailure { report("discover inflated media", it) }
                }
            })
        }.onFailure { report("install media discovery", it) }
    }

    private fun enabled(): Boolean = HookConfig.safeGetBoolean(
        modulePrefs, HookConfig.KEY_NOTIFICATION_MEDIA_FROST, HookConfig.DEFAULT_NOTIFICATION_MEDIA_FROST
    )

    private fun discoverMedia(root: ViewGroup) {
        if (isMediaCard(root) || resourceName(root) == "root_view") observeMedia(root)
        for (i in 0 until root.childCount) {
            (root.getChildAt(i) as? ViewGroup)?.let(::discoverMedia)
        }
    }

    private fun observeMedia(view: ViewGroup) {
        if (observed.put(view, true) != null) return
        val attachment = object : View.OnAttachStateChangeListener {
            private var observer: ViewTreeObserver? = null
            private var listener: ViewTreeObserver.OnPreDrawListener? = null

            override fun onViewAttachedToWindow(v: View) {
                runCatching {
                    if (!isMediaCard(v) && resourceName(v) != "root_view") return@runCatching
                    listener = ViewTreeObserver.OnPreDrawListener {
                         runCatching { updateMedia(view) }.onFailure {
                            runCatching { LockMediaCardStyle.restore(view) }.onFailure { failure -> report("compact media rollback", failure) }
                            runCatching { restoreMedia(view) }.onFailure { failure -> report("media rollback", failure) }
                            report("media surface", it)
                        }
                        true
                    }
                    observer = v.viewTreeObserver
                    observer?.addOnPreDrawListener(listener)
                }.onFailure { report("media attach", it) }
            }

            override fun onViewDetachedFromWindow(v: View) {
                runCatching {
                    listener?.let { if (observer?.isAlive == true) observer?.removeOnPreDrawListener(it) }
                    observer = null
                    listener = null
                    LockMediaCardStyle.restore(view)
                    restoreMedia(view)
                }.onFailure { report("media detach", it) }
            }
        }
        view.addOnAttachStateChangeListener(attachment)
        // inflate(..., attachedParent, true) can return an already attached subtree.
        if (view.isAttachedToWindow) attachment.onViewAttachedToWindow(view)
    }

    private fun isMediaCard(view: View): Boolean = resourceName(view) in setOf(
        "media_player", "media_recommendation", "media_player_view", "media_panel", "qs_media_controls"
    ) || view.javaClass.name == "com.motorola.systemui.prc.media.MediaPanelKeyguard"

    private fun isPlaybackRoot(view: View): Boolean {
        if (isMediaCard(view)) return true
        if (resourceName(view) != "root_view" || view !is ViewGroup) return false
        return (0 until view.childCount).any { resourceName(view.getChildAt(it)) == "media_progress_bar" }
    }

    private fun containsPlaybackRoot(parent: ViewGroup): Boolean =
        (0 until parent.childCount).any { index ->
            val child = parent.getChildAt(index)
            isPlaybackRoot(child) || child is ViewGroup && containsPlaybackRoot(child)
        }

    private fun mediaHost(view: ViewGroup): MediaHost {
        var inNotificationHost = false
        var inDynamicHost = false
        var current: View? = view
        while (current != null) {
            val name = resourceName(current)
            val className = current.javaClass.name
            // Control-center ancestry always wins, including QS over keyguard.
            if (MediaGlassScope.isControlCenter(name, className)) return MediaHost.CONTROL_CENTER
            if (MediaGlassScope.isNotificationHost(name, className)) inNotificationHost = true
            if (MediaGlassScope.isDynamicHost(name, className)) inDynamicHost = true
            current = current.parent as? View
        }
        if (inNotificationHost) return MediaHost.NOTIFICATION
        // Require real playback controls before accepting a floating notification host.
        val ids = (0 until view.childCount).map { resourceName(view.getChildAt(it)) }.toSet()
        if (!ids.containsAll(setOf("album_art", "icon", "header_title", "media_progress_bar"))) return MediaHost.UNKNOWN
        val window = view.rootView.layoutParams as? WindowManager.LayoutParams
        val popup = inDynamicHost || window?.let {
            MediaGlassScope.isPopupWindow(it.type) || MediaGlassScope.isDynamicHost(it.title?.toString().orEmpty(), "")
        } == true
        return if (popup) MediaHost.DYNAMIC else MediaHost.UNKNOWN
    }

    private fun applyMedia(view: ViewGroup) {
        // Motorola can wrap the AOSP card in both MediaPanelKeyguard and root_view.
        // Tinting every discovered wrapper stacks the same translucent material,
        // making media darker than notifications (and restores it during seeking).
        // Only the innermost real playback surface owns a backdrop.
        if (!isPlaybackRoot(view) || containsPlaybackRoot(view)) {
            LockMediaCardStyle.restore(view)
            restoreMedia(view)
            return
        }
        val host = mediaHost(view)
        val notificationHost = host == MediaHost.NOTIFICATION
        val dynamicHost = host == MediaHost.DYNAMIC
        val compactEnabled = HookConfig.safeGetBoolean(modulePrefs,
            HookConfig.KEY_LOCK_MEDIA_COMPACT, HookConfig.DEFAULT_LOCK_MEDIA_COMPACT)
        val compact = LockMediaCardScope.allows(
            enabled = compactEnabled,
            keyguard = compactEnabled && notificationHost && LockNotificationLayout.isKeyguard(view) &&
                !KeyguardPanelVisibility.controlCenterVisible,
            notificationHost = notificationHost,
            // Scope by THIS card's ancestors. A global QS visibility transition
            // must not restore another, still-visible notification card to AOSP.
            controlCenter = host == MediaHost.CONTROL_CENTER,
            dynamicHost = dynamicHost
        )
        if (compact) {
            // Hand off ownership once: remove the frost/artwork overlay before the
            // shared style snapshots native controls. Never run both renderers on
            // the same card, or create/reparent a competing playback view.
            LightGlassContentStyle.restore(view)
            restoreMedia(view)
            if (LockMediaCardStyle.apply(view, liquid = enabled(), blurAvailable = blurAvailable(view),
                forceKeyguardAppearance = dynamicHost) {
                report("compact media backdrop blur (using translucent fallback)", it)
            }) return
            // Zero-sized transition frames are not an opt-out. Keep the last style
            // until usable native bounds return, rather than restoring it each frame.
            if (LockMediaCardStyle.isApplied(view)) return
        }
        LockMediaCardStyle.restore(view)
        if (!enabled() || (!notificationHost && !dynamicHost)) {
            restoreMedia(view)
            return
        }
        // Avoid layering blur twice if a Motorola panel wraps an AOSP media card.
        var ancestor = view.parent as? View
        while (ancestor != null) {
            if (media.containsKey(ancestor)) {
                restoreMedia(view)
                return
            }
            ancestor = ancestor.parent as? View
        }
        if (view.width <= 0 || view.height <= 0) return
        val state = media.getOrPut(view) {
            MediaState(view.background, view.backgroundTintList,
                Rect(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom))
        }
        if (view.background !== state.glass?.drawable) {
            state.original = view.background
            state.tint = view.backgroundTintList
        }
        val glass = glassFor(view, state.glass, ripple = false, forceDark = dynamicHost)
        if (!glass.night) LightGlassContentStyle.apply(view, media = true)
        else LightGlassContentStyle.restore(view)
        state.glass = glass
        if (view.backgroundTintList != null) view.backgroundTintList = null
        if (view.background !== glass.drawable) {
            view.background = glass.drawable
            glass.drawable.callback = WeakCallback(view)
            // A background swap must never resize/reposition playback controls.
            view.setPadding(state.padding.left, state.padding.top, state.padding.right, state.padding.bottom)
        }
        stripMediaBackdrop(view, view, state)
        if (dynamicHost) state.artwork.apply(view) else state.artwork.restore(view)
    }

    private fun updateMedia(view: ViewGroup) {
        if (applying.put(view, true) != null) return
        try {
            applyMedia(view)
            MediaArtworkBrightnessHook.updateArtwork(view)
        } finally {
            applying.remove(view)
        }
    }

    private fun stripMediaBackdrop(root: ViewGroup, parent: ViewGroup, state: MediaState) {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            val name = resourceName(child)
            // Remove only full-bleed decorative art/backgrounds. Keep small album covers,
            // actions, progress bars, output switchers, accessibility and touch targets.
            val backdrop = name in setOf("album_art", "album_art_container", "media_background",
                "media_bg", "background", "media_player_background", "touch_ripple_view",
                "turbulence_noise_view", "loading_effect_view") &&
                child.width >= root.width * 0.85f && child.height >= root.height * 0.85f
            if (backdrop) {
                val layer = state.layers.getOrPut(child) {
                    HiddenLayer(child.background, child.alpha, child is ImageView ||
                        name in setOf("touch_ripple_view", "turbulence_noise_view", "loading_effect_view"))
                }
                if (child.background != null) layer.background = child.background
                if (child.background != null) child.background = null
                if (layer.hideImage) {
                    if (child.alpha != 0f) layer.alpha = child.alpha
                    if (child.alpha != 0f) child.alpha = 0f
                }
            } else {
                state.layers.remove(child)?.let { restoreLayer(child, it) }
            }
            if (child is ViewGroup) stripMediaBackdrop(root, child, state)
        }
    }

    private fun restoreLayer(view: View, layer: HiddenLayer) {
        if (view.background == null) view.background = layer.background
        if (layer.hideImage && view.alpha == 0f) view.alpha = layer.alpha
    }

    private fun restoreMedia(view: ViewGroup) {
        LightGlassContentStyle.restore(view)
        val state = media[view] ?: return
        state.artwork.restore(view)
        if (view.background === state.glass?.drawable) {
            view.background = state.original
            view.backgroundTintList = state.tint
            view.setPadding(state.padding.left, state.padding.top, state.padding.right, state.padding.bottom)
        }
        state.layers.forEach { (child, layer) -> restoreLayer(child, layer) }
        media.remove(view)
    }

    private fun glassFor(view: View, previous: Glass?, ripple: Boolean, forceDark: Boolean = false): Glass {
        val keyguard = LockNotificationLayout.isKeyguard(view)
        val night = forceDark || view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val blur = blurAvailable(view)
        if (previous != null && previous.night == night && previous.blur == blur && previous.keyguard == keyguard) return previous
        val radius = LiquidGlassSurface.cardRadius(view, keyguard)
        val content = LiquidGlassSurface.create(view, radius, blur, keyguard, night) {
            report("backdrop blur (using translucent fallback)", it)
        }
        val drawable = if (ripple) RippleDrawable(
            ColorStateList.valueOf(if (night) 0x30ffffff else 0x18000000), content,
            GradientDrawable().apply { cornerRadius = radius; setColor(Color.WHITE) }
        ) else content
        return Glass(drawable, night, blur, keyguard)
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
        if (view.id == View.NO_ID || view.id == 0 || (view.id ushr 24) == 0) ""
        else view.resources.getResourceEntryName(view.id)

    private fun report(stage: String, error: Throwable) {
        if (reported.add(stage)) ErrorLogger.log(TAG, stage, error)
    }
}
