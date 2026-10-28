package com.motolauncher.mod.hooks

import android.app.KeyguardManager
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.os.PowerManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.motolauncher.mod.ErrorLogger
import com.motolauncher.mod.HookConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** Independent rendering hook. Never changes clock-size flows, layout or View RenderEffects. */
internal object LockClockGlass : YukiBaseHooker() {
    private const val TAG = "MotoLockClockGlass"
    private const val FRAME = "motorolaTweak.clockGlassFrame"
    private val errors = ConcurrentHashMap.newKeySet<String>()
    private val states = WeakHashMap<View, State>()
    private val roots = WeakHashMap<View, Boolean>()
    private val drawMethods = mutableSetOf<Method>()
    private var dozing = false

    private class State {
        val node = RenderNode("MotorolaTweak clock glyphs")
        var shader: RuntimeShader? = null
        var width = -1
        var height = -1
        var density = -1f
        var failed = false
        var recording = false
    }

    private data class Frame(val state: State, val original: Canvas)

    override fun onHook() {
        // Validate AGSL early: Kotlin compilation alone does not validate shaders.
        runCatching { RuntimeShader(GLASS_SHADER) }
            .onFailure { report("shader compilation", it) }

        // This wrapper is optional: default Android 16 clocks do not use Moto's plugin.
        hook("com.android.systemui.shared.plugins.MotoLsClockFacePlugin") { clazz ->
            clazz.declaredMethods.filter { it.name in setOf("onCreateView", "findClockView") }
                .forEach { method ->
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            runCatching {
                                if (param.hasThrowable()) return@runCatching
                                (param.result as? View)?.let { scan(it, true) }
                                param.args.filterIsInstance<View>().forEach { observeRoot(it, true) }
                            }.onFailure { report("Moto ${method.name}", it) }
                        }
                    })
                }
        }

        // AOSP's reactive pipeline exposes both clock faces here, including custom fonts
        // and the newer FlexClockView/SimpleDigitalClockTextView implementation.
        hook("com.android.systemui.shared.clocks.ClockRegistry") { clazz ->
            clazz.declaredMethods.filter { it.name == "createCurrentClock" && it.parameterCount == 0 }
                .forEach { method ->
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            runCatching {
                                if (param.hasThrowable()) return@runCatching
                                val controller = param.result ?: return@runCatching
                                listOf("getLargeClock", "getSmallClock").forEach { getter ->
                                    runCatching {
                                        val face = XposedHelpers.callMethod(controller, getter)
                                        val layout = XposedHelpers.callMethod(face, "getLayout")
                                        val views = XposedHelpers.callMethod(layout, "getViews") as? List<*>
                                        views?.filterIsInstance<View>()?.forEach { observeRoot(it, true) }
                                    }.onFailure { report("clock face $getter", it) }
                                }
                            }.onFailure { report("clock registry", it) }
                        }
                    })
                }
        }

        hook("com.android.systemui.plugins.clocks.DefaultClockFaceLayout") { clazz ->
            XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        if (!param.hasThrowable()) param.args.filterIsInstance<View>()
                            .forEach { observeRoot(it, true) }
                    }.onFailure { report("default clock face", it) }
                }
            })
        }

        hook("com.android.keyguard.AnimatableClockView") { clazz ->
            XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { observe(param.thisObject as View) }
                        .onFailure { report("observe legacy clock", it) }
                }
            })
        }

        // Resource-based fallback handles inlined/removed factory calls. Only
        // identified clock hosts are observed; no whole-panel per-frame traversal.
        runCatching {
            val inflate = LayoutInflater::class.java.getDeclaredMethod("inflate",
                Int::class.javaPrimitiveType, ViewGroup::class.java, Boolean::class.javaPrimitiveType)
            XposedBridge.hookMethod(inflate, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        if (param.hasThrowable()) return@runCatching
                        val root = param.result as? View ?: return@runCatching
                        if (root.context.packageName != HookConfig.SYSTEM_UI_PACKAGE) return@runCatching
                        discover(root)
                    }.onFailure { report("inflated clock hosts", it) }
                }
            })
        }.onFailure { report("inflater hook", it) }

        hook("com.android.systemui.statusbar.StatusBarStateControllerImpl") { clazz ->
            clazz.declaredMethods.filter {
                it.name == "setIsDozing" &&
                    it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
            }.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            if (!param.hasThrowable()) dozing = param.args[0] as Boolean
                        }.onFailure { report("doze state", it) }
                    }
                })
            }
        }
    }

    private fun enabled() = HookConfig.safeGetBoolean(modulePrefs,
        HookConfig.KEY_LOCK_CLOCK_LIQUID_GLASS, HookConfig.DEFAULT_LOCK_CLOCK_LIQUID_GLASS)

    private fun hook(name: String, install: (Class<*>) -> Unit) {
        runCatching {
            install(Class.forName(name, false, appClassLoader))
        }.onFailure {
            if (it !is ClassNotFoundException) report("install $name", it)
        }
    }

    private fun id(view: View): String = if (view.id == View.NO_ID) "" else
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")

    private fun discover(view: View) {
        if (LockClockGlassScope.isClockHost(id(view), view.javaClass.name)) {
            observeRoot(view, true)
        } else if (view is ViewGroup) {
            for (index in 0 until view.childCount) discover(view.getChildAt(index))
        }
    }

    private fun scan(view: View, insideFace: Boolean) {
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) scan(view.getChildAt(index), insideFace)
        } else if (LockClockGlassScope.isClockText(view.tag as? String, view.javaClass.name,
                id(view), insideFace, (view as? TextView)?.text)) {
            observe(view)
        }
    }

    private fun observeRoot(root: View, insideFace: Boolean) {
        if (roots.put(root, true) != null) return
        scan(root, insideFace)
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            runCatching { scan(root, insideFace) }.onFailure { report("clock layout discovery", it) }
        }
        val attachment = object : View.OnAttachStateChangeListener {
            private var observer: ViewTreeObserver? = null
            override fun onViewAttachedToWindow(v: View) {
                runCatching {
                    observer = v.viewTreeObserver
                    observer?.addOnGlobalLayoutListener(listener)
                    scan(v, insideFace)
                }.onFailure { report("clock host attach", it) }
            }
            override fun onViewDetachedFromWindow(v: View) {
                runCatching {
                    observer?.let { if (it.isAlive) it.removeOnGlobalLayoutListener(listener) }
                    observer = null
                }.onFailure { report("clock host detach", it) }
            }
        }
        root.addOnAttachStateChangeListener(attachment)
        if (root.isAttachedToWindow) attachment.onViewAttachedToWindow(root)
    }

    private fun observe(view: View) {
        if (states.containsKey(view)) return
        val method = generateSequence(view.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { clazz -> clazz.declaredMethods.firstOrNull {
                it.name == "onDraw" && it.parameterTypes.contentEquals(arrayOf(Canvas::class.java))
            } }.firstOrNull() ?: return
        if (drawMethods.add(method)) XposedBridge.hookMethod(method, drawHook)
        states[view] = State()
    }

    private val drawHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching {
                if (!enabled()) return@runCatching
                val view = param.thisObject as? View ?: return@runCatching
                val state = states[view] ?: return@runCatching
                if (state.failed || state.recording || view.width <= 0 || view.height <= 0) return@runCatching
                val canvas = param.args[0] as? Canvas ?: return@runCatching
                if (!LockClockGlassScope.shouldApply(true,
                        view.context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true,
                        dozing, view.context.getSystemService(PowerManager::class.java)?.isInteractive == true,
                        canvas.isHardwareAccelerated)) return@runCatching
                prepare(view, state)
                val recording = state.node.beginRecording(view.width, view.height)
                state.recording = true
                param.setObjectExtra(FRAME, Frame(state, canvas))
                param.args[0] = recording
            }.onFailure {
                (param.thisObject as? View)?.let { view -> states[view]?.failed = true }
                report("begin clock draw", it)
            }
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            val frame = param.getObjectExtra(FRAME) as? Frame ?: return
            runCatching {
                param.args[0] = frame.original
                frame.state.node.endRecording()
                frame.state.recording = false
                if (param.hasThrowable()) return@runCatching
                frame.original.drawRenderNode(frame.state.node)
            }.onFailure {
                frame.state.failed = true
                frame.state.recording = false
                // Keep the recorded stock glyphs visible if the effect fails.
                runCatching {
                    frame.state.node.setRenderEffect(null)
                    frame.original.drawRenderNode(frame.state.node)
                }.onFailure { failure -> report("stock draw fallback", failure) }
                report("finish clock draw", it)
            }
        }
    }

    private fun prepare(view: View, state: State) {
        val density = view.resources.displayMetrics.density
        if (state.shader != null && state.width == view.width && state.height == view.height &&
            state.density == density) return
        val shader = state.shader ?: RuntimeShader(GLASS_SHADER)
        shader.setFloatUniform("size", view.width.toFloat(), view.height.toFloat())
        shader.setFloatUniform("bevel", (density * 1.15f).coerceAtLeast(1f))
        state.node.setPosition(0, 0, view.width, view.height)
        state.node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "clock"))
        state.shader = shader
        state.width = view.width
        state.height = view.height
        state.density = density
    }

    private fun report(stage: String, error: Throwable) {
        if (errors.add(stage)) ErrorLogger.log(TAG, stage, error)
    }

    private const val GLASS_SHADER = """
        uniform shader clock;
        uniform float2 size;
        uniform float bevel;
        half4 main(float2 p) {
            half4 src = clock.eval(p);
            if (src.a <= 0.001) return half4(0.0);
            float left = clock.eval(p - float2(bevel, 0.0)).a;
            float right = clock.eval(p + float2(bevel, 0.0)).a;
            float top = clock.eval(p - float2(0.0, bevel)).a;
            float bottom = clock.eval(p + float2(0.0, bevel)).a;
            float2 normal = float2(left - right, top - bottom);
            float edge = clamp(length(normal) * 1.6, 0.0, 1.0);
            float light = dot(normal, normalize(float2(-0.6, -0.8)));
            float y = p.y / max(size.y, 1.0);
            float x = p.x / max(size.x, 1.0);
            float reflection = exp(-pow((y + x * 0.18 - 0.32) * 9.0, 2.0));
            half3 tint = src.rgb / max(src.a, half(0.001));
            half3 body = mix(tint, half3(0.88, 0.94, 1.0), half(0.26));
            body *= half(0.66 + 0.28 * (1.0 - y));
            body = mix(body, half3(1.0), half(reflection * 0.65));
            body += half3(half(max(light, 0.0) * 0.65));
            body *= half(1.0 - max(-light, 0.0) * 0.32);
            half alpha = src.a * half(0.70 + edge * 0.28);
            return half4(clamp(body, 0.0, 1.0) * alpha, alpha);
        }
    """
}
