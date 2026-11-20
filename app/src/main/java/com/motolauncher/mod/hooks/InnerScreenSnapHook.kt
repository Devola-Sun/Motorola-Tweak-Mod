package com.motolauncher.mod.hooks

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.motolauncher.mod.ErrorLogger
import com.motolauncher.mod.HookConfig
import com.motolauncher.mod.R
import com.motolauncher.mod.hooks.InnerSnapGeometry.Axis
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.io.PrintWriter
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WM objects are only read/mutated on WMS.mH under mGlobalLock. Views only live
 * on com.android.server.UiThread. Immutable snapshots cross this boundary;
 * clicks re-resolve focus, so stale overlays can never move a background task.
 * Unknown posture / ROM APIs fail closed. No polling, shell, or app overlay permission.
 */
object InnerScreenSnapHook : YukiBaseHooker() {
    private const val TAG = "InnerScreenSnap"
    private const val WM = "com.android.server.wm."
    private val failures = ConcurrentHashMap.newKeySet<String>()
    private val methodCache = ConcurrentHashMap<Class<*>, List<Method>>()
    private val fieldCache = ConcurrentHashMap<Class<*>, List<Field>>()
    private val queued = AtomicBoolean()
    private val refreshAgain = AtomicBoolean()
    private val positions = WeakHashMap<Any, Int>()
    private val controllerScope = ThreadLocal<Any?>()
    @Volatile private var service: Any? = null
    @Volatile private var wmHandler: Handler? = null
    @Volatile private var uiHandler: Handler? = null
    @Volatile private var settings = Options()
    @Volatile private var last: Snapshot? = null
    private var prefsAt = 0L
    private var receiverInstalled = false
    private var windowContext: Context? = null
    private var windowManager: WindowManager? = null
    private data class Control(val view: View, val params: WindowManager.LayoutParams)
    private val overlays = mutableMapOf<Int, Control>() // UiThread only, keyed by direction (0/2)
    private var rendered: Snapshot? = null
    private var uiBroken = false
    private var multiplierHooks = 0
    private var verticalMultiplierHooks = 0
    private var configuredActivity: Any? = null // WMS handler + global lock only
    private var configuredAxis: Axis? = null
    private var configuredRotation = -1
    @Volatile private var diagnostic = "initializing"

    private data class Options(val enabled: Boolean = false, val mode: String = "BOTH", val gestures: Boolean = false)
    private data class Snapshot(
        val task: Any, val activity: Any, val display: Display, val context: Context,
        val bounds: InnerSnapGeometry.Box, val safe: InnerSnapGeometry.Box,
        val freeform: Boolean, val vendorCompat: Boolean, val size: Int, val position: Int,
        val axis: Axis, val rotation: Int
    )

    override fun onHook() {
        guarded("install") {
            val wms = Class.forName(WM + "WindowManagerService", false, appClassLoader)
            XposedBridge.hookAllConstructors(wms, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    guarded("wms-created") { attach(param.thisObject) }
                }
            })
            observe("WindowManagerService", "systemReady", "enableScreenAfterBoot", "onDisplayChanged")
            observe("DisplayContent", "updateFocusedWindowLocked", "onConfigurationChanged",
                "setSleeping", "onDisplayChanged", "removeImmediately")
            observe("Task", "setBounds", "onConfigurationChanged", "removeImmediately")
            observe("ActivityRecord", "setVisibility", "onConfigurationChanged", "onRemovedFromDisplay")
            observe("ActivityTaskManagerService", "resizeTask", "setFocusedTask", "onSystemReady")
            observe("Letterbox", "layout", "applySurfaceChanges", "destroy")
            listOf("LetterboxUiController", "AppCompatReachabilityPolicy", "AppCompatLetterboxPolicy",
                "AppCompatAspectRatioPolicy", "AppCompatReachabilityOverrides")
                .forEach { installController(it) }
            installConfiguration("LetterboxConfiguration")
            installConfiguration("AppCompatConfiguration")
            // Posture change must also remove windows when focus/bounds are unchanged.
            observe("DeviceStateController", "onDeviceStateReceivedByDisplayManager", "onStateChanged")
            installVendorCompat()
            XposedBridge.hookAllMethods(wms, "dump", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    guarded("dump-diagnostics") {
                        val writer = param.args.filterIsInstance<PrintWriter>().firstOrNull()
                        writer?.println("InnerScreenSnap: revision=5 enabled=${settings.enabled} mode=${settings.mode} " +
                            "multiplierHooks=$multiplierHooks verticalMultiplierHooks=$verticalMultiplierHooks " +
                            "status=$diagnostic uiBroken=$uiBroken")
                    }
                }
            })
            android.util.Log.i(TAG, "Installed revision=5")
        }
    }

    private fun attach(wms: Any) {
        if (service != null) return
        val handler = field(wms, "mH") as? Handler ?: return
        val ui = call(Class.forName("com.android.server.UiThread", false, appClassLoader), "getHandler") as? Handler
            ?: return
        service = wms
        wmHandler = handler
        uiHandler = ui
        schedule()
    }

    private fun observe(shortName: String, vararg names: String) {
        guarded("observe-$shortName") {
            val type = optionalClass(shortName) ?: return@guarded
            names.forEach { name ->
                guarded("hook-$shortName-$name") {
                    XposedBridge.hookAllMethods(type, name, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            guarded("event-$shortName-$name") {
                                if (service == null) {
                                    val wms = if (shortName == "WindowManagerService") param.thisObject
                                        else field(param.thisObject, "mWmService", "mWindowManager")
                                    if (wms != null) attach(wms)
                                }
                                schedule()
                            }
                        }
                    })
                }
            }
        }
    }

    private fun installController(shortName: String) {
        guarded("controller-$shortName") {
            val type = optionalClass(shortName) ?: return@guarded
            listOf("isHorizontalReachabilityEnabled", "getHorizontalPositionMultiplier",
                "isVerticalReachabilityEnabled", "getVerticalPositionMultiplier").forEach { name ->
                val axis = if (name.contains("Vertical")) Axis.VERTICAL else Axis.HORIZONTAL
                methods(type).filter { it.declaringClass == type && it.name == name &&
                    it.returnType in listOf(Boolean::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                }.forEach { method ->
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            guarded("controller-before-$name") {
                                param.setObjectExtra(TAG, controllerScope.get())
                                controllerScope.set(field(param.thisObject, "mActivityRecord"))
                            }
                        }
                        override fun afterHookedMethod(param: MethodHookParam) {
                            guarded("controller-after-$name") {
                                try {
                                    val activity = field(param.thisObject, "mActivityRecord")
                                    if (!param.hasThrowable() && eligible(activity) && last?.axis == axis) {
                                        if (method.returnType == Boolean::class.javaPrimitiveType) param.result = true
                                        else positionFor(activity)?.let { param.result = it / 2f }
                                    }
                                } finally {
                                    controllerScope.set(param.getObjectExtra(TAG))
                                }
                            }
                        }
                    })
                    if (method.name == "getHorizontalPositionMultiplier" &&
                        method.returnType == Float::class.javaPrimitiveType) multiplierHooks++
                    if (method.name == "getVerticalPositionMultiplier" &&
                        method.returnType == Float::class.javaPrimitiveType) verticalMultiplierHooks++
                }
            }
            // Consume only native *horizontal* double-tap callbacks for our current
            // letterboxed activity. No full-screen touch interceptor is installed.
            methods(type).filter { it.declaringClass == type && it.name in
                listOf("handleHorizontalDoubleTap", "handleVerticalDoubleTap") &&
                it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
            }.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        guarded("margin-double-tap") {
                            val activity = field(param.thisObject, "mActivityRecord")
                            if (!eligible(activity)) return@guarded
                            val snapshot = last ?: return@guarded
                            val axis = if (method.name.contains("Vertical")) Axis.VERTICAL else Axis.HORIZONTAL
                            if (snapshot.axis != axis || snapshot.vendorCompat) return@guarded
                            val coordinate = param.args[0] as? Int ?: return@guarded
                            val start = if (axis == Axis.HORIZONTAL) snapshot.bounds.left else snapshot.bounds.top
                            val end = if (axis == Axis.HORIZONTAL) snapshot.bounds.right else snapshot.bounds.bottom
                            if (settings.gestures && (coordinate < start || coordinate > end)) {
                                val direction = if (coordinate < start) -1 else 1
                                move(snapshot, (snapshot.position + direction).coerceIn(0, 2))
                            }
                            param.result = null
                        }
                    }
                })
            }
        }
    }

    private fun installConfiguration(shortName: String) {
        guarded("configuration-$shortName") {
            val type = optionalClass(shortName) ?: return@guarded
            listOf("getIsHorizontalReachabilityEnabled", "isHorizontalReachabilityEnabled", "getLetterboxHorizontalPositionMultiplier",
                "getHorizontalMultiplierForReachability", "getIsVerticalReachabilityEnabled", "isVerticalReachabilityEnabled",
                "getLetterboxVerticalPositionMultiplier", "getVerticalMultiplierForReachability").forEach { name ->
                val axis = if (name.contains("Vertical")) Axis.VERTICAL else Axis.HORIZONTAL
                XposedBridge.hookAllMethods(type, name, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        guarded("configuration-$name") {
                            // Never override a global config value for other apps/displays.
                            val activity = controllerScope.get()
                            if (param.hasThrowable() || !eligible(activity) || last?.axis != axis) return@guarded
                            when (param.result) {
                                is Boolean -> param.result = true
                                is Float -> positionFor(activity)?.let { param.result = it / 2f }
                            }
                        }
                    }
                })
            }
        }
    }

    private fun eligible(activity: Any?): Boolean {
        val s = last ?: return false
        if (!settings.enabled || s.freeform || activity !== s.activity || settings.mode == "FREEFORM") return false
        if (s.display.rotation != s.rotation) return false
        if (!innerPosture()) return false
        if (call(activity, "getWindowingMode") != 1 || isEmbedded(activity)) return false
        val dc = call(activity, "getDisplayContent") ?: return false
        return field(field(dc, "mCurrentFocus"), "mActivityRecord") === activity
    }

    private fun positionFor(activity: Any?): Int? = synchronized(positions) { positions[activity] }

    private fun schedule() {
        val handler = wmHandler ?: return
        if (!queued.compareAndSet(false, true)) {
            refreshAgain.set(true)
            return
        }
        if (!handler.postDelayed({
                guarded("refresh") {
                    try {
                        refreshOptions()
                        val wms = service ?: return@guarded
                        val lock = field(wms, "mGlobalLock") ?: return@guarded
                        val context = field(wms, "mContext") as? Context
                        synchronized(lock) {
                            val next = if (settings.enabled) snapshot(wms) else {
                                diagnostic = "disabled-or-boot-incomplete"
                                null
                            }
                            last = next
                            val previous = configuredActivity
                            if (previous != null && (next?.activity !== previous || next.freeform ||
                                    next.axis != configuredAxis || next.rotation != configuredRotation)) {
                                // Drop scoped multipliers before restoring native configuration.
                                configuredActivity = null
                                synchronized(positions) { positions.remove(previous) }
                                recompute(previous)
                                invokeIfPresent(wms, "requestTraversal")
                                refreshAgain.set(true)
                            }
                            next
                        }
                        uiHandler?.post {
                            guarded("render") {
                                if (context != null && HookConfig.isBootCompleted()) installReceivers(context)
                                // Obsolete UI work must not hide newer eligible snapshots.
                                render(last)
                            }
                        }
                    } finally {
                        queued.set(false)
                        if (refreshAgain.getAndSet(false)) schedule()
                    }
                }
            }, 32L)) queued.set(false)
    }

    private fun refreshOptions() {
        if (!HookConfig.isBootCompleted()) {
            settings = Options()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - prefsAt < 750L) return
        prefsAt = now
        val prefs = modulePrefs // Deferred until after boot: LSPosed binder is not ready at bootstrap.
        settings = Options(
            HookConfig.safeGetBoolean(prefs, HookConfig.KEY_INNER_SNAP_ENABLED, false),
            HookConfig.InnerSnapMode.fromKey(HookConfig.safeGetString(prefs,
                HookConfig.KEY_INNER_SNAP_MODE, HookConfig.DEFAULT_INNER_SNAP_MODE)).key,
            HookConfig.safeGetBoolean(prefs, HookConfig.KEY_INNER_SNAP_DOUBLE_TAP, false)
        )
    }

    /** Called with WM global lock held. No View/WindowManager client operations here. */
    private fun snapshot(wms: Any): Snapshot? {
        diagnostic = "checking-context"
        val context = field(wms, "mContext") as? Context ?: return null
        val power = context.getSystemService(PowerManager::class.java) ?: return null
        val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return null
        diagnostic = "screen-off-or-keyguard"
        if (!power.isInteractive || keyguard.isKeyguardLocked) return null
        diagnostic = "posture-unavailable-or-closed"
        if (!innerPosture()) return null
        diagnostic = "no-focused-standard-activity"
        val root = field(wms, "mRoot") ?: return null
        val dc = call(root, "getTopFocusedDisplayContent") ?: return null
        val display = field(dc, "mDisplay") as? Display ?: return null
        if (display.displayId != Display.DEFAULT_DISPLAY || display.state != Display.STATE_ON) return null
        val focus = field(dc, "mCurrentFocus") ?: return null
        val activity = field(focus, "mActivityRecord") ?: return null
        if (call(activity, "isVisibleRequested") != true) return null
        val task = call(activity, "getTask") ?: return null
        diagnostic = "embedded-or-activity-mode-excluded"
        val taskMode = call(task, "getWindowingMode") as? Int ?: return null
        val activityMode = call(activity, "getWindowingMode") as? Int ?: return null
        if (!InnerSnapGeometry.supportedActivity(taskMode, activityMode, isEmbedded(activity))) return null
        // Excludes home/recents/assistant and avoids moving a non-top activity.
        if (call(task, "getActivityType") != 1) return null
        val top = call(task, "topRunningActivity") ?: call(task, "getTopNonFinishingActivity") ?: return null
        if (top !== activity) return null
        val mode = taskMode
        val freeform = mode == 5 // WindowConfiguration.WINDOWING_MODE_FREEFORM (hidden SDK).
        if (mode != 1 && !freeform) return null
        diagnostic = "mode-excluded"
        if (freeform && settings.mode == "LETTERBOX" || !freeform && settings.mode == "FREEFORM") return null
        val uiContext = call(wms, "getSystemUiContext") as? Context
            ?: field(wms, "mSystemUiContext") as? Context ?: context
        val displayContext = uiContext.createDisplayContext(display)
        val metrics = displayContext.resources.displayMetrics
        val density = metrics.density
        val full = call(dc, "getBounds") as? Rect ?: return null
        if (minOf(full.width(), full.height()) / density < 600f) return null
        // Prefer WM's stable safe rectangle. Unknown safe bounds => no controls.
        val policy = call(dc, "getDisplayPolicy") ?: field(dc, "mDisplayPolicy") ?: return null
        diagnostic = "safe-insets-unavailable"
        val stable = stableBounds(dc, policy, full) ?: return null
        val safeRect = Rect(stable)
        if (!safeRect.intersect(full) || safeRect.isEmpty) return null
        val config = call(activity, "getConfiguration") ?: return null
        val wc = field(config, "windowConfiguration") ?: return null
        // Use the complete activity bounds, not just content inset by system bars.
        // Otherwise a fullscreen app's status/navigation bars can look like a gap.
        val activityBounds = call(wc, "getBounds") as? Rect ?: return null
        diagnostic = "no-compatible-gap"
        // Hello UI's aspect-ratio compatibility display reports false even with
        // real horizontal app bounds (AliExpress: x=584..1648 on 2232px display).
        val vendorCompat = !freeform && call(activity, "areBoundsLetterboxed") == false
        val bounds = if (freeform) call(task, "getBounds") as? Rect ?: return null else activityBounds
        if (bounds.isEmpty) return null
        // Negative gaps / offscreen windows are not input-safe.
        val safe = safeRect.box()
        val box = Rect(bounds).box()
        val size = (48f * density + 0.5f).toInt()
        val axis = InnerSnapGeometry.axis(box, safe, size, freeform) ?: return null
        diagnostic = "no-multiplier-hook-$axis"
        if (!freeform && !vendorCompat &&
            (if (axis == Axis.HORIZONTAL) multiplierHooks else verticalMultiplierHooks) == 0) return null
        if (InnerSnapGeometry.controls(box, safe, size, axis).isEmpty()) return null
        // Store the stable system context, not a freshly allocated display context:
        // snapshot equality must suppress add/remove -> WM callback feedback loops.
        diagnostic = "eligible ${if (freeform) "freeform" else if (vendorCompat) "moto-compat" else "letterbox"} axis=$axis bounds=$box safe=$safe"
        return Snapshot(task, activity, display, uiContext, box, safe, freeform, vendorCompat, size,
            if (!freeform) positionFor(activity) ?: InnerSnapGeometry.position(box, safe, size / 4, axis)
            else InnerSnapGeometry.position(box, safe, size / 4, axis), axis, display.rotation)
    }

    private fun move(expected: Snapshot, position: Int) {
        wmHandler?.post {
            guarded("move") {
                refreshOptions()
                if (!settings.enabled) return@guarded
                val wms = service ?: return@guarded
                val lock = field(wms, "mGlobalLock") ?: return@guarded
                synchronized(lock) {
                    val current = snapshot(wms) ?: return@synchronized
                    if (current.task !== expected.task || current.activity !== expected.activity ||
                        current.freeform != expected.freeform || current.axis != expected.axis ||
                        current.rotation != expected.rotation) return@synchronized
                    if (current.freeform) {
                        val b = InnerSnapGeometry.snap(current.bounds, current.safe, position) ?: return@synchronized
                        val rect = Rect(b.left, b.top, b.right, b.bottom)
                        if (!invokeIfPresent(current.task, "setBounds", rect)) {
                            report("setBounds", IllegalStateException("No compatible Task.setBounds(Rect); skipped"))
                            return@synchronized
                        }
                    } else {
                        synchronized(positions) { positions[current.activity] = position.coerceIn(0, 2) }
                        if (!recompute(current.activity)) {
                            synchronized(positions) { positions.remove(current.activity) }
                            report("recompute", IllegalStateException("No ActivityRecord.recomputeConfiguration(); skipped"))
                            return@synchronized
                        }
                        configuredActivity = current.activity
                        configuredAxis = current.axis
                        configuredRotation = current.rotation
                    }
                    invokeIfPresent(current.task, "setLayoutNeeded")
                    invokeIfPresent(wms, "requestTraversal")
                }
                schedule()
            }
        }
    }

    private fun render(snapshot: Snapshot?) {
        try {
            if (snapshot == null || uiBroken) {
                hide()
                return
            }
            ensureUi(snapshot)
            val context = windowContext ?: return
            val power = context.getSystemService(PowerManager::class.java)
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            if (power?.isInteractive != true || keyguard?.isKeyguardLocked != false) {
                hide()
                return
            }
            if (rendered == snapshot) return
            val previous = rendered
            if (previous != null && (previous.activity !== snapshot.activity || previous.axis != snapshot.axis ||
                    previous.rotation != snapshot.rotation || previous.size != snapshot.size)) hide()
            val placements = InnerSnapGeometry.controls(snapshot.bounds, snapshot.safe, snapshot.size, snapshot.axis)
            overlays.keys.toList().filter { direction -> placements.none { it.first == direction } }
                .forEach { removeControl(it) }
            placements.forEach { (direction, bounds) ->
                val existing = overlays[direction]
                if (existing == null) addControls(snapshot, bounds, direction)
                else if (existing.params.x != bounds.left || existing.params.y != bounds.top ||
                    existing.params.width != bounds.width || existing.params.height != bounds.height) {
                    existing.params.x = bounds.left
                    existing.params.y = bounds.top
                    existing.params.width = bounds.width
                    existing.params.height = bounds.height
                    windowManager?.updateViewLayout(existing.view, existing.params)
                }
            }
            rendered = snapshot
        } catch (t: Throwable) {
            report("ui", t)
            uiBroken = true // No repeated addView failures / system_server exception loop.
            hide()
        }
    }

    private fun ensureUi(snapshot: Snapshot) {
        if (windowContext == null) {
            // System UI context has a theme and attribution suitable for internal windows.
            windowContext = snapshot.context.createDisplayContext(snapshot.display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT, null)
            windowManager = windowContext?.getSystemService(WindowManager::class.java)
        }
    }

    private fun installReceivers(context: Context) {
        if (!receiverInstalled) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
                addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    guarded("screen-receiver") {
                        // Receiver explicitly dispatched to UiThread; remove before refreshing.
                        // A normal configuration/prefs event is not a visibility loss.
                        // Unconditional removal here fed back into WM and caused flashing.
                        if (intent?.action in listOf(Intent.ACTION_SCREEN_OFF, Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) {
                            last = null
                            hide()
                        }
                        schedule()
                        if (intent?.action == HookConfig.ACTION_INNER_SNAP_REFRESH) {
                            // A second, bounded refresh outlives HookConfig's 750ms cache;
                            // do not continuously poll system_server for preferences.
                            wmHandler?.postDelayed({ guarded("prefs-notification") { schedule() } }, 850L)
                        }
                    }
                }
            }
            context.registerReceiver(receiver, filter, null, uiHandler, Context.RECEIVER_NOT_EXPORTED)
            // A module signature permission prevents other apps from triggering refresh storms.
            context.registerReceiver(receiver, IntentFilter(HookConfig.ACTION_INNER_SNAP_REFRESH),
                HookConfig.INNER_SNAP_REFRESH_PERMISSION, uiHandler, Context.RECEIVER_EXPORTED)
            receiverInstalled = true
        }
    }

    private fun addControls(snapshot: Snapshot, bounds: InnerSnapGeometry.Box, direction: Int) {
        val context = windowContext ?: return
        val wm = windowManager ?: return
        val vertical = snapshot.axis == Axis.VERTICAL
        val strings = try {
            context.moduleStringResources()
        } catch (t: Throwable) {
            report("control-strings", t)
            null
        }
        val group = LinearLayout(context).apply {
            orientation = if (vertical) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        // Two 48dp buttons in a narrow gap: direction + direct center action.
        // If already at an edge only the opposite gap exists and still exposes both.
        val actions = listOf(direction, 1)
        actions.forEach { target ->
            val button = TextView(context).apply {
                text = if (vertical) when (target) { 0 -> "▲"; 1 -> "↕"; else -> "▼" }
                    else when (target) { 0 -> "◀"; 1 -> "↔"; else -> "▶" }
                contentDescription = try {
                    strings?.getString(when {
                        target == 1 -> R.string.inner_snap_move_center
                        vertical && target == 0 -> R.string.inner_snap_move_top
                        vertical -> R.string.inner_snap_move_bottom
                        target == 0 -> R.string.inner_snap_move_left
                        else -> R.string.inner_snap_move_right
                    }) ?: text
                } catch (t: Throwable) {
                    report("control-strings", t)
                    text
                }
                gravity = Gravity.CENTER
                textSize = 22f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xD9333333.toInt())
                    setStroke(1, 0x66FFFFFF)
                }
                setOnClickListener {
                    guarded("button") {
                        val current = rendered ?: return@guarded
                        if (current.activity === snapshot.activity && current.axis == snapshot.axis &&
                            current.rotation == snapshot.rotation) move(current, target)
                    }
                }
            }
            group.addView(button, LinearLayout.LayoutParams(snapshot.size, snapshot.size))
        }
        val params = WindowManager.LayoutParams(bounds.width, bounds.height,
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            this.x = bounds.left
            this.y = bounds.top
            title = "MotoInnerSnap"
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        // Track before addView: even a partially-added window is cleaned up on failure.
        overlays[direction] = Control(group, params)
        wm.addView(group, params)
    }

    private fun hide() {
        overlays.keys.toList().forEach { removeControl(it) }
        rendered = null
    }

    private fun removeControl(direction: Int) {
        val control = overlays.remove(direction) ?: return
        guarded("remove-overlay") {
            control.view.visibility = View.GONE
            windowManager?.removeViewImmediate(control.view)
        }
    }

    private fun isEmbedded(activity: Any): Boolean {
        if (findMethod(activity, "isEmbedded", emptyArray()) != null && call(activity, "isEmbedded") == true) return true
        val fragment = call(activity, "getTaskFragment") ?: return false
        return call(fragment, "isEmbedded") == true
    }

    private fun Rect.box() = InnerSnapGeometry.Box(left, top, right, bottom)

    /** Android 15/16 replaced DisplayPolicy.getStableRect on some branches. */
    private fun stableBounds(dc: Any, policy: Any, full: Rect): Rect? {
        if (findMethod(policy, "getStableRect", emptyArray()) != null) {
            (call(policy, "getStableRect") as? Rect)?.let { return Rect(it) }
        }
        val out = Rect()
        if (invokeIfPresent(policy, "getStableRect", out) && !out.isEmpty) return out
        val controller = call(dc, "getInsetsStateController") ?: field(dc, "mInsetsStateController") ?: return null
        val state = call(controller, "getRawInsetsState") ?: return null
        val insets = call(state, "calculateInsets", full,
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(), true) as? android.graphics.Insets
            ?: return null
        return Rect(full.left + insets.left, full.top + insets.top,
            full.right - insets.right, full.bottom - insets.bottom)
    }

    private fun recompute(activity: Any): Boolean =
        invokeIfPresent(activity, "recomputeConfiguration") ||
            invokeIfPresent(activity, "recomputeConfiguration", false)

    /**
     * Motorola's compatibility path is not AOSP Letterbox. Adjust only this
     * activity's freshly resolved override, after the vendor has determined its
     * size. Never mutate parent/global configuration or a different activity.
     */
    private fun installVendorCompat() {
        guarded("vendor-compat-install") {
            val type = Class.forName(WM + "ActivityRecord", false, appClassLoader)
            XposedBridge.hookAllMethods(type, "resolveOverrideConfiguration", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    guarded("vendor-compat-resolve") {
                        if (param.hasThrowable()) return@guarded
                        val activity = param.thisObject
                        val current = last ?: return@guarded
                        if (!current.vendorCompat || !eligible(activity)) return@guarded
                        val position = positionFor(activity) ?: return@guarded
                        val config = call(activity, "getResolvedOverrideConfiguration") ?: return@guarded
                        val wc = field(config, "windowConfiguration") ?: return@guarded
                        val bounds = call(wc, "getBounds") as? Rect ?: return@guarded
                        val safe = current.safe
                        val start = InnerSnapGeometry.alignedStart(bounds.box(), safe, position, current.axis)
                            ?: return@guarded
                        val dx = if (current.axis == Axis.HORIZONTAL) start - bounds.left else 0
                        val dy = if (current.axis == Axis.VERTICAL) start - bounds.top else 0
                        if (dx == 0 && dy == 0) return@guarded
                        val original = Rect(bounds)
                        val app = (call(wc, "getAppBounds") as? Rect)?.let { Rect(it) }
                        val max = (call(wc, "getMaxBounds") as? Rect)?.let { Rect(it) }
                        // All three setters are known on WindowConfiguration; prepare
                        // first, and roll back a partial update if any setter fails.
                        val moved = Rect(original).apply { offset(dx, dy) }
                        val appMoved = app?.let { Rect(it).apply { offset(dx, dy) } }
                        val maxMoved = max?.takeIf { it == original }?.let { Rect(it).apply { offset(dx, dy) } }
                        if (!invokeIfPresent(wc, "setBounds", moved) ||
                            appMoved != null && !invokeIfPresent(wc, "setAppBounds", appMoved) ||
                            maxMoved != null && !invokeIfPresent(wc, "setMaxBounds", maxMoved)) {
                            invokeIfPresent(wc, "setBounds", original)
                            if (app != null) invokeIfPresent(wc, "setAppBounds", app)
                            if (max != null) invokeIfPresent(wc, "setMaxBounds", max)
                        }
                    }
                }
            })
        }
    }

    private fun innerPosture(): Boolean {
        val posture = field(service, "mDeviceStateController")
        var state = field(posture, "mCurrentDeviceState")?.toString()
        if (state == null) {
            val root = field(service, "mRoot") ?: return false
            val dc = call(root, "getTopFocusedDisplayContent") ?: return false
            val rotation = field(dc, "mDisplayRotation") ?: return false
            // Verified in the connected device's dumpsys: FoldController.mDeviceState=OPEN.
            state = field(field(rotation, "mFoldController"), "mDeviceState")?.toString()
        }
        return state == "OPEN" || state == "HALF_FOLDED"
    }

    private fun optionalClass(shortName: String): Class<*>? = try {
        Class.forName(WM + shortName, false, appClassLoader)
    } catch (t: Throwable) {
        report("class-$shortName", t)
        null
    }

    private fun methods(type: Class<*>): List<Method> = methodCache.getOrPut(type) {
        generateSequence(type) { it.superclass }.flatMap { it.declaredMethods.asSequence() }.toList()
    }

    private fun field(target: Any?, vararg names: String): Any? {
        if (target == null) return null
        return try {
            val fields = fieldCache.getOrPut(target.javaClass) {
                generateSequence(target.javaClass) { it.superclass }.flatMap { it.declaredFields.asSequence() }.toList()
            }
            for (name in names) {
                val f = fields.firstOrNull { it.name == name } ?: continue
                f.isAccessible = true
                val value = f.get(target)
                if (value != null) return value
            }
            null
        } catch (t: Throwable) {
            report("field-${target.javaClass.name}-${names.joinToString()}", t)
            null
        }
    }

    /** Match arguments by assignability, including inherited and vendor overloads. */
    private fun findMethod(target: Any, name: String, args: Array<out Any?>): Method? {
        val type = if (target is Class<*>) target else target.javaClass
        return methods(type).firstOrNull { method ->
            method.name == name && method.parameterTypes.size == args.size &&
                method.parameterTypes.indices.all { i ->
                    val value = args[i]
                    val p = method.parameterTypes[i]
                    if (value == null) !p.isPrimitive else p.isInstance(value) ||
                        p == Int::class.javaPrimitiveType && value is Int ||
                         p == Boolean::class.javaPrimitiveType && value is Boolean
                }
        }
    }

    private fun call(target: Any, name: String, vararg args: Any?): Any? = try {
        val method = findMethod(target, name, args)
        if (method == null) {
            report("missing-${if (target is Class<*>) target.name else target.javaClass.name}-$name-${args.size}",
                NoSuchMethodException(name))
            null
        } else {
            method.isAccessible = true
            method.invoke(if (target is Class<*>) null else target, *args)
        }
    } catch (t: Throwable) {
        report("call-${target.javaClass.name}-$name", t)
        null
    }

    private fun invokeIfPresent(target: Any, name: String, vararg args: Any?): Boolean = try {
        val method = findMethod(target, name, args)
        if (method == null) false else {
            method.isAccessible = true
            method.invoke(target, *args)
            true
        }
    } catch (t: Throwable) {
        report("invoke-${target.javaClass.name}-$name", t)
        false
    }

    private fun guarded(key: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) {
            report(key, t)
            // Do not leave eligibility or stale controls alive after a failed refresh.
            if (key == "refresh") {
                last = null
                try {
                    uiHandler?.post { guarded("hide-after-error") { hide() } }
                } catch (cleanup: Throwable) { report("cleanup-post", cleanup) }
            }
        }
    }

    private fun report(key: String, t: Throwable) {
        try {
            if (!failures.add(key)) return
            XposedBridge.log("$TAG: $key (unsupported APIs are skipped)")
            XposedBridge.log(t)
            ErrorLogger.log(TAG, key, t)
        } catch (_: Throwable) { /* Logging must never escape into system_server. */ }
    }
}
