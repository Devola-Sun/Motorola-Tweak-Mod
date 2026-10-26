package com.motolauncher.mod.hooks

import android.view.View
import android.view.ViewGroup
import com.motolauncher.mod.ErrorLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field
import java.util.WeakHashMap

/** Adapts native view states, not notification data, groups, RemoteViews or touch handlers. */
internal object LockNotificationStack {
    private const val ROW = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
    private val errors = mutableSetOf<String>()
    private val fields = mutableMapOf<Pair<Class<*>, String>, Field?>()
    private val changed = WeakHashMap<Any, MutableMap<String, Change>>()
    private data class Change(val original: Any?, val applied: Any?)
    private data class Card(val state: Any, val height: Int)
    data class Metrics(val height: Int, val scrollRange: Int)
    var supported = false
        private set

    fun install(stack: Class<*>, isActive: (View) -> Boolean) {
        runCatching {
            val stateClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.stack.ExpandableViewState", stack.classLoader
            )
            listOf("yTranslation", "zTranslation", "alpha", "hidden", "inShelf", "clipTopAmount",
                "clipBottomAmount").forEach { name ->
                check(field(stateClass, name) != null) { "Missing notification state field: $name" }
            }
            val methods = stack.declaredMethods.filter {
                it.name in setOf("applyCurrentState", "startAnimationToState") && it.parameterCount == 0
            }
            // Without both paths a native animation could undo the stack or restore stale states.
            if (methods.map { it.name }.toSet().size != 2) {
                error("Native notification state application entry points unavailable")
            }
            methods.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val host = param.thisObject as? ViewGroup ?: return@runCatching
                            restoreStates(host)
                            if (isActive(host)) apply(host)
                        }.onFailure {
                            supported = false
                            runCatching { restoreStates(param.thisObject as ViewGroup) }
                                .onFailure { failure -> report("rollback", failure) }
                            report("view states", it)
                        }
                    }
                })
            }
            supported = true
        }.onFailure { report("install", it) }
    }

    fun metrics(host: ViewGroup): Metrics? {
        val cards = cards(host)
        if (cards.isEmpty()) return null
        val density = host.resources.displayMetrics.density
        val peek = 28f * density
        val heights = cards.map { it.height.toFloat() }
        return Metrics(
            (heights.maxOrNull()!! + peek * minOf(cards.lastIndex, 2) + 8f * density).toInt(),
            NotificationStackGeometry.scrollRange(heights, 10f * density).toInt()
        )
    }

    private fun apply(host: ViewGroup) {
        val cards = cards(host)
        if (cards.isEmpty()) return
        val density = host.resources.displayMetrics.density
        val gap = 10f * density
        val heights = cards.map { it.height.toFloat() }
        val scroll = (field(host.javaClass, "mOwnScrollY")?.getInt(host) ?: 0).toFloat()
        val placements = NotificationStackGeometry.place(heights, scroll, gap, 28f * density)
        cards.zip(placements).forEach { (card, placement) ->
            // Only fields used by stock ExpandableViewState are touched. Native X translation,
            // swipe progress, content selection and group expansion are not overridden.
            write(card.state, "yTranslation", placement.y)
            write(card.state, "zTranslation", placement.depth * 3f * density)
            // Some vendor ViewStates omit scale; overlap and scrolling still work there.
            if (field(card.state.javaClass, "scaleX") != null) write(card.state, "scaleX", placement.scale)
            if (field(card.state.javaClass, "scaleY") != null) write(card.state, "scaleY", placement.scale)
            write(card.state, "alpha", placement.alpha)
            write(card.state, "hidden", !placement.visible)
            write(card.state, "inShelf", false)
            write(card.state, "clipTopAmount", 0)
            write(card.state, "clipBottomAmount", 0)
        }
        for (index in 0 until host.childCount) {
            val child = host.getChildAt(index)
            // The system shelf/footer belong to the full list, not the lock-screen deck.
            if (child.javaClass.simpleName in setOf("NotificationShelf", "FooterView", "EmptyShadeView")) {
                viewState(child)?.let { state ->
                    write(state, "alpha", 0f)
                    write(state, "hidden", true)
                }
            }
        }
    }

    fun restoreStates(host: ViewGroup) {
        for (index in 0 until host.childCount) {
            val state = viewState(host.getChildAt(index)) ?: continue
            val changes = changed.remove(state) ?: continue
            changes.forEach { (name, change) ->
                val field = field(state.javaClass, name) ?: return@forEach
                // A new native algorithm pass is authoritative; never restore over it.
                if (field.get(state) == change.applied) field.set(state, change.original)
            }
        }
    }

    private fun cards(host: ViewGroup): List<Card> = buildList {
        for (index in 0 until host.childCount) {
            val view = host.getChildAt(index)
            if (view.visibility == View.GONE) continue
            val isRow = generateSequence(view.javaClass as Class<*>?) { it.superclass }.any { it.name == ROW }
            val isMedia = view.javaClass.simpleName == "MediaContainerView"
            if (!isRow && !isMedia) continue
            val state = viewState(view) ?: continue
            if (field(state.javaClass, "gone")?.getBoolean(state) == true) continue
            val height = (XposedHelpers.callMethod(view, "getIntrinsicHeight") as? Int) ?: continue
            if (height > 0) add(Card(state, height))
        }
    }

    private fun viewState(view: View): Any? =
        field(view.javaClass, "mViewState")?.get(view)

    private fun write(target: Any, name: String, value: Any) {
        val field = field(target.javaClass, name) ?: error("Missing notification state field: $name")
        val old = field.get(target)
        changed.getOrPut(target) { mutableMapOf() }[name] = Change(old, value)
        if (old != value) field.set(target, value)
    }

    private fun field(type: Class<*>, name: String): Field? = fields.getOrPut(type to name) {
        generateSequence(type as Class<*>?) { it.superclass }
            .mapNotNull { clazz -> clazz.declaredFields.firstOrNull { it.name == name } }
            .firstOrNull()?.apply { isAccessible = true }
    }

    private fun report(stage: String, error: Throwable) {
        if (errors.add(stage)) ErrorLogger.log("MotoNotificationFrost", "Lock stack $stage", error)
    }
}
