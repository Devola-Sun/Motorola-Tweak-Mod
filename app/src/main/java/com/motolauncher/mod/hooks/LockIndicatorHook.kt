package com.motolauncher.mod.hooks

import android.app.KeyguardManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.PowerManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.motolauncher.mod.ErrorLogger
import com.motolauncher.mod.HookConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** A non-interactive root-overlay glyph; native authentication logic is never replaced. */
internal object LockIndicatorHook : YukiBaseHooker() {
    private val roots = WeakHashMap<ViewGroup, State>()
    private val faces = WeakHashMap<View, Boolean>()
    private val nativeIcons = WeakHashMap<View, NativeIcon>()
    private val errors = mutableSetOf<String>()
    private var dozing = false

    private class State {
        val glyph = FilledLockDrawable()
        var added = false
        var scanNeeded = true
    }

    private class NativeIcon {
        var visibility = View.VISIBLE
        var hidden = false
        var applying = false
        var observed = false
    }

    override fun onHook() {
        val loader = appClassLoader ?: return
        KeyguardPanelVisibility.install(loader)
        // Android 16's reactive device-entry UI no longer needs LockIconView.
        // Discover from real clock factories as well as resource inflation.
        hook("com.android.systemui.shared.clocks.ClockRegistry") { clazz ->
            clazz.declaredMethods.filter { it.name == "createCurrentClock" && it.parameterCount == 0 }
                .forEach { method ->
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            runCatching {
                                if (param.hasThrowable()) return@runCatching
                                val controller = param.result ?: return@runCatching
                                for (getter in listOf("getLargeClock", "getSmallClock")) {
                                    runCatching {
                                        val face = XposedHelpers.callMethod(controller, getter)
                                        val layout = XposedHelpers.callMethod(face, "getLayout")
                                        (XposedHelpers.callMethod(layout, "getViews") as? List<*>)
                                            ?.filterIsInstance<View>()?.forEach(::observeFace)
                                    }.onFailure { report("clock $getter", it) }
                                }
                            }.onFailure { report("clock factory", it) }
                        }
                    })
                }
        }
        for (name in listOf("com.android.keyguard.AnimatableClockView",
            "com.android.systemui.shared.clocks.AnimatableClockView")) {
            hook(name) { clazz ->
                XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching { observeFace(param.thisObject as View) }
                            .onFailure { report("legacy clock", it) }
                    }
                })
            }
        }
        for (name in listOf("com.android.keyguard.LockIconView",
            "com.android.systemui.keyguard.ui.view.DeviceEntryIconView")) {
            hook(name) { clazz ->
                XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching { observeNative(param.thisObject as View) }
                            .onFailure { report("native icon factory", it) }
                    }
                })
            }
        }
        runCatching {
            XposedHelpers.findAndHookMethod(LayoutInflater::class.java, "inflate",
                Int::class.javaPrimitiveType, ViewGroup::class.java, Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            if (param.hasThrowable()) return@runCatching
                            val view = param.result as? View ?: return@runCatching
                            if (view.context.packageName != HookConfig.SYSTEM_UI_PACKAGE) return@runCatching
                            discover(view)
                        }.onFailure { report("inflated keyguard", it) }
                    }
                })
            XposedHelpers.findAndHookMethod(View::class.java, "setVisibility", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val state = nativeIcons[param.thisObject as? View] ?: return@runCatching
                            if (state.applying) return@runCatching
                            state.visibility = param.args[0] as Int
                            if (state.hidden) param.args[0] = View.INVISIBLE
                        }.onFailure { report("native visibility", it) }
                    }
                })
        }.onFailure { report("install view discovery", it) }
        hook("com.android.systemui.statusbar.StatusBarStateControllerImpl") { clazz ->
            clazz.declaredMethods.filter { it.name == "setIsDozing" &&
                it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) }.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            if (param.hasThrowable()) return@runCatching
                            dozing = param.args[0] as Boolean
                            roots.keys.toList().forEach(::update)
                        }.onFailure { report("doze", it) }
                    }
                })
            }
        }
        Log.i("MotoLockIndicator", "Clock overlay discovery installed")
    }

    private fun discover(view: View) {
        val id = name(view)
        if (LockClockScalePolicy.isFace(id, view.javaClass.name)) observeFace(view)
        if (LockIndicatorPolicy.isNativeIcon(id, view.javaClass.name)) observeNative(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) discover(view.getChildAt(i))
    }

    private fun observeFace(view: View) {
        if (faces.put(view, true) != null) return
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                runCatching { (v.rootView as? ViewGroup)?.let(::observeRoot) }
                    .onFailure { report("attach clock", it) }
            }
            override fun onViewDetachedFromWindow(v: View) = Unit
        }
        view.addOnAttachStateChangeListener(listener)
        if (view.isAttachedToWindow) listener.onViewAttachedToWindow(view)
    }

    private fun observeNative(view: View) {
        val state = nativeIcons.getOrPut(view) { NativeIcon().apply { visibility = view.visibility } }
        if (state.observed) return
        state.observed = true
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                runCatching { (v.rootView as? ViewGroup)?.let(::observeRoot) }
                    .onFailure { report("attach native icon", it) }
            }
            override fun onViewDetachedFromWindow(v: View) {
                runCatching { hideNative(v, false) }.onFailure { report("detach native icon", it) }
            }
        }
        view.addOnAttachStateChangeListener(listener)
        if (view.isAttachedToWindow) listener.onViewAttachedToWindow(view)
    }

    private fun observeRoot(root: ViewGroup) {
        if (roots.containsKey(root)) { roots[root]?.scanNeeded = true; return }
        roots[root] = State()
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> roots[root]?.scanNeeded = true }
        val draw = ViewTreeObserver.OnPreDrawListener {
            runCatching { update(root) }.onFailure {
                runCatching { clear(root) }.onFailure { failure -> report("overlay rollback", failure) }
                report("overlay", it)
            }
            true
        }
        val attachment = object : View.OnAttachStateChangeListener {
            private var observer: ViewTreeObserver? = null
            override fun onViewAttachedToWindow(v: View) {
                runCatching {
                    observer = v.viewTreeObserver
                    observer?.addOnPreDrawListener(draw)
                    roots[root]?.scanNeeded = true
                }.onFailure { report("attach overlay", it) }
            }
            override fun onViewDetachedFromWindow(v: View) {
                runCatching {
                    observer?.let { if (it.isAlive) it.removeOnPreDrawListener(draw) }
                    observer = null
                    clear(root)
                }.onFailure { report("detach overlay", it) }
            }
        }
        root.addOnAttachStateChangeListener(attachment)
        if (root.isAttachedToWindow) attachment.onViewAttachedToWindow(root)
    }

    private fun update(root: ViewGroup) {
        val state = roots[root] ?: return
        val manager = root.context.getSystemService(KeyguardManager::class.java)
        if (!HookConfig.safeGetBoolean(modulePrefs, HookConfig.KEY_LOCK_ICON_ABOVE_CLOCK,
                HookConfig.DEFAULT_LOCK_ICON_ABOVE_CLOCK) || dozing ||
            manager?.isKeyguardLocked != true || KeyguardPanelVisibility.controlCenterVisible ||
            root.context.getSystemService(PowerManager::class.java)?.isInteractive != true) {
            clear(root)
            return
        }
        if (state.scanNeeded) { state.scanNeeded = false; discover(root) }
        val clock = faces.keys.filter { it.rootView === root && it.isShown && it.width > 0 &&
            it.height > 0 && generateSequence(it as View?) { v -> v.parent as? View }.all { v -> v.alpha > 0f } }
            .maxByOrNull { it.width.toLong() * it.height }
        if (clock == null) { clear(root); return }
        val transform = Matrix().apply { clock.transformMatrixToGlobal(this) }
        val inverse = Matrix()
        val rootMatrix = Matrix().apply { root.transformMatrixToGlobal(this) }
        if (!rootMatrix.invert(inverse)) { clear(root); return }
        val rect = RectF(0f, 0f, clock.width.toFloat(), clock.height.toFloat())
        transform.mapRect(rect)
        inverse.mapRect(rect)
        val d = root.resources.displayMetrics.density
        val safeTop = (root.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars() or
            WindowInsets.Type.displayCutout())?.top ?: 0).toFloat() + 2f * d
        val size = 22f * d
        // Clamp into available space instead of silently abandoning short clock layouts.
        val top = LockIndicatorPolicy.top(rect.top, safeTop, size, 10f * d)
        val left = (rect.centerX() - size / 2f).coerceIn(0f, (root.width - size).coerceAtLeast(0f))
        state.glyph.setBounds(left.roundToInt(), top.roundToInt(),
            (left + size).roundToInt(), (top + size).roundToInt())
        state.glyph.open = manager.isDeviceLocked.not()
        state.glyph.alpha = (clock.alpha * 255).roundToInt().coerceIn(0, 255)
        if (!state.added) {
            root.overlay.add(state.glyph)
            state.added = true
            Log.i("MotoLockIndicator", "Above-clock overlay attached; hiding bottom indicator")
        }
        nativeIcons.keys.toList().filter { it.rootView === root }.forEach { hideNative(it, true) }
    }

    private fun hideNative(view: View, hide: Boolean) {
        val state = nativeIcons[view] ?: return
        if (state.hidden == hide) return
        if (hide) state.visibility = view.visibility
        state.applying = true
        try {
            view.visibility = if (hide) View.INVISIBLE else state.visibility
            state.hidden = hide
        } finally { state.applying = false }
    }

    private fun clear(root: ViewGroup) {
        roots[root]?.let { if (it.added) { root.overlay.remove(it.glyph); it.added = false } }
        nativeIcons.keys.toList().filter { it.rootView === root }.forEach { hideNative(it, false) }
    }

    private fun name(view: View): String = if (view.id <= 0 || view.id ushr 24 == 0) ""
        else view.resources.getResourceEntryName(view.id)

    private fun hook(name: String, install: (Class<*>) -> Unit) {
        runCatching { XposedHelpers.findClassIfExists(name, appClassLoader)?.let(install) }
            .onFailure { report("install $name", it) }
    }

    private fun report(stage: String, error: Throwable) {
        if (errors.add(stage)) ErrorLogger.log("MotoLockIndicator", stage, error)
    }
}

private class FilledLockDrawable : Drawable() {
    var open = false
        set(value) { if (field != value) { field = value; invalidateSelf() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val shackle = Path()
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(4f, 10f, 20f, 23f, 2f, 2f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.strokeCap = Paint.Cap.ROUND
        shackle.reset()
        if (open) {
            shackle.moveTo(11f, 10f)
            shackle.lineTo(11f, 6f)
            shackle.cubicTo(11f, 0f, 21f, 0f, 21f, 6f)
        } else {
            shackle.moveTo(7f, 10f)
            shackle.lineTo(7f, 6f)
            shackle.cubicTo(7f, 0f, 17f, 0f, 17f, 6f)
            shackle.lineTo(17f, 10f)
        }
        canvas.drawPath(shackle, paint)
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) {
        if (paint.alpha != alpha) { paint.alpha = alpha; invalidateSelf() }
    }
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
