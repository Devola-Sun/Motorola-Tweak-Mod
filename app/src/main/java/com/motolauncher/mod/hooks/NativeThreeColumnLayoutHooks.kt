package com.motolauncher.mod.hooks

import android.graphics.Matrix
import android.graphics.Rect
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.motolauncher.mod.hooks.InnerSnapGeometry.Box
import com.motolauncher.mod.hooks.NativeThreeColumnGeometry.Layout
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/** One live carousel, on the Shell looper. Don't change the ROM's snap or density state. */
internal class NativeThreeColumnLayoutHooks(
    private val looper: Looper,
    private val enabled: () -> Boolean,
    private val report: (String, Throwable) -> Unit,
) {
    private var session: Session? = null
    private val hooked = mutableSetOf<Method>()
    private val offscreenTouch = NativeThreeColumnOffscreenTouch({ looper.isCurrentThread }, { current() != null }, report)

    fun observeOffscreenTouch(loader: ClassLoader) {
        safe("offscreen-touch-install") { offscreenTouch.install(loader) }
    }
    private data class Drag(val index: Int, val pointer: Int, val x: Float, val start: Layout)

    private class Session(
        val coordinator: Any,
        val manager: Any,
        val layout: Any,
        val ownsLayout: () -> Boolean,
        val canDrag: () -> Boolean,
        val allowSurfaceFit: () -> Boolean,
        val writeTaskBounds: (Any, Layout) -> Boolean,
        val writeSurfaces: (Any, Layout) -> Boolean,
        val preview: () -> Unit,
        val commit: () -> Unit,
        val onCommitted: () -> Unit,
        var root: Box,
        var gap: Int,
        var geometry: Layout,
        var drag: Drag? = null,
    ) {
        var specsSynced = false
        var surfacesSynced = false
        var boundsScope = 0
        var specCaches = emptyList<Pair<Any, Field>>()
        val touchIndices = mutableSetOf<Int>()
    }

    fun begin(
        coordinator: Any, manager: Any, layout: Any, ownsLayout: () -> Boolean, canDrag: () -> Boolean, allowSurfaceFit: () -> Boolean,
        writeTaskBounds: (Any, Layout) -> Boolean, writeSurfaces: (Any, Layout) -> Boolean, onCommitted: () -> Unit,
    ): Boolean =
        safe("bind") {
            check(layout.javaClass.simpleName == "SplitLayoutCarousel3Apps") { "Unexpected carousel ${layout.javaClass.name}" }
            val root = root(manager)
            check(NativeThreeColumnGeometry.isLandscapeRoot(root)) { "Not a landscape display root" }
            val gap = field(manager, "mDividerSize") as Int
            val preview = layoutCallback(coordinator, manager, layout, "onLayoutSizeChanging", true)
                ?: layoutCallback(coordinator, manager, layout, "onLayoutPositionChanging")
            val commit = layoutCallback(coordinator, manager, layout, "onLayoutSizeChanged")
            checkNotNull(preview) { "No Shell divider preview callback" }
            checkNotNull(commit) { "No Shell divider resize callback" }
            val next = Session(coordinator, manager, layout, ownsLayout, canDrag, allowSurfaceFit, writeTaskBounds, writeSurfaces,
                preview, commit, onCommitted, root, gap, equal(root, gap))
            install(next)
            apply(next)
            session = next
            true
        } ?: false

    fun end() {
        session = null
        offscreenTouch.restore()
    }

    /** Refresh only carousel input when the display or transition changes eligibility. */
    fun refreshOffscreenTouch() = offscreenTouch.refresh()

    fun updateInitialBounds(action: () -> Unit) {
        val s = current()
        if (s == null) action() else withBoundsScope(s, action)
    }

    /** Refresh the two input windows once native enter has made them visible. */
    fun refreshTouch() {
        safe("touch-refresh") {
            offscreenTouch.refresh()
            val s = current()?.takeIf { it.canDrag() } ?: return@safe
            for (window in windows(s)) {
                val view = dividerView(window) ?: continue
                val i = index(view) ?: continue
                call(window, "setTouchRegion", touchRegion(s, i))
            }
        }
    }

    fun summary(): String = session?.let {
        "${it.geometry} stageSpecUpdated=${it.specsSynced} surfaceFitApplied=${it.surfacesSynced} " +
            "touchDividers=${it.touchIndices.sorted()} ${offscreenTouch.summary()}"
    } ?: "native fallback / not bound"

    private fun install(s: Session) {
        val loader = s.layout.javaClass.classLoader
        val divider = Class.forName("com.android.wm.shell.common.split.DividerView", false, loader)
        val window = Class.forName("com.android.wm.shell.common.split.SplitWindowManager", false, loader)
        val touch = divider.getDeclaredMethod("onTouch", View::class.java, MotionEvent::class.java)
        val region = window.getDeclaredMethod("setTouchRegion", Rect::class.java)
        val updateBounds = methods(s.layout).filter { it.name == "updateBounds" || it.name == "updateBounds\$1" }
        check(updateBounds.isNotEmpty()) { "Missing carousel bounds update" }
        updateBounds.forEach { method -> hook(method, after = { p ->
            current()?.takeIf { p.thisObject === it.layout }?.let(::apply)
        }) }
        val boundsConsumers = methods(s.coordinator).filter { method ->
            method.name in setOf("updateWindowBounds", "updateSurfaceBounds") &&
                (method.name == "updateSurfaceBounds" || method.parameterTypes.any { it.isInstance(s.manager) || it.isInstance(s.layout) })
        }
        check(boundsConsumers.any { method -> method.name == "updateWindowBounds" &&
            method.parameterTypes.any { it.name == "android.window.WindowContainerTransaction" } }) { "Missing stage WCT callback" }
        boundsConsumers.forEach { method ->
            val windowBounds = method.name == "updateWindowBounds"
            val transactionType = if (windowBounds) "android.window.WindowContainerTransaction" else "android.view.SurfaceControl\$Transaction"
            val transaction = method.parameterTypes.indexOfFirst { it.name == transactionType }
            val explicitLayout = method.parameterTypes.any { it.isInstance(s.manager) || it.isInstance(s.layout) }
            fun owner(p: XC_MethodHook.MethodHookParam) = current()?.takeIf {
                p.thisObject === it.coordinator && (!explicitLayout || p.args.any { arg -> arg === it.manager || arg === it.layout })
            }
            hook(method, before = { p ->
                owner(p)?.let(::apply)
            }, after = { p ->
                if (transaction < 0) return@hook
                val active = owner(p) ?: return@hook
                val tx = p.args[transaction] ?: return@hook
                if (!windowBounds) {
                    // Don't overwrite a native animation's leash transforms.
                    if ((active.boundsScope > 0 || active.allowSurfaceFit()) && active.writeSurfaces(tx, active.geometry)) {
                        active.surfacesSynced = true
                    }
                } else if (active.boundsScope > 0 || (active.allowSurfaceFit() && p.result != false)) {
                    // Only an explicit module entry/drag may force a resize on a native cache hit.
                    if (active.writeTaskBounds(tx, active.geometry) && active.boundsScope > 0 &&
                        method.returnType == Boolean::class.javaPrimitiveType) p.result = true
                }
            })
        }
        methods(s.layout).filter { method ->
            method.name in setOf("isNotMovableDivider", "isStageOffscreenInSnapPosition") &&
                method.returnType == Boolean::class.javaPrimitiveType &&
                method.parameterTypes.all { it == Int::class.javaPrimitiveType } &&
                method.parameterCount == (if (method.name == "isNotMovableDivider") 1 else 2)
        }.forEach { method -> hook(method, before = { p ->
            if (current()?.layout === p.thisObject &&
                (if (method.name == "isNotMovableDivider") (p.args[0] as Int) in 0..1
                else (p.args[0] as Int) in 6..7 && (p.args[1] as Int) in 0..2)) p.result = false
        }) }
        // Some methods return the spec, others fill a caller-supplied spec. Use the
        // ROM's default matrix/scale from a fresh instance, not a guessed field type.
        val owners = listOf(s.layout, s.manager, s.coordinator)
        s.specCaches = owners.flatMap { owner ->
            hierarchy(owner.javaClass).flatMap { it.declaredFields.asSequence() }
                .filter { !Modifier.isStatic(it.modifiers) &&
                    (it.type.isArray || List::class.java.isAssignableFrom(it.type)) && isSpec(it.genericType) }
                .map { owner to it.apply { isAccessible = true } }.toList()
        }
        val specMethods = owners.flatMap(::methods).distinct().filter { method ->
            isSpec(method.genericReturnType) || method.genericParameterTypes.any(::isSpec)
        }
        check(specMethods.isNotEmpty() || s.specCaches.isNotEmpty()) { "Missing StageUISpec geometry path" }
        specMethods.forEach { method ->
            val returnsSpec = isSpec(method.genericReturnType)
            val output = method.genericParameterTypes.indexOfFirst(::isSpec)
            fun sync(p: XC_MethodHook.MethodHookParam, specs: Any?) {
                val active = current()?.takeIf { p.thisObject === it.layout || p.thisObject === it.manager || p.thisObject === it.coordinator }
                    ?: return
                syncSpecs(active, specs, p.args.filterIsInstance<Int>().singleOrNull())
            }
            hook(method,
                before = { p -> if (!returnsSpec && output >= 0) sync(p, p.args[output]) },
                after = { p -> sync(p, if (returnsSpec) p.result else p.args[output]) })
        }
        hook(region, before = { p ->
            val active = current() ?: return@hook
            if (!active.canDrag()) return@hook
            val view = dividerView(p.thisObject) ?: return@hook
            val i = ownedIndex(active, view, p.thisObject) ?: return@hook
            p.args[0] = touchRegion(active, i)
            active.touchIndices += i
        })
        hook(touch, before = { p ->
            val active = current() ?: return@hook
            val i = ownedIndex(active, p.thisObject) ?: return@hook
            val event = p.args[1] as MotionEvent
            if (active.drag?.index?.let { it != i } == true) {
                p.result = true
                return@hook
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                if (!active.canDrag()) return@hook
                active.drag = Drag(i, event.getPointerId(0), event.rawX, active.geometry)
            }
            val drag = active.drag?.takeIf { it.index == i } ?: return@hook
            // Once we own DOWN, do not deliver an orphan MOVE/UP to native snap logic.
            p.result = true
            try {
                val action = event.actionMasked
                val pointer = event.findPointerIndex(drag.pointer)
                when {
                    action == MotionEvent.ACTION_CANCEL -> cancel(active, drag)
                    action == MotionEvent.ACTION_UP -> {
                        if (pointer >= 0) move(active, drag, event.getRawX(pointer))
                        if (active.drag === drag) {
                            active.drag = null
                            if (active.geometry != drag.start) {
                                withBoundsScope(active, active.commit)
                                safe("drag-finished", active.onCommitted)
                            }
                        }
                    }
                    pointer >= 0 && (action == MotionEvent.ACTION_MOVE ||
                        (action == MotionEvent.ACTION_POINTER_UP && pointer == event.actionIndex)) ->
                        move(active, drag, event.getRawX(pointer))
                }
            } catch (t: Throwable) {
                report("drag", t)
                safe("drag-restore") { cancel(active, drag) }
            }
        })
    }

    private fun move(s: Session, drag: Drag, x: Float) {
        val cut = if (drag.index == 0) drag.start.dividerAB.left else drag.start.dividerBC.left
        val position = (cut.toLong() - s.root.left + (x - drag.x).toInt())
            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        val next = NativeThreeColumnGeometry.moveDivider(s.root, s.gap, drag.start, drag.index, position) ?: return
        if (s.geometry == next) return
        s.geometry = next
        apply(s)
        if (s.drag === drag) withBoundsScope(s, s.preview)
    }

    private fun cancel(s: Session, drag: Drag) {
        s.drag = null
        if (s.geometry == drag.start) return
        s.geometry = drag.start
        apply(s)
        withBoundsScope(s, s.preview)
    }

    private fun withBoundsScope(s: Session, action: () -> Unit) {
        s.boundsScope++
        try { action() } finally { s.boundsScope-- }
    }

    private fun apply(s: Session) {
        val root = root(s.manager)
        if (!NativeThreeColumnGeometry.isLandscapeRoot(root)) return
        val gap = field(s.manager, "mDividerSize") as Int
        if (root != s.root || gap != s.gap) {
            s.geometry = equal(root, gap)
            s.root = root
            s.gap = gap
            s.drag = null
        }
        val cuts = listOf(s.geometry.dividerAB, s.geometry.dividerBC)
        val stages = s.geometry.stages.map(::rect)
        // Resolve/validate all caches before writing. Keep their Rect identities
        // intact because native code passes references to the same rectangles.
        val caches = listOf("mStageBounds", "mStageBoundsDisplayed", "mContentBounds").map { rectangles(s.layout, it, 3) }
        val dividers = rectangles(s.layout, "mDividerBounds", 2)
        val padding = dividers.map { padding(s, it) }
        val positions = checkNotNull(field(s.layout, "mDividerPositions"))
        val left = cuts[0].left - root.left
        val right = cuts[1].left - root.left
        when (positions) {
            is IntArray -> { check(positions.size >= 2); positions[0] = left; positions[1] = right }
            is MutableList<*> -> {
                @Suppress("UNCHECKED_CAST") val list = positions as MutableList<Int>
                check(list.size >= 2)
                val previous = list.set(0, left)
                try { list[1] = right } catch (t: Throwable) { list[0] = previous; throw t }
            }
            else -> error("Unsupported divider positions: ${positions.javaClass.name}")
        }
        caches.forEach { cache -> cache.zip(stages).forEach { (out, bounds) -> out.set(bounds) } }
        dividers.zip(cuts).forEachIndexed { i, (out, gapBox) ->
            out.set(gapBox.left - padding[i], gapBox.top, gapBox.right + padding[i], gapBox.bottom)
        }
        for ((owner, cache) in s.specCaches) {
            safe("stage-spec-cache") { syncSpecs(s, cache.get(owner), null) }
        }
    }

    private fun syncSpecs(s: Session, value: Any?, index: Int?) {
        val specs = when (value) {
            is Array<*> -> value.mapIndexed { i, spec -> i to spec }
            is List<*> -> value.mapIndexed { i, spec -> i to spec }
            null -> return
            else -> listOf((index ?: return) to value)
        }
        for ((i, spec) in specs) {
            val stage = s.geometry.stages.getOrNull(i) ?: continue
            if (spec == null) continue
            val before = field(spec, "boundsBeforeScale") as Rect
            val after = field(spec, "boundsAfterScale") as Rect
            val constructor = spec.javaClass.getDeclaredConstructor().apply { isAccessible = true }
            val identity = constructor.newInstance()
            val matrix = field(identity, "matrix")
            val scale = field(identity, "scaleHW")
            XposedHelpers.setObjectField(spec, "scaleHW", scale)
            val existing = field(spec, "matrix")
            if (existing is Matrix && matrix is Matrix) existing.set(matrix)
            else XposedHelpers.setObjectField(spec, "matrix", matrix)
            before.set(stage.left, stage.top, stage.right, stage.bottom)
            after.set(before)
            s.specsSynced = true
        }
    }

    private fun touchRegion(s: Session, index: Int): Rect {
        val pad = padding(s, rectangles(s.layout, "mDividerBounds", 2)[index])
        // The divider host includes transparent horizontal padding. Accept
        // touches only over the actual gap, in host-local coordinates.
        return Rect(pad, 0, pad + s.gap, s.root.bottom - s.root.top)
    }

    private fun padding(s: Session, bounds: Rect): Int =
        ((bounds.width() - s.gap) / 2).coerceIn(0, s.gap * 4)

    private fun ownedIndex(s: Session, view: Any, window: Any? = null): Int? {
        val i = index(view) ?: return null
        val direct = listOf("mSplitLayout", "mSplitLayoutManager").any {
            val link = optional(view, it)
            link === s.layout || link === s.manager
        }
        return i.takeIf { direct || windows(s).any { it === window || dividerView(it) === view } }
    }

    private fun index(view: Any): Int? = sequenceOf("mDividerIndex", "mIndex", "mDividerId")
        .mapNotNull { optional(view, it) as? Int }.firstOrNull { it in 0..1 }

    private fun dividerView(window: Any): Any? = optional(window, "mDividerView")

    private fun windows(s: Session): List<Any> = listOf(s.layout, s.manager).flatMap { owner ->
        hierarchy(owner.javaClass).flatMap { it.declaredFields.asSequence() }.filter {
            it.type.simpleName == "SplitWindowManager" || it.type.componentType?.simpleName == "SplitWindowManager" ||
                (List::class.java.isAssignableFrom(it.type) &&
                    (it.name.contains("window", true) || it.name.contains("divider", true)))
        }.flatMap { f ->
            when (val value = f.apply { isAccessible = true }.get(owner)) {
                is Array<*> -> value.asSequence().filterNotNull()
                is List<*> -> value.asSequence().filterNotNull()
                null -> emptySequence()
                else -> sequenceOf(value)
            }
        }.filter { it.javaClass.simpleName == "SplitWindowManager" }.toList()
    }

    private fun layoutCallback(sc: Any, manager: Any, layout: Any, name: String, resizing: Boolean = false): (() -> Unit)? {
        val method = methods(sc).firstOrNull {
            it.name == name && it.returnType == Void.TYPE && it.parameterTypes.let { types ->
                if (resizing) types.size == 4 && (types[0].isInstance(manager) || types[0].isInstance(layout)) &&
                    types[1] == Int::class.javaPrimitiveType && types[2] == Int::class.javaPrimitiveType &&
                    types[3] == Boolean::class.javaPrimitiveType
                else types.isEmpty() || (types.size == 1 && (types[0].isInstance(manager) || types[0].isInstance(layout)))
            }
        }?.apply { isAccessible = true } ?: return null
        val arg = if (method.parameterTypes.firstOrNull()?.isInstance(manager) == true) manager else layout
        return {
            if (resizing) method.invoke(sc, arg, 0, 0, false)
            else if (method.parameterCount == 0) method.invoke(sc)
            else method.invoke(sc, arg)
        }
    }

    private fun isSpec(type: Type): Boolean = when (type) {
        is Class<*> -> (type.componentType ?: type).simpleName == "StageUISpec"
        is ParameterizedType -> type.rawType.let { it is Class<*> && List::class.java.isAssignableFrom(it) } &&
            type.actualTypeArguments.singleOrNull()?.let(::isSpec) == true
        else -> false
    }

    private fun hierarchy(type: Class<*>): Sequence<Class<*>> = generateSequence(type) { it.superclass }
        .takeWhile { it != Any::class.java }
    private fun methods(owner: Any) = hierarchy(owner.javaClass).flatMap { it.declaredMethods.asSequence() }
        .filterNot { Modifier.isAbstract(it.modifiers) || Modifier.isStatic(it.modifiers) }.toList()

    private fun rectangles(owner: Any, name: String, count: Int): List<Rect> {
        val values = when (val cache = field(owner, name)) {
            is Array<*> -> cache.asList()
            is List<*> -> cache
            else -> error("Unsupported $name")
        }
        check(values.size >= count) { "Missing $name slots" }
        return values.take(count).map { it as Rect }
    }

    private fun root(manager: Any): Box = (call(manager, "getRootBounds") as Rect).let { Box(it.left, it.top, it.right, it.bottom) }
    private fun equal(root: Box, gap: Int): Layout {
        val ideal = checkNotNull(NativeThreeColumnGeometry.calculate(root, gap))
        return checkNotNull(NativeThreeColumnGeometry.fromDividers(root, gap, ideal.dividerAB.left - root.left, ideal.dividerBC.left - root.left))
    }
    private fun rect(b: Box) = Rect(b.left, b.top, b.right, b.bottom)
    private fun field(target: Any, name: String): Any? = XposedHelpers.getObjectField(target, name)
    private fun optional(target: Any, name: String): Any? = try { field(target, name) } catch (_: NoSuchFieldError) { null }
    private fun call(target: Any, name: String, vararg args: Any?): Any? = XposedHelpers.callMethod(target, name, *args)

    private fun current(): Session? = if (looper.isCurrentThread && enabled()) session?.takeIf {
        it.ownsLayout() && NativeThreeColumnGeometry.isLandscapeRoot(root(it.manager))
    } else null

    private fun hook(
        method: Method,
        before: ((XC_MethodHook.MethodHookParam) -> Unit)? = null,
        after: ((XC_MethodHook.MethodHookParam) -> Unit)? = null,
    ) {
        if (method in hooked) return
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (before != null) safe(method.name) { before(param) }
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                if (after != null) safe(method.name) { if (!param.hasThrowable()) after(param) }
            }
        })
        hooked += method
    }

    private inline fun <T> safe(where: String, block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        report("layout-$where", t)
        null
    }
}
