package com.motolauncher.mod.hooks

import android.graphics.Point
import android.hardware.display.DisplayManager
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.motolauncher.mod.ErrorLogger
import com.motolauncher.mod.HookConfig
import com.motolauncher.mod.R
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap

/** Extends only SystemUI's WM Shell task menu, before Shell sizes and shows it. */
object ThreeColumnMenuHook : YukiBaseHooker() {
    private const val TAG = "ThreeColumnMenu"
    private const val MARKER = "moto-three-column-actions"
    private const val SHELL_DECOR = "com.android.wm.shell.windowdecor.WindowDecoration"
    private const val SURFACE = "shell-surface"
    private const val WINDOW = "system-window"
    private val reported = ConcurrentHashMap.newKeySet<String>()

    override fun onHook() {
        appClassLoader?.let { NativeThreeColumnShell.install(it) { enabled("native") } }
        safe("install-$SURFACE") {
            val type = XposedHelpers.findClassIfExists(SHELL_DECOR, appClassLoader)
            if (type == null) {
                infoOnce("missing-$SURFACE") { "Unavailable $SHELL_DECOR; keeping system-window path" }
                return@safe
            }
            // View overload only: after inflate/bind, before the windowless host and
            // its SurfaceControl crop receive the width/height. The resource overload
            // on AOSP delegates here; do not hook the two overloads independently.
            val targets = type.declaredMethods.filter { method ->
                val p = method.parameterTypes
                method.name == "addWindow" && p.size >= 6 && View::class.java.isAssignableFrom(p[0]) &&
                    p[1] == String::class.java && p.takeLast(4).all { it == Int::class.javaPrimitiveType }
            }
            targets.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        safe("bind-$SURFACE") {
                            if (param.args[1] != "Handle Menu") return@safe
                            val view = param.args[0] as? View ?: return@safe
                            if (view.context.packageName != HookConfig.SYSTEM_UI_PACKAGE) return@safe
                            infoOnce("seen-$SURFACE") { "Menu reached path=$SURFACE root=${view.javaClass.name}" }
                            if (!enabled(SURFACE)) return@safe
                            val task = XposedHelpers.getObjectField(param.thisObject, "mTaskInfo") ?: return@safe
                            val taskId = XposedHelpers.getIntField(task, "taskId")
                            val displayId = XposedHelpers.getIntField(task, "displayId")
                            val display = view.context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
                            val heightIndex = param.args.lastIndex
                            append(view, taskId, display, param.args[heightIndex] as Int, SURFACE) {
                                param.args[heightIndex] = it
                            }
                        }
                    }
                })
            }
            Log.i(TAG, "Hook path=$SURFACE methods=${targets.size}")
        }
        safe("install-$WINDOW") {
            val type = Class.forName("android.view.WindowManagerGlobal", false, appClassLoader)
            val hooks = XposedBridge.hookAllMethods(type, "addView", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    safe("bind-$WINDOW") {
                        val params = param.args.getOrNull(1) as? WindowManager.LayoutParams ?: return@safe
                        val taskId = ThreeColumnPolicy.menuTask(params.title?.toString().orEmpty()) ?: return@safe
                        val view = param.args[0] as? View ?: return@safe
                        if (view.context.packageName != HookConfig.SYSTEM_UI_PACKAGE) return@safe
                        infoOnce("seen-$WINDOW") { "Menu reached path=$WINDOW root=${view.javaClass.name}" }
                        if (!enabled(WINDOW)) return@safe
                        val display = param.args.getOrNull(2) as? Display ?: view.display
                        append(view, taskId, display, params.height, WINDOW) { params.height = it }
                    }
                }
            })
            Log.i(TAG, "Hook path=$WINDOW methods=${hooks.size}")
        }
    }

    private fun enabled(path: String): Boolean = when {
        !HookConfig.isBootCompleted() -> {
            infoOnce("boot-$path") { "Skipped path=$path reason=boot-incomplete" }
            false
        }
        !HookConfig.safeGetBoolean(modulePrefs, HookConfig.KEY_THREE_COLUMN_ENABLED, false) -> {
            infoOnce("pref-$path") { "Skipped path=$path reason=preference-off-or-unavailable" }
            false
        }
        else -> true
    }

    @Suppress("DEPRECATION") // The task-scoped View context may report window rather than display bounds.
    private fun append(view: View, taskId: Int, display: Display?, height: Int, path: String, resize: (Int) -> Unit) {
        if (taskId < 0) return
        val size = Point()
        display?.getRealSize(size)
        val density = if (display != null) {
            view.context.createDisplayContext(display).resources.displayMetrics.density
        } else 0f
        if (display?.displayId != Display.DEFAULT_DISPLAY || size.x <= size.y || density <= 0f ||
            size.y / density < 600f) {
            infoOnce("display-$path") { "Skipped path=$path reason=display id=${display?.displayId} size=$size density=$density" }
            return
        }
        // A retained or previously attached view can have different LayoutParams on
        // its next show. Do not append duplicates or extend the height a second time.
        if (view.isAttachedToWindow || view.findViewWithTag<View>(MARKER) != null) return
        val root = view as? ViewGroup
        val layout = root?.let(::findLayout)
        if (layout == null) {
            infoOnce("layout-$path") {
                "Unsupported shell menu path=$path height=$height: ${describe(view)}; callers=" +
                    Throwable().stackTrace.filter { it.className.startsWith("com.android.wm.shell.") }
                        .take(8).joinToString { "${it.className}.${it.methodName}" }
            }
            return
        }
        val context = view.context
        val rowHeight = (48 * density + 0.5f).toInt()
        val actions = NativeThreeColumnShell.menuActions(taskId)
        if (actions.isEmpty()) return
        val strings = context.moduleStringResources()
        val extra = rowHeight * actions.size + layout.topMargin
        val expanded = ThreeColumnPolicy.expandedMenuHeight(height, extra, size.y) ?: run {
            infoOnce("height-$path") { "Skipped path=$path reason=height native=$height extra=$extra display=${size.y}" }
            return
        }
        val group = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = MARKER
            background = layout.backgroundSource?.background?.constantState
                ?.newDrawable(resources, context.theme)?.mutate()
            elevation = layout.backgroundSource?.elevation ?: 0f
        }
        actions.forEach { action ->
            group.addView(TextView(context).apply {
                text = strings.getString(when (action) {
                    NativeThreeColumnShell.Action.START -> R.string.three_column_action_start
                    NativeThreeColumnShell.Action.ROLL -> R.string.three_column_action_roll
                    NativeThreeColumnShell.Action.EXIT -> R.string.three_column_action_exit
                })
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPaddingRelative((16 * density).toInt(), 0, (12 * density).toInt(), 0)
                layout.template?.let {
                    setTextColor(it.textColors)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, it.textSize)
                    typeface = it.typeface
                }
                val selectable = TypedValue()
                if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true) &&
                    selectable.resourceId != 0) setBackgroundResource(selectable.resourceId)
                maxLines = 2
                isFocusable = true
                setOnClickListener {
                    safe("click") {
                        if (!isAttachedToWindow || !enabled(path)) return@safe
                        // Only the known native collapse control is used for dismissal.
                        safe("collapse") { layout.collapse?.takeIf { it.hasOnClickListeners() }?.performClick() }
                        NativeThreeColumnShell.submit(action, taskId, context)
                    }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight))
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = layout.topMargin
        lp.marginStart = layout.startMargin
        try {
            layout.parent.addView(group, lp)
            resize(expanded)
        } catch (t: Throwable) {
            safe("rollback-$path") { layout.parent.removeView(group) }
            throw t
        }
        infoOnce("injected-$path") { "Injected path=$path task=$taskId layout=${layout.kind} height=$height->$expanded" }
    }

    private data class MenuLayout(
        val kind: String,
        val parent: LinearLayout,
        val template: TextView?,
        val backgroundSource: View? = null,
        val topMargin: Int = 0,
        val startMargin: Int = 0,
        val collapse: View? = null,
    )

    private fun findLayout(root: ViewGroup): MenuLayout? {
        val menu = descendants(root).filterIsInstance<LinearLayout>().firstOrNull {
            it.orientation == LinearLayout.VERTICAL && resourceName(it) == "handle_menu"
        }
        if (menu != null) {
            // Android 16: icon controls are in a *horizontal* pill. A separate pill
            // continues to work when Shell hides its optional more-actions pill.
            if (menu !== root || (menu.layoutParams?.height ?: 0) > 0) return null
            val windowing = (0 until menu.childCount).map { menu.getChildAt(it) }
                .firstOrNull { resourceName(it) == "windowing_pill" } as? LinearLayout ?: return null
            if (windowing.orientation != LinearLayout.HORIZONTAL || windowing.background?.constantState == null) return null
            val info = descendants(menu).firstOrNull { resourceName(it) == "application_name" } as? TextView
            val margin = windowing.layoutParams as? LinearLayout.LayoutParams
            return MenuLayout("handle-menu-pill", menu, info, windowing, margin?.topMargin ?: 0,
                margin?.marginStart ?: 0, descendants(menu).firstOrNull { resourceName(it) == "collapse_menu_button" })
        }
        // Earlier layouts: keep the original evidence requirement. A fixed-size
        // intermediate container would clip the new rows even with a taller host.
        val legacy = descendants(root).filterIsInstance<LinearLayout>().lastOrNull { parent ->
            if (parent.orientation != LinearLayout.VERTICAL || generateSequence<View>(parent) { it.parent as? View }
                    .takeWhile { it !== root }.any { (it.layoutParams?.height ?: 0) != ViewGroup.LayoutParams.WRAP_CONTENT }) {
                return@lastOrNull false
            }
            val names = descendants(parent).mapNotNull(::resourceName).toList()
            names.any { it.contains("split") } && names.any {
                it.contains("freeform") || it.contains("floating") || it.contains("desktop")
            }
        } ?: return null
        return MenuLayout("legacy-linear", legacy, descendants(legacy).filterIsInstance<TextView>().firstOrNull())
    }

    private fun describe(view: View) = descendants(view).take(32)
        .joinToString { "${it.javaClass.simpleName}#${resourceName(it) ?: "-"}" }

    private fun resourceName(view: View): String? = if (view.id == View.NO_ID) null else runCatching {
        view.resources.getResourceEntryName(view.id)
    }.getOrNull()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    private inline fun infoOnce(key: String, message: () -> String) {
        if (reported.add(key)) Log.i(TAG, message())
    }

    private inline fun safe(key: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            runCatching {
                if (reported.add("error-$key")) {
                    XposedBridge.log("$TAG: $key")
                    XposedBridge.log(t)
                    ErrorLogger.log(TAG, key, t)
                }
            }
        }
    }
}
