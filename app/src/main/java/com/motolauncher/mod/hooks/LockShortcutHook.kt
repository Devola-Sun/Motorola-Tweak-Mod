package com.motolauncher.mod.hooks

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.motolauncher.mod.ErrorLogger
import com.motolauncher.mod.HookConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Only the two keyguard affordances; never changes unlock or launch behavior. */
object LockShortcutHook : YukiBaseHooker() {
    private const val TAG = "MotoLauncherMod"
    private data class State(
        val width: Int,
        val height: Int,
        val padding: IntArray,
        val background: Drawable?,
        val tint: ColorStateList?,
        var percent: Int = 100,
        var blurred: Boolean = false,
        var blurAvailable: Boolean = false,
        var customBackground: Drawable? = null
    )
    private val states = WeakHashMap<View, State>()
    private val observed = WeakHashMap<View, Boolean>()
    private val reportedErrors = mutableSetOf<String>()

    override fun onHook() {
        runCatching {
            // Moto's Android 16 keyguard uses LaunchableImageView start_button/end_button.
            // Constructors let us attach listeners without hooking every View draw system-wide.
            XposedBridge.hookAllConstructors(
                Class.forName("com.android.systemui.animation.view.LaunchableImageView", false, appClassLoader),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching { observe(param.thisObject as View) }
                            .onFailure { report("observe", it) }
                    }
                }
            )
            Log.i(TAG, "LockShortcut hooks installed")
        }.onFailure { report("install", it) }
    }

    private fun observe(view: View) {
        if (observed.put(view, true) != null) return
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            private var observer: ViewTreeObserver? = null
            private var listener: ViewTreeObserver.OnPreDrawListener? = null

            override fun onViewAttachedToWindow(v: View) {
                runCatching {
                    val name = if (v.id == View.NO_ID) "" else v.resources.getResourceEntryName(v.id)
                    if (name != "start_button" && name != "end_button") return@runCatching
                    listener = ViewTreeObserver.OnPreDrawListener {
                        runCatching { apply(v) }.onFailure { report("apply", it) }
                        true
                    }
                    observer = v.viewTreeObserver
                    observer?.addOnPreDrawListener(listener)
                }.onFailure { report("attach", it) }
            }

            override fun onViewDetachedFromWindow(v: View) {
                runCatching {
                    listener?.let { if (observer?.isAlive == true) observer?.removeOnPreDrawListener(it) }
                    observer = null
                    listener = null
                    // Keep the baseline while the same view is temporarily detached.
                    // The weak key is released when SystemUI discards the view.
                }.onFailure { report("detach", it) }
            }
        })
    }

    private fun apply(view: View) {
        val prefs = modulePrefs
        val blur = HookConfig.safeGetBoolean(prefs, HookConfig.KEY_LOCK_SHORTCUT_BLUR, false)
        val percent = HookConfig.coerceLockShortcutSize(
            HookConfig.safeGetInt(prefs, HookConfig.KEY_LOCK_SHORTCUT_SIZE_PERCENT, 100)
        )
        if (!blur && percent == 100 && !states.containsKey(view)) return
        val lp = view.layoutParams ?: return
        if (lp.width <= 0 || lp.height <= 0 || view.width <= 0) return
        val state = states.getOrPut(view) {
            State(lp.width, lp.height,
                intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom),
                view.background, view.backgroundTintList)
        }
        val factor = percent / 100f
        val width = (state.width * factor).roundToInt()
        val height = (state.height * factor).roundToInt()
        if (lp.width != width || lp.height != height) {
            // Layout dimensions enlarge hit targets too; do not fight launch/press scale animations.
            lp.width = width
            lp.height = height
            view.layoutParams = lp
        }
        val padding = state.padding.map { (it * factor).roundToInt() }
        if (view.paddingLeft != padding[0] || view.paddingTop != padding[1] ||
            view.paddingRight != padding[2] || view.paddingBottom != padding[3]) {
            view.setPadding(padding[0], padding[1], padding[2], padding[3])
        }
        val available = blur && view.context.getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled
        if (blur && (!state.blurred || state.percent != percent || state.blurAvailable != available)) {
            state.customBackground = makeBackground(view, state, width, height, available)
        }
        if (blur) {
            if (view.background !== state.customBackground) view.background = state.customBackground
            if (view.backgroundTintList != null) view.backgroundTintList = null
        } else if (state.blurred) {
            view.background = state.background
            view.backgroundTintList = state.tint
            state.customBackground = null
        }
        if (state.percent != percent || state.blurred != blur || state.blurAvailable != available) {
            Log.i(TAG, "LockShortcut ${view.resources.getResourceEntryName(view.id)}: size=$percent% " +
                "target=${width}x$height blur=$blur backdrop=$available")
        }
        state.percent = percent
        state.blurred = blur
        state.blurAvailable = available
    }

    private fun makeBackground(view: View, state: State, width: Int, height: Int, available: Boolean): Drawable {
        val density = view.resources.displayMetrics.density
        val surface = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(if (available) 115 else 175, 24, 24, 28))
            setStroke(density.roundToInt().coerceAtLeast(1), Color.argb(115, 255, 255, 255))
        }
        // RenderEffect on the ImageView would blur the icon, not the wallpaper.
        // BackgroundBlurDrawable samples the actual surface behind this view.
        val backdrop = if (available) runCatching {
            val root = View::class.java.getDeclaredMethod("getViewRootImpl").apply { isAccessible = true }.invoke(view)
                ?: error("ViewRootImpl unavailable")
            val drawable = root.javaClass.getDeclaredMethod("createBackgroundBlurDrawable")
                .apply { isAccessible = true }.invoke(root) as Drawable
            drawable.javaClass.getMethod("setBlurRadius", Int::class.javaPrimitiveType)
                .invoke(drawable, (32f * density).roundToInt())
            drawable.javaClass.getMethod("setCornerRadius", Float::class.javaPrimitiveType)
                .invoke(drawable, minOf(width, height) / 2f)
            drawable
        }.onFailure { report("backdrop blur (using contrast fallback)", it) }.getOrNull() else null
        val content = if (backdrop != null) LayerDrawable(arrayOf(backdrop, surface)) else surface
        val ripple = RippleDrawable(ColorStateList.valueOf(0x40ffffff), content,
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
        // Keep stock activated-state feedback (e.g. flashlight on), including its tint.
        val activated = state.background?.constantState?.newDrawable()?.mutate()?.apply {
            state.tint?.let { setTintList(it) }
        }
        return if (activated == null) ripple else StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_activated), activated)
            addState(intArrayOf(), ripple)
        }
    }

    private fun report(stage: String, error: Throwable) {
        // A broken vendor API must not flood the log on every frame.
        if (reportedErrors.add(stage)) ErrorLogger.log(TAG, "LockShortcut $stage", error)
    }
}
