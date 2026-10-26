package com.motolauncher.mod.hooks

import android.appwidget.AppWidgetHostView
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.motolauncher.mod.ErrorLogger
import com.motolauncher.mod.HookConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** Clock, date and weather RemoteViews draw in the host, not in the provider's process. */
object MotoWidgetClockGlassHook : YukiBaseHooker() {
    private const val TAG = "MotoWidgetClockGlass"
    private const val FRAME = "motorolaTweak.motoWidgetGlassFrame"
    private val roots = WeakHashMap<AppWidgetHostView, Boolean>()
    private val states = WeakHashMap<View, State>()
    private val methods = mutableSetOf<Method>()
    private val errors = ConcurrentHashMap.newKeySet<String>()

    private class State(host: AppWidgetHostView) {
        val host = WeakReference(host)
        val node = RenderNode("Moto widget text and weather icons")
        var shader: RuntimeShader? = null
        var width = 0
        var height = 0
        var failed = false
        var recording = false
    }

    private data class Frame(val state: State, val canvas: Canvas)

    private fun enabled() = HookConfig.safeGetBoolean(modulePrefs,
        HookConfig.KEY_MOTO_WIDGET_CLOCK_LIQUID_GLASS, HookConfig.DEFAULT_MOTO_WIDGET_CLOCK_LIQUID_GLASS)

    override fun onHook() {
        runCatching {
            if (!enabled()) return
            RuntimeShader(SHADER) // Validate before installing any rendering hook.
            for (name in listOf("setAppWidget", "updateAppWidget")) {
                XposedBridge.hookAllMethods(AppWidgetHostView::class.java, name, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            if (!enabled() || param.hasThrowable()) return@runCatching
                            val host = param.thisObject as? AppWidgetHostView ?: return@runCatching
                            if (host.appWidgetInfo?.provider?.packageName != HookConfig.MOTO_WIDGET_PACKAGE) return@runCatching
                            observe(host)
                        }.onFailure { report("widget update", it) }
                    }
                })
            }
        }.onFailure { report("install", it) }
    }

    private fun observe(host: AppWidgetHostView) {
        scan(host, host)
        if (roots.put(host, true) != null) return
        // Async RemoteViews inflation and size changes are discovered on layout.
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            runCatching { if (enabled()) scan(host, host) }.onFailure { report("widget layout", it) }
        }
        val attachment = object : View.OnAttachStateChangeListener {
            private var observer: ViewTreeObserver? = null
            override fun onViewAttachedToWindow(v: View) {
                runCatching {
                    observer = v.viewTreeObserver
                    observer?.addOnGlobalLayoutListener(listener)
                    if (enabled()) scan(host, host)
                }.onFailure { report("attach", it) }
            }
            override fun onViewDetachedFromWindow(v: View) {
                runCatching {
                    observer?.let { if (it.isAlive) it.removeOnGlobalLayoutListener(listener) }
                    observer = null
                }.onFailure { report("detach", it) }
            }
        }
        host.addOnAttachStateChangeListener(attachment)
        if (host.isAttachedToWindow) attachment.onViewAttachedToWindow(host)
    }

    private fun scan(view: View, host: AppWidgetHostView) {
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) scan(view.getChildAt(index), host)
            return
        }
        if (view !is TextView && view !is ImageView) return
        val name = if (view.id == View.NO_ID) "" else
            runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
        if (!MotoWidgetClockScope.isGlassPart(name) || states.containsKey(view)) return
        val method = generateSequence(view.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { clazz -> clazz.declaredMethods.firstOrNull {
                it.name == "onDraw" && it.parameterTypes.contentEquals(arrayOf(Canvas::class.java))
            } }.firstOrNull() ?: return
        if (methods.add(method)) XposedBridge.hookMethod(method, drawHook)
        states[view] = State(host)
    }

    private fun onWorkspace(host: AppWidgetHostView): Boolean {
        var parent = host.parent
        while (parent != null) {
            if (parent.javaClass.name == "com.android.launcher3.Workspace") return true
            parent = parent.parent
        }
        return false
    }

    private val drawHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching {
                if (!enabled()) return@runCatching
                val view = param.thisObject as? View ?: return@runCatching
                val state = states[view] ?: return@runCatching
                val host = state.host.get() ?: return@runCatching
                if (!MotoWidgetClockScope.shouldApply(true, host.appWidgetInfo?.provider?.packageName,
                        onWorkspace(host))) return@runCatching
                val canvas = param.args[0] as? Canvas ?: return@runCatching
                if (state.failed || state.recording || !canvas.isHardwareAccelerated ||
                    view.width <= 0 || view.height <= 0) return@runCatching
                if (state.shader == null || state.width != view.width || state.height != view.height) {
                    val shader = state.shader ?: RuntimeShader(SHADER)
                    shader.setFloatUniform("size", view.width.toFloat(), view.height.toFloat())
                    shader.setFloatUniform("bevel", view.resources.displayMetrics.density.coerceAtLeast(1f))
                    state.node.setPosition(0, 0, view.width, view.height)
                    state.node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "glyph"))
                    state.shader = shader
                    state.width = view.width
                    state.height = view.height
                }
                val recording = state.node.beginRecording(view.width, view.height)
                state.recording = true
                param.setObjectExtra(FRAME, Frame(state, canvas))
                param.args[0] = recording
            }.onFailure { report("begin draw", it) }
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            runCatching {
                val frame = param.getObjectExtra(FRAME) as? Frame ?: return@runCatching
                param.args[0] = frame.canvas
                runCatching {
                    frame.state.node.endRecording()
                    frame.state.recording = false
                    if (!param.hasThrowable()) frame.canvas.drawRenderNode(frame.state.node)
                }.onFailure {
                    frame.state.failed = true
                    frame.state.recording = false
                    runCatching {
                        frame.state.node.setRenderEffect(null)
                        frame.canvas.drawRenderNode(frame.state.node)
                    }.onFailure { fallback -> report("stock fallback", fallback) }
                    report("finish draw", it)
                }
            }.onFailure { report("draw callback", it) }
        }
    }

    private fun report(stage: String, error: Throwable) {
        if (errors.add(stage)) ErrorLogger.log(TAG, stage, error)
    }

    private const val SHADER = """
        uniform shader glyph;
        uniform float2 size;
        uniform float bevel;
        half4 main(float2 p) {
            half4 src = glyph.eval(p);
            if (src.a <= 0.001) return half4(0.0);
            float2 normal = float2(
                glyph.eval(p - float2(bevel, 0.0)).a - glyph.eval(p + float2(bevel, 0.0)).a,
                glyph.eval(p - float2(0.0, bevel)).a - glyph.eval(p + float2(0.0, bevel)).a);
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
