package com.motolauncher.mod.hooks

import android.app.ActivityManager.RunningTaskInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.SparseArray
import android.view.Display
import android.view.SurfaceControl
import android.widget.Toast
import com.motolauncher.mod.ErrorLogger
import com.motolauncher.mod.R
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.PrintWriter
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/** Razr Fold: select locally, then ask the initialized WM Shell to enter once. */
internal object NativeThreeColumnShell {
    private const val TAG = "NativeThreeColumn"
    private const val SPLIT = "com.android.wm.shell.splitscreen."
    private const val TIMEOUT_MS = 60_000L
    private const val COMMIT_TIMEOUT_MS = 8_000L
    private const val STAGE_A = 2
    private const val STAGE_B = 3
    private const val STAGE_C = 4
    private const val FULLSCREEN_REQUEST = 13 // exitReasonToString() on the device
    private const val THREE_STAGE_SNAP = 6 // Detached three-child enter reached finishEnterSplitScreen.
    private const val THREE_STAGE_COUNT = 3 // SplitLayoutManager.prepareSplitLayout takes a count, not a snap.
    private const val ENTER_TRANSIT = 1004
    private const val STANDARD = 1
    private const val FULLSCREEN = 1
    enum class Action { START, ROLL, EXIT }

    private val errors = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var bridge: Bridge? = null
    @Volatile private var installation = "not installed"
    private var isEnabled: () -> Boolean = { false }

    fun install(loader: ClassLoader, enabled: () -> Boolean) {
        installation = "initializing"
        try {
            installHooks(loader, enabled)
        } catch (t: Throwable) {
            installation = "hook installation failed"
            report("install", t)
        }
    }

    private fun installHooks(loader: ClassLoader, enabled: () -> Boolean) {
        isEnabled = enabled
        val controller = Class.forName(SPLIT + "SplitScreenController", false, loader)
        val coordinator = Class.forName(SPLIT + "StageCoordinator", false, loader)
        val stageListener = Class.forName(SPLIT + "StageTaskListener", false, loader)
        val bool = Boolean::class.javaPrimitiveType!!
        val int = Int::class.javaPrimitiveType!!
        val batch = BatchApi(loader, coordinator, stageListener, int, bool)
        val roll = coordinator.getDeclaredMethod("rollingStages", bool).apply { isAccessible = true }
        val exit = controller.getDeclaredMethod("exitSplitScreen", int, int).apply { isAccessible = true }
        safe("back-observer") {
            val back = Class.forName("com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler\$5", false, loader)
            after(back, "triggerBack") { bridge?.enqueueCancelSelection("back") }
        }
        after(coordinator, "onRootTaskVanished") { p -> bridge?.takeIf { it.matches(p.thisObject) }?.reset("root vanished") }
        after(coordinator, "onFoldedStateChanged", bool) { p ->
            if (p.args.singleOrNull() == true) bridge?.takeIf { it.matches(p.thisObject) }?.reset("fold")
        }
        after(coordinator, "onTransitionAnimationComplete") { p ->
            bridge?.takeIf { it.matches(p.thisObject) }?.enqueueRefresh()
        }
        after(coordinator, "onTransitionConsumed", IBinder::class.java, bool, Class.forName("android.view.SurfaceControl\$Transaction", false, loader)) { p ->
            bridge?.takeIf { it.matches(p.thisObject) }?.onConsumed(p.args[0], p.args[1] == true)
        }
        after(coordinator, "onChildTaskStatusChanged", stageListener, int, bool, bool) { p ->
            val current = bridge?.takeIf { it.matches(p.thisObject) } ?: return@after
            // Read a coherent snapshot after all organizer callbacks for this transaction.
            current.enqueueRefresh()
        }
        safe("batch-finish-observer") {
            val wct = Class.forName("android.window.WindowContainerTransaction", false, loader)
            val transitions = Class.forName(SPLIT + "SplitScreenTransitions", false, loader)
            after(transitions, "onFinish", wct) { p ->
                bridge?.takeIf { field(p.thisObject, "mStageCoordinator")?.let(it::matches) == true }?.enqueueRefresh()
            }
        }
        safe("transition-trace") {
            val wct = Class.forName("android.window.WindowContainerTransaction", false, loader)
            val remote = Class.forName("android.window.RemoteTransition", false, loader)
            val transitions = Class.forName(SPLIT + "SplitScreenTransitions", false, loader)
            val handler = Class.forName("com.android.wm.shell.transition.Transitions\$TransitionHandler", false, loader)
            fun record(sc: Any?, label: String, transaction: Any?, detail: String) {
                val current = bridge ?: return
                if (sc == null || !current.matches(sc) || !isEnabled()) return
                val ops = transaction?.let { (call(it, "getHierarchyOps") as? List<*>)?.size }
                val changes = transaction?.let { (call(it, "getChanges") as? Map<*, *>)?.size }
                current.traceTransition("$label $detail ops=$ops changes=$changes")
            }
            after(coordinator, "activateSplit", int, int, wct, bool) { p ->
                record(p.thisObject, "activateSplit", p.args[2], "ints=${p.args[0]},${p.args[1]} flag=${p.args[3]}")
            }
            after(coordinator, "setupActiveStagesReady", int, wct) { p ->
                record(p.thisObject, "setupActiveStagesReady", p.args[1], "layout=${p.args[0]}")
            }
            after(coordinator, "prepareEnterSplitScreen", wct, RunningTaskInfo::class.java, int, bool, int, int) { p ->
                val taskId = (p.args[1] as? RunningTaskInfo)?.taskId
                record(p.thisObject, "prepareEnterSplitScreen", p.args[0],
                    "task=$taskId ints=${p.args[2]},${p.args[4]},${p.args[5]} flag=${p.args[3]}")
            }
            after(coordinator, "prepareExitSplitScreen", int, int, wct) { p ->
                record(p.thisObject, "prepareExitSplitScreen", p.args[2], "ints=${p.args[0]},${p.args[1]}")
            }
            after(transitions, "startEnterTransition", wct, remote, handler, int, bool, int) { p ->
                record(field(p.thisObject, "mStageCoordinator"), "startEnterTransition", p.args[0],
                    "ints=${p.args[3]},${p.args[5]} flag=${p.args[4]} remote=${p.args[1] != null}")
            }
        }
        safe("dump-observer") {
            after(coordinator, "dump\$2", PrintWriter::class.java, String::class.java) { p ->
                if (field(p.thisObject, "mDisplayId") != Display.DEFAULT_DISPLAY) return@after
                val current = bridge?.takeIf { it.ownsCoordinator(p.thisObject) }
                val writer = p.args[0] as PrintWriter
                val prefix = p.args[1]
                writer.println("${prefix}NativeThreeColumn ${current?.diagnostic() ?: installation}")
                current?.transitionTrace()?.forEach { writer.println("${prefix}NativeThreeColumn trace $it") }
            }
        }

        fun observeController(instance: Any, source: String) {
            val handler = field(instance, "mMainHandler") as? Handler ?: return
            val context = field(instance, "mContext") as? Context ?: return
            // Use one Shell handler for both initialization and requests. In
            // particular, wait until the controller has a default-display coordinator.
            handler.post {
                safe("controller-$source") {
                    val shell = field(instance, "mStageCoordinator") ?: return@safe
                    if (field(shell, "mDisplayId") != Display.DEFAULT_DISPLAY) return@safe
                    synchronized(this@NativeThreeColumnShell) {
                        if (bridge != null) return@safe
                        val next = Bridge(instance, handler, context, batch,
                            { sc -> roll.invoke(sc, true) },
                            { task -> exit.invoke(instance, task, FULLSCREEN_REQUEST) })
                        bridge = next
                        try {
                            next.attach(loader)
                            Log.i(TAG, "Controller attached via $source")
                        } catch (t: Throwable) {
                            next.attachmentFailed()
                            report("attach", t)
                        }
                    }
                }
            }
        }

        after(controller, "onInit") { p -> observeController(p.thisObject, "onInit") }
        safe("binder-observer") {
            val binder = Class.forName(SPLIT + "SplitScreenController\$ISplitScreenImpl", false, loader)
            XposedBridge.hookMethod(binder.getDeclaredConstructor(controller), object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = safe("binder-created") {
                    if (!param.hasThrowable()) observeController(param.args[0], "ISplitScreenImpl")
                }
            })
        }
        installation = "waiting for SplitScreenController"
        Log.i(TAG, "Controller initialization hooks installed")
    }

    fun submit(action: Action, taskId: Int, context: Context) = safe("dispatch") {
        val current = bridge
        if (current == null) {
            Log.w(TAG, "Request rejected: $installation")
            val message = if (installation == "waiting for SplitScreenController") {
                R.string.three_column_initializing
            } else R.string.three_column_hooks_unavailable
            showToast(context) { it.getString(message) }
        } else current.enqueue(action, taskId)
    }

    fun menuActions(taskId: Int): List<Action> = try {
        if (taskId in bridge?.activeMenuTasks.orEmpty()) listOf(Action.ROLL, Action.EXIT) else listOf(Action.START)
    } catch (t: Throwable) {
        report("menu-actions", t)
        emptyList()
    }

    /** Resolve every mutating entry point before touching native split state. */
    private class BatchApi(loader: ClassLoader, coordinator: Class<*>, stage: Class<*>, int: Class<*>, bool: Class<*>) {
        private val wct = Class.forName("android.window.WindowContainerTransaction", false, loader)
        private val token = Class.forName("android.window.WindowContainerToken", false, loader)
        private val manager = Class.forName("com.android.wm.shell.common.split.SplitLayoutManager", false, loader)
        private val transitions = Class.forName(SPLIT + "SplitScreenTransitions", false, loader)
        private val remote = Class.forName("android.window.RemoteTransition", false, loader)
        private val handler = Class.forName("com.android.wm.shell.transition.Transitions\$TransitionHandler", false, loader)
        val transaction = wct.getConstructor()
        val setup = coordinator.getMethod("setupActiveStagesReady", int, wct)
        val prepareLayout = manager.getMethod("prepareSplitLayout", int)
        val currentLayout = manager.getField("mSplitLayout")
        val stageCount = currentLayout.type.getMethod("getStageCount")
        val requestHorizontal = manager.getMethod("checkAndRequestLeftRightSplitFor3App", String::class.java, bool)
        val requestDirection = manager.getMethod("requestLeftRightSplit", bool, bool, bool)
        val orientationDemand = manager.getField("mSplitOrientationOnDemand")
        val windowBounds = coordinator.getMethod("updateWindowBounds", manager, wct)
        val addTask = stage.getMethod("addTask", RunningTaskInfo::class.java, wct)
        val setRootForceTranslucent = coordinator.getMethod("setRootForceTranslucent", wct, bool)
        val reorder = wct.getMethod("reorder", token, bool)
        val setBounds = wct.getMethod("setBounds", token, Rect::class.java)
        val setScreenSize = wct.getMethod("setScreenSizeDp", token, int, int)
        val setSmallestWidth = wct.getMethod("setSmallestScreenWidthDp", token, int)
        val setRelaunch = wct.getMethod("setReparentLeafTaskIfRelaunch", token, bool)
        val start = transitions.getMethod("startEnterTransition", wct, remote, handler, int, bool, int)
        val abortPreparation = coordinator.getMethod("prepareExitSplitScreen", int, int, wct)
    }

    private class Bridge(
        private val controller: Any,
        private val handler: Handler,
        private val context: Context,
        private val batch: BatchApi,
        private val roll: (Any) -> Unit,
        private val exit: (Int) -> Unit,
    ) {
        private val flow = NativeThreeColumnFlow()
        private val layoutHooks = NativeThreeColumnLayoutHooks(handler.looper, { isEnabled() }, ::report)
        private val picker = NativeThreeColumnPicker(context) { report("picker", it) }
        private var deadline = 0L
        private var session = 0L
        private var candidates = emptyMap<Int, Candidate>()
        private var committedTokens = emptyMap<Int, Any>()
        private var ownedTransition: IBinder? = null
        private var recoveryReason: String? = null
        private data class Direction(val manager: Any, val leftRight: Boolean, val demand: Int, val rotation: Int)
        private var direction: Direction? = null
        @Volatile private var batchDetail = "none"
        @Volatile private var geometry = "none"
        @Volatile var activeMenuTasks: Set<Int> = emptySet()
            private set
        private var unfolded = false
        private var ready = false
        private var refreshPosted = false
        private val timeout = Runnable { safe("timeout") {
            if (flow.selecting) reset("picker timed out")
            else if (flow.phase == NativeThreeColumnFlow.Phase.COMMITTING) {
                refresh()
                if (flow.phase == NativeThreeColumnFlow.Phase.COMMITTING) recover("enter timed out")
            }
        } }
        @Volatile var status = "IDLE (waiting for posture callback)"
            private set
        @Volatile private var lastRequest = "none"
        @Volatile private var lastHandoff = "none"
        @Volatile private var lastNavigation = "none"
        private val trace = ArrayDeque<String>()

        fun diagnostic() = "batch-v4.5 $status; request=$lastRequest; commit=$lastHandoff; navigation=$lastNavigation; batch=$batchDetail; geometry=$geometry"

        fun traceTransition(event: String) = synchronized(trace) {
            if (trace.size == 12) trace.removeFirst()
            trace.addLast("${SystemClock.uptimeMillis()} $event")
        }

        fun transitionTrace(): List<String> = synchronized(trace) { trace.toList() }

        private fun shell(): Any? = field(controller, "mStageCoordinator")

        fun ownsCoordinator(value: Any) = shell() === value
        fun matches(value: Any) = handler.looper.isCurrentThread && ownsCoordinator(value)

        fun attachmentFailed() { status = "IDLE (environment observer setup failed)" }

        @Suppress("DEPRECATION") // The ROM still broadcasts Home/Overview via CLOSE_SYSTEM_DIALOGS.
        fun attach(loader: ClassLoader) {
            // Observe named overlay channels before preparing any new split layout.
            layoutHooks.observeOffscreenTouch(loader)
            val deviceState = Class.forName("android.hardware.devicestate.DeviceStateManager", false, loader)
            val callbackType = Class.forName("${deviceState.name}\$DeviceStateCallback", false, loader)
            val service = context.getSystemService(deviceState) ?: error("No DeviceStateManager")
            val callback = Proxy.newProxyInstance(loader, arrayOf(callbackType)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.singleOrNull()
                    "toString" -> TAG
                    "onDeviceStateChanged" -> {
                        safe("posture") {
                            // On this Razr: id=4, name=OPENED. Unknown ROM posture fails closed.
                            val value = args?.singleOrNull()
                            unfolded = value != null && call(value, "getIdentifier") == 4 && call(value, "getName") == "OPENED"
                            if (!unfolded) reset("not open")
                            else if (flow.phase == NativeThreeColumnFlow.Phase.IDLE) publish("open")
                        }
                        null
                    }
                    else -> null
                }
            }
            deviceState.getMethod("registerCallback", Executor::class.java, callbackType)
                .invoke(service, Executor { handler.post(it) }, callback)
            val displays = context.getSystemService(DisplayManager::class.java) ?: error("No DisplayManager")
            displays.registerDisplayListener(object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit
                override fun onDisplayRemoved(displayId: Int) = safe("display-removed") {
                    if (displayId == Display.DEFAULT_DISPLAY) reset("display removed")
                }
                override fun onDisplayChanged(displayId: Int) = safe("display-change") {
                    if (displayId == Display.DEFAULT_DISPLAY) onDisplayEnvironmentChanged("display changed")
                }
            }, handler)
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = safe("navigation") {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_OFF -> onDisplayEnvironmentChanged("screen off")
                        Intent.ACTION_CONFIGURATION_CHANGED -> onDisplayEnvironmentChanged("configuration changed")
                        Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> onNavigation(intent.getStringExtra("reason"))
                    }
                }
            }, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
                addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            }, null, handler, Context.RECEIVER_NOT_EXPORTED)
            ready = true
        }

        private fun onDisplayEnvironmentChanged(reason: String) {
            // Rotation, screen off and temporary loss of focus are not task removal.
            if (flow.phase == NativeThreeColumnFlow.Phase.ACTIVE) {
                layoutHooks.refreshOffscreenTouch()
                enqueueRefresh()
            }
            else if (reason == "screen off" || !environmentOk()) reset(reason)
        }

        fun enqueue(action: Action, taskId: Int) {
            handler.post {
                try {
                    lastRequest = "$action task=$taskId uptime=${SystemClock.uptimeMillis()}"
                    if (!ready) return@post reject("environment observer not ready", R.string.three_column_environment_unavailable)
                    if (!environmentOk() || !isEnabled()) {
                        reset("ineligible environment")
                        return@post reject("ineligible environment or option disabled", R.string.three_column_use_unfolded_landscape)
                    }
                    val sc = shell() ?: return@post reject("no coordinator", R.string.three_column_not_ready)
                    if (field(sc, "mDisplayId") != Display.DEFAULT_DISPLAY || field(sc, "mIsExiting") == true) {
                        return@post reject("wrong display or split exiting", R.string.three_column_wait_transition)
                    }
                    when (action) {
                        Action.START -> start(sc, taskId)
                        Action.ROLL, Action.EXIT -> {
                            val actual = childTokens(sc)
                            if (!flow.owns(taskId, columns(sc)) || actual != committedTokens || !transitionsIdle(sc)) {
                                return@post message(R.string.three_column_group_unavailable)
                            }
                            if (action == Action.ROLL) roll(sc) else exit(taskId)
                        }
                    }
                } catch (t: Throwable) {
                    report("action-$action", t)
                    safe("action-cleanup") {
                        if (action == Action.START) reset("action failed")
                        message(R.string.three_column_action_failed)
                    }
                }
            }
        }

        private fun start(sc: Any, taskId: Int) {
            if (flow.phase != NativeThreeColumnFlow.Phase.IDLE) {
                return reject("session already ${flow.phase}", R.string.three_column_already_running)
            }
            batchBlocker(sc)?.let { blocker ->
                val text = when {
                    blocker.startsWith("split transition busy:") -> R.string.three_column_wait_transition
                    blocker == "3-app split disabled" -> R.string.three_column_native_disabled
                    blocker == "split active" || blocker == "split roots occupied" -> R.string.three_column_exit_current_split
                    else -> R.string.three_column_not_ready
                }
                return reject(blocker, text)
            }
            val organizer = checkNotNull(field(sc, "mTaskOrganizer"))
            val shellRoots = splitRootIdentities(sc)
            val task = anchorTask(organizer, taskId, shellRoots)
                ?: return reject("anchor ineligible", R.string.three_column_require_fullscreen)
            val anchorPackage = checkNotNull(task.topActivity).packageName
            val tasks = call(organizer, "getRunningTasks", Display.DEFAULT_DISPLAY) as List<*>
            candidates = tasks.filterIsInstance<RunningTaskInfo>()
                .filter { it.taskId != taskId && eligible(it, field(task, "userId"), shellRoots) }
                .sortedByDescending { (field(it, "lastActiveTime") as? Long) ?: 0L }
                .distinctBy { it.taskId }
                .mapNotNull { info ->
                    val pkg = info.topActivity?.packageName ?: return@mapNotNull null
                    val label = runCatching {
                        val pm = context.packageManager
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    }.getOrDefault(pkg)
                    Candidate(NativeThreeColumnPicker.Choice(info.taskId, label, pkg), checkNotNull(call(info, "getToken")))
                }.associateBy { it.choice.taskId }
            if (candidates.size < 2) {
                val missing = 2 - candidates.size
                candidates = emptyMap()
                lastRequest += " rejected: not enough eligible windows"
                return showToast(context) { it.getQuantityString(R.plurals.three_column_need_more_windows, missing, missing) }
            }
            if (!flow.begin(taskId)) return
            anchorToken = call(task, "getToken")
            anchorPackageName = anchorPackage
            session++
            geometry = "none"
            batchDetail = "none"
            lastNavigation = "none"
            lastHandoff = "no transaction during selection"
            deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
            handler.postAtTime(timeout, deadline)
            publish("anchor=$taskId choices=${candidates.size}")
            showPicker()
        }

        private data class Candidate(val choice: NativeThreeColumnPicker.Choice, val token: Any)
        private var anchorToken: Any? = null
        private var anchorPackageName: String? = null
        private var selectedRight: Int? = null

        private fun eligible(task: RunningTaskInfo, user: Any?, shellRoots: List<NativeThreeColumnTaskGuard.Identity>): Boolean =
            field(task, "userId") == user && type(task) == STANDARD && task.topActivity != null &&
                call(task, "getDisplayId") == Display.DEFAULT_DISPLAY && field(task, "supportsMultiWindow") == true &&
                call(task, "getWindowingMode") == FULLSCREEN && call(task, "hasParentTask") == false &&
                taskIdentity(task)?.let { NativeThreeColumnTaskGuard.isApplicationCandidate(it, shellRoots) } == true

        private fun anchorTask(organizer: Any, taskId: Int, shellRoots: List<NativeThreeColumnTaskGuard.Identity>): RunningTaskInfo? {
            val task = call(organizer, "getRunningTaskInfo", taskId) as? RunningTaskInfo ?: return null
            val focused = field(organizer, "mLastFocusedTaskInfo") as? RunningTaskInfo
            return task.takeIf { focused?.taskId == taskId && call(task, "isVisible") == true && eligible(task, field(task, "userId"), shellRoots) }
        }

        private fun showPicker() {
            val generation = session
            val step = flow.phase
            val right = step == NativeThreeColumnFlow.Phase.SELECTING_RIGHT
            val choices = candidates.values.map { it.choice }
            val repeatedPackages = choices.groupingBy { it.packageName }.eachCount()
            val distinguish = choices.filter {
                it.packageName == anchorPackageName || (repeatedPackages[it.packageName] ?: 0) > 1
            }.mapTo(mutableSetOf()) { it.taskId }
            picker.show(if (right) R.string.three_column_choose_right else R.string.three_column_choose_center,
                choices.filter { it.taskId != selectedRight }, distinguish,
                { id -> handler.post {
                    if (session != generation || flow.phase != step) return@post
                    try { choose(id, right) } catch (t: Throwable) {
                        report("selection", t)
                        safe("selection-cleanup") {
                            if (ownedTransition == null) clear("selection failed")
                            else recover("selection failed after submit")
                            message(R.string.three_column_enter_failed)
                        }
                    }
                } },
                { handler.post { safe("picker-cancel") {
                    if (session == generation && flow.phase == step) reset("picker canceled")
                } } })
        }

        private fun choose(taskId: Int, right: Boolean) {
            val sc = shell()
            if (sc == null || !environmentOk() || !isEnabled() || SystemClock.uptimeMillis() >= deadline) {
                reset("selection eligibility lost")
                return
            }
            batchBlocker(sc)?.let { return reset("selection eligibility lost: $it") }
            val organizer = checkNotNull(field(sc, "mTaskOrganizer"))
            val shellRoots = splitRootIdentities(sc)
            val anchor = anchorTask(organizer, flow.anchor, shellRoots)?.takeIf {
                call(it, "getToken") == anchorToken && it.topActivity?.packageName == anchorPackageName
            }
            if (anchor == null) return reset("anchor changed")
            fun resolve(id: Int): RunningTaskInfo? {
                val candidate = candidates[id] ?: return null
                val task = call(organizer, "getRunningTaskInfo", id) as? RunningTaskInfo ?: return null
                return task.takeIf { eligible(it, field(anchor, "userId"), shellRoots) &&
                    call(it, "getToken") == candidate.token && it.topActivity?.packageName == candidate.choice.packageName }
            }
            val chosen = resolve(taskId)
            if (chosen == null) return reset("selected task changed")
            if (right) {
                if (flow.chooseRight(taskId)) {
                    selectedRight = taskId
                    publish("anchor=${anchor.taskId} right=$taskId")
                    showPicker()
                }
            } else {
                val rightTask = selectedRight?.let(::resolve) ?: return reset("right task changed")
                if (flow.chooseCenter(taskId) == null) return
                picker.close()
                handler.removeCallbacks(timeout)
                commit(sc, listOf(anchor, chosen, rightTask))
            }
        }

        private fun transitionsIdle(sc: Any): Boolean = transitionBlocker(sc) == null

        private fun transitionBlocker(sc: Any): String? {
            val transitions = field(sc, "mSplitTransitions") ?: return "no split transitions"
            if (field(sc, "mIsAnimating") != false) return "split transition busy: mIsAnimating"
            if (field(sc, "mIsExiting") != false) return "split transition busy: mIsExiting"
            val pending = listOf("mPendingEnter", "mPendingDismiss", "mPendingResize", "mPendingRemotePassthrough", "mFinishCallback")
                .firstOrNull { field(transitions, it) != null }
            return pending?.let { "split transition busy: $it" }
        }

        /** Keep the batch guard fail-closed, but preserve the exact failing condition. */
        private fun batchBlocker(sc: Any): String? {
            if (field(sc, "mDisplayId") != Display.DEFAULT_DISPLAY) return "wrong display"
            val manager = field(sc, "mSplitLayoutManager") ?: return "no split layout manager"
            val state = field(sc, "mSplitState") ?: return "no split state"
            val order = field(sc, "mStageOrderOperator") ?: return "no stage order operator"
            transitionBlocker(sc)?.let { return it }
            if (field(manager, "m3AppSplitConfigEnabled") != true) return "3-app split disabled"
            if (field(order, "isActive") != false || call(sc, "isSplitScreenVisible") != false) return "split active"
            val activeCount = call(state, "getActiveStageCountBySplitState")
            if (activeCount != 0) return "split state active stage count=$activeCount (expected 0)"
            if (field(sc, "mRootTaskInfo") == null) return "no split root task"
            if (field(sc, "mRootTaskLeash") == null) return "no split root leash"
            val roots = stages(sc)
            if (roots.size != THREE_STAGE_COUNT) return "split roots available=${roots.keys.sorted()} (expected A/B/C)"
            if (roots.values.any { (field(it, "mChildrenTaskInfo") as SparseArray<*>).size() != 0 }) return "split roots occupied"
            return null
        }

        private fun commit(sc: Any, tasks: List<RunningTaskInfo>) {
            var preparing = false
            var submitted = false
            var wasTranslucent = false
            var step = "preflight"
            try {
                wasTranslucent = field(sc, "mIsRootTranslucent") == true
                val manager = checkNotNull(field(sc, "mSplitLayoutManager"))
                val transitions = checkNotNull(field(sc, "mSplitTransitions"))
                val order = checkNotNull(field(sc, "mStageOrderOperator"))
                val roots = stages(sc)
                val shellRoots = splitRootIdentities(sc)
                val identities = tasks.map { checkNotNull(taskIdentity(it)) }
                check(NativeThreeColumnTaskGuard.canReparent(identities, shellRoots)) { "Unsafe or duplicate split task identity" }
                val rootToken = shellRoots.first().token
                val wct = batch.transaction.newInstance()
                committedTokens = identities.associate { it.id to it.token }
                publish("requested=${flow.requested}")
                step = "request horizontal three-stage layout"
                val previousDirection = call(sc, "isLeftRightSplit") == true
                val previousDemand = batch.orientationDemand.getInt(manager)
                if (!previousDirection) {
                    direction = Direction(manager, previousDirection, previousDemand, displayRotation())
                    batch.requestHorizontal.invoke(manager, TAG, false)
                }
                batchDetail = "leftRight=$previousDirection→${call(sc, "isLeftRightSplit")} demand=$previousDemand→${batch.orientationDemand.getInt(manager)}"
                check(call(sc, "isLeftRightSplit") == true) { "Horizontal layout unavailable" }
                batchBlocker(sc)?.let { error("Split not ready after horizontal request: $it") }
                step = "activate stages"
                preparing = true
                batch.setup.invoke(sc, THREE_STAGE_SNAP, wct)
                // A previous carousel roll can leave the ROM with a different
                // physical permutation. Bind to the current slots, not A/B/C IDs.
                val ordered = checkNotNull(activeOrder(order, roots.values)) { "Unexpected stage order" }
                batchDetail += "; slots=${ordered.map { field(it, "mId") }}"
                step = "prepare native layout"
                batch.prepareLayout.invoke(manager, THREE_STAGE_COUNT)
                val layout = checkNotNull(batch.currentLayout.get(manager)) { "Native layout missing" }
                val count = batch.stageCount.invoke(layout)
                batchDetail += "; layout=${layout.javaClass.simpleName} stages=$count"
                check(count == THREE_STAGE_COUNT) { "Expected a three-stage layout, got $count stages" }
                check(call(sc, "isLeftRightSplit") == true) { "Native layout is not horizontal" }
                val bound = layoutHooks.begin(sc, manager, layout,
                    ownsLayout = { ownsLayout(sc, manager, layout) },
                    canDrag = { flow.phase == NativeThreeColumnFlow.Phase.ACTIVE && transitionsIdle(sc) &&
                        field(sc, "mDividerVisible") == true && call(sc, "isSplitScreenVisible") == true && environmentOk() },
                    allowSurfaceFit = { flow.phase == NativeThreeColumnFlow.Phase.COMMITTING ||
                        (transitionsIdle(sc) && call(sc, "isSplitScreenVisible") == true) },
                    writeTaskBounds = { transaction, fit -> applyTaskBounds(sc, transaction, fit) },
                    writeSurfaces = { transaction, fit -> applyStageSurfaces(sc, transaction, fit) },
                    onCommitted = ::enqueueRefresh)
                batchDetail += "; fitGeometry=$bound"
                step = "set stage window bounds"
                layoutHooks.updateInitialBounds { batch.windowBounds.invoke(sc, manager, wct) }
                safe("geometry-preparation") { recordGeometry(sc) }
                step = "reparent tasks into WCT"
                val liveRoots = splitRootIdentities(sc)
                check(liveRoots == shellRoots && NativeThreeColumnTaskGuard.canReparent(identities, liveRoots)) {
                    "Split roots changed during preparation"
                }
                tasks.zip(ordered).forEach { (task, stage) -> batch.addTask.invoke(stage, task, wct) }
                step = "set split root options"
                batch.reorder.invoke(wct, rootToken, true)
                batch.setRelaunch.invoke(wct, rootToken, false)
                batch.setRootForceTranslucent.invoke(sc, wct, false)
                // This ROM method returns void; the native session holds the actual token.
                step = "submit enter transition"
                submitted = true
                batch.start.invoke(transitions, wct, null, sc, ENTER_TRANSIT, false, THREE_STAGE_SNAP)
                ownedTransition = pendingEnterToken(sc)
                lastHandoff = "submitted snap=$THREE_STAGE_SNAP type=$ENTER_TRANSIT token=${ownedTransition != null}"
                Log.i(TAG, "Batch ${flow.requested}: $lastHandoff")
                handler.postDelayed(timeout, COMMIT_TIMEOUT_MS)
                enqueueRefresh()
            } catch (t: Throwable) {
                val cause = (t as? InvocationTargetException)?.targetException ?: t
                batchDetail += "; failed at $step: ${cause.javaClass.simpleName} ${cause.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(180)}"
                report("batch-enter", t)
                if (submitted) {
                    safe("batch-enter-recovery") {
                        ownedTransition = pendingEnterToken(sc)
                        recover("enter invocation failed")
                    }
                    if (flow.phase == NativeThreeColumnFlow.Phase.COMMITTING) handler.postDelayed(timeout, COMMIT_TIMEOUT_MS)
                } else {
                    layoutHooks.end()
                    if (preparing) {
                        safe("batch-preparation-cleanup") {
                            batch.abortPreparation.invoke(sc, STAGE_A, FULLSCREEN_REQUEST, batch.transaction.newInstance())
                        }
                        safe("batch-preparation-translucency") {
                            batch.setRootForceTranslucent.invoke(sc, batch.transaction.newInstance(), wasTranslucent)
                        }
                    }
                    safe("batch-direction-restore") { restoreDirectionIfIdle(sc) }
                    clear("enter preparation failed; WCT not submitted")
                }
                message(R.string.three_column_enter_failed)
            }
        }

        private fun pendingEnterToken(sc: Any): IBinder? = field(sc, "mSplitTransitions")
            ?.let { field(it, "mPendingEnter") }?.let { field(it, "mTransition") as? IBinder }

        fun onConsumed(token: Any?, aborted: Boolean) {
            if (token != null && token == ownedTransition && aborted) recoveryReason = "enter aborted"
            enqueueRefresh()
        }

        fun enqueueRefresh() {
            if ((flow.phase == NativeThreeColumnFlow.Phase.IDLE && direction == null) || refreshPosted) return
            refreshPosted = handler.post {
                refreshPosted = false
                refresh()
            }
        }

        private fun refresh() {
            try {
                if (flow.phase == NativeThreeColumnFlow.Phase.IDLE) {
                    shell()?.let(::restoreDirectionIfIdle)
                    return
                }
                if (flow.selecting) {
                    if (!isEnabled() || !environmentOk() || SystemClock.uptimeMillis() >= deadline) reset("eligibility lost")
                    return
                }
                val sc = shell() ?: return clear("coordinator missing")
                if (!transitionsIdle(sc)) return
                val previous = flow.phase
                if (previous == NativeThreeColumnFlow.Phase.COMMITTING) {
                    recoveryReason?.let { return recover(it) }
                    if (!isEnabled() || !environmentOk()) return recover("commit eligibility lost")
                    if (call(sc, "isSplitScreenVisible") != true || field(sc, "mDividerVisible") != true) return
                    if (childTokens(sc) != committedTokens) return
                } else if (childTokens(sc) == committedTokens) {
                    val order = field(sc, "mStageOrderOperator") ?: return
                    // During a layout swap the ROM can rebuild the slot list even
                    // though all three of our children still belong to their roots.
                    if (activeOrder(order, stages(sc).values) == null) return
                }
                val current = columns(sc)
                flow.observe(current)
                if (flow.phase == NativeThreeColumnFlow.Phase.ACTIVE) {
                    activeMenuTasks = current.complete().orEmpty()
                    handler.removeCallbacks(timeout)
                    ownedTransition = null
                    layoutHooks.refreshTouch()
                    if (call(sc, "isLeftRightSplit") == true) safe("geometry-snapshot") { recordGeometry(sc) }
                } else if (flow.phase == NativeThreeColumnFlow.Phase.IDLE) {
                    safe("batch-direction-restore") { restoreDirectionIfIdle(sc) }
                    return clear("split no longer owned")
                }
                if (previous != flow.phase) {
                    publish("stages=$current")
                    Log.i(TAG, status)
                }
            } catch (t: Throwable) {
                report("stage-update", t)
                if (flow.phase != NativeThreeColumnFlow.Phase.COMMITTING) clear("stage observation failed")
            }
        }

        private fun ownsLayout(sc: Any, manager: Any, layout: Any): Boolean {
            val phase = flow.phase
            if (phase != NativeThreeColumnFlow.Phase.COMMITTING && phase != NativeThreeColumnFlow.Phase.ACTIVE) return false
            if (!unfolded || !matches(sc) || recoveryReason != null || field(sc, "mIsExiting") != false ||
                field(sc, "mSplitLayoutManager") !== manager || batch.currentLayout.get(manager) !== layout ||
                call(sc, "isLeftRightSplit") != true) return false
            val order = field(sc, "mStageOrderOperator") ?: return false
            if (activeOrder(order, stages(sc).values) == null) return false
            val actual = childTokens(sc)
            return if (phase == NativeThreeColumnFlow.Phase.ACTIVE) actual == committedTokens
                else actual.all { committedTokens[it.key] == it.value }
        }

        private fun applyTaskBounds(sc: Any, wct: Any, fit: NativeThreeColumnGeometry.Layout): Boolean {
            val order = field(sc, "mStageOrderOperator") ?: return false
            val ordered = activeOrder(order, stages(sc).values) ?: return false
            val tokens = ordered.map { stage ->
                checkNotNull(call(checkNotNull(field(stage, "mRootTaskInfo")), "getToken"))
            }
            // Append to Shell's transaction instead of issuing an independent resize.
            tokens.zip(fit.stages).forEach { (token, box) ->
                batch.setBounds.invoke(wct, token, Rect(box.left, box.top, box.right, box.bottom))
                // Native WCT can carry screen dp from a previous layout even
                // when its bounds are replaced. Let WM derive dp and insets
                // from the new rectangle, without touching display density.
                batch.setScreenSize.invoke(wct, token, Configuration.SCREEN_WIDTH_DP_UNDEFINED, Configuration.SCREEN_HEIGHT_DP_UNDEFINED)
                batch.setSmallestWidth.invoke(wct, token, Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED)
            }
            return true
        }

        private fun applyStageSurfaces(sc: Any, transaction: Any, fit: NativeThreeColumnGeometry.Layout): Boolean {
            val order = field(sc, "mStageOrderOperator") ?: return false
            val ordered = activeOrder(order, stages(sc).values) ?: return false
            val leashes = ordered.map { (field(it, "mRootLeash") as? SurfaceControl)?.takeIf { leash -> leash.isValid } ?: return false }
            val manager = checkNotNull(field(sc, "mSplitLayoutManager"))
            val root = call(manager, "getRootBounds") as Rect
            // Leashes are children of the split root; WCT bounds are absolute.
            leashes.zip(fit.stages).forEach { (leash, box) ->
                call(transaction, "setMatrix", leash, 1f, 0f, 0f, 1f)
                call(transaction, "setPosition", leash, (box.left - root.left).toFloat(), (box.top - root.top).toFloat())
                call(transaction, "setWindowCrop", leash, box.width, box.height)
            }
            return true
        }

        private fun recordGeometry(sc: Any) {
            val manager = checkNotNull(field(sc, "mSplitLayoutManager"))
            val root = call(manager, "getRootBounds") as Rect
            val divider = field(manager, "mDividerSize") as Int
            val actual = (0..2).map { (call(sc, "getStageBoundsByIndex", it) as? Rect)?.let(::Rect) }
            val displayed = Array(3) { Rect() }
            call(sc, "getStageBoundsDisplayed", displayed)
            val ideal = NativeThreeColumnGeometry.calculate(InnerSnapGeometry.Box(root.left, root.top, root.right, root.bottom), divider)
            geometry = "root=$root divider=$divider stageBounds=$actual displayed=${displayed.toList()} " +
                "equalReference=$ideal fit=${layoutHooks.summary()} dpi=${context.resources.configuration.densityDpi}"
        }

        /** Never dismiss an unrelated group or edit a transaction already handed to Shell. */
        private fun recover(reason: String) {
            recoveryReason = reason
            val sc = shell() ?: return clear("$reason; coordinator unavailable")
            if (!transitionsIdle(sc)) {
                publish("$reason; waiting for WM Shell to settle")
                return
            }
            val actual = childTokens(sc)
            val order = checkNotNull(field(sc, "mStageOrderOperator"))
            if (actual.isEmpty() && field(order, "isActive") == false) {
                safe("batch-direction-restore") { restoreDirectionIfIdle(sc) }
                return clear("$reason; no split children")
            }
            if (flow.anchor in actual && actual.all { committedTokens[it.key] == it.value }) {
                try {
                    exit(flow.anchor)
                    clear("$reason; native fullscreen requested")
                } catch (t: Throwable) {
                    report("batch-native-exit", t)
                    publish("$reason; native exit failed")
                }
            } else publish("$reason; automatic exit skipped (cannot verify ownership)")
        }

        @Suppress("DEPRECATION")
        private fun displayRotation(): Int = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: -1

        private fun restoreDirectionIfIdle(sc: Any) {
            val saved = direction ?: return
            if (field(sc, "mSplitLayoutManager") !== saved.manager) {
                direction = null
                return
            }
            if (!transitionsIdle(sc) || field(checkNotNull(field(sc, "mStageOrderOperator")), "isActive") != false ||
                childTokens(sc).isNotEmpty()) return
            direction = null
            if (batch.orientationDemand.getInt(saved.manager) != 0) return // The ROM changed or cleared our demand.
            try {
                if (displayRotation() == saved.rotation && unfolded && call(sc, "isLeftRightSplit") != saved.leftRight) {
                    batch.requestDirection.invoke(saved.manager, saved.leftRight, false, false)
                }
            } finally {
                batch.orientationDemand.setInt(saved.manager, saved.demand)
            }
        }

        private fun childTokens(sc: Any): Map<Int, Any> = buildMap {
            for (stage in stages(sc).values) {
                val children = field(stage, "mChildrenTaskInfo") as SparseArray<*>
                for (i in 0 until children.size()) {
                    val task = children.valueAt(i) as RunningTaskInfo
                    check(put(task.taskId, checkNotNull(call(task, "getToken"))) == null) { "Duplicate child task" }
                }
            }
        }

        private fun taskIdentity(task: RunningTaskInfo): NativeThreeColumnTaskGuard.Identity? =
            call(task, "getToken")?.let { NativeThreeColumnTaskGuard.Identity(task.taskId, it) }

        /** The first item is the parent split root; the other three are reparent destinations. */
        private fun splitRootIdentities(sc: Any): List<NativeThreeColumnTaskGuard.Identity> {
            val roots = stages(sc)
            val owners = listOf(sc) + (STAGE_A..STAGE_C).map(roots::getValue)
            return owners.map { owner -> checkNotNull(taskIdentity(field(owner, "mRootTaskInfo") as RunningTaskInfo)) }
        }

        private fun stages(sc: Any): Map<Int, Any> {
            val op = field(sc, "mStageOrderOperator") ?: return emptyMap()
            val all = field(op, "allStages") as? List<*> ?: return emptyMap()
            return all.filterNotNull().mapNotNull {
                val id = field(it, "mId") as Int
                if (id in STAGE_A..STAGE_C && field(it, "mRootTaskInfo") != null) id to it else null
            }.toMap()
        }

        private fun columns(sc: Any): NativeThreeColumnFlow.Columns {
            val stages = stages(sc)
            val order = field(sc, "mStageOrderOperator") ?: return NativeThreeColumnFlow.Columns(null, null, null)
            val ordered = activeOrder(order, stages.values) ?: return NativeThreeColumnFlow.Columns(null, null, null)
            fun task(index: Int): Int? {
                val children = field(ordered[index], "mChildrenTaskInfo") as SparseArray<*>
                if (children.size() != 1) return null
                val info = children.valueAt(0) as? RunningTaskInfo ?: return null
                return info.taskId.takeIf { committedTokens[it] == taskIdentity(info)?.token && it in committedTokens }
            }
            return NativeThreeColumnFlow.Columns(task(0), task(1), task(2))
        }

        private fun activeOrder(order: Any, roots: Collection<Any>): List<Any>? {
            val slots = field(order, "activeStages") as? List<*> ?: return null
            val active = slots.map { it ?: return null }
            return NativeThreeColumnTaskGuard.orderedStages(active, roots)
        }

        private fun type(info: RunningTaskInfo) = call(info, "getActivityType") as Int

        @Suppress("DEPRECATION")
        private fun environmentOk(): Boolean {
            if (!unfolded || context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE ||
                context.getSystemService(PowerManager::class.java)?.isInteractive != true ||
                context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false) return false
            val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
                ?: return false
            val config = context.createDisplayContext(display).resources.configuration
            return display.state == Display.STATE_ON && config.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                config.smallestScreenWidthDp >= 600
        }

        fun enqueueCancelSelection(reason: String) {
            handler.post { safe("cancel-$reason") { cancelSelection(reason) } }
        }

        private fun cancelSelection(reason: String) {
            if (flow.selecting) reset(reason)
        }

        private fun onNavigation(reason: String?) {
            if (reason !in setOf("homekey", "recentapps", "gestureNav") || !flow.selecting) return
            lastNavigation = "$reason uptime=${SystemClock.uptimeMillis()}"
            cancelSelection("navigation: $reason")
        }

        fun reset(reason: String) {
            if (flow.phase == NativeThreeColumnFlow.Phase.IDLE) return
            if (flow.phase == NativeThreeColumnFlow.Phase.COMMITTING && reason != "root vanished") return recover(reason)
            clear(reason)
        }

        private fun clear(reason: String) {
            layoutHooks.end()
            flow.reset()
            session++
            anchorToken = null
            anchorPackageName = null
            selectedRight = null
            candidates = emptyMap()
            committedTokens = emptyMap()
            activeMenuTasks = emptySet()
            ownedTransition = null
            recoveryReason = null
            handler.removeCallbacks(timeout)
            picker.close()
            publish(reason)
            Log.i(TAG, status)
        }

        private fun publish(detail: String) { status = "${flow.phase} ($detail)" }
        private fun reject(detail: String, text: Int) {
            lastRequest += " rejected: $detail"
            message(text)
        }
        private fun message(text: Int) = showToast(context) { it.getString(text) }
    }

    private fun showToast(context: Context, text: (Resources) -> String) {
        Handler(Looper.getMainLooper()).post {
            safe("toast") { Toast.makeText(context, text(context.moduleStringResources()), Toast.LENGTH_SHORT).show() }
        }
    }

    private fun field(target: Any, name: String): Any? = XposedHelpers.getObjectField(target, name)
    private fun call(target: Any, name: String, vararg args: Any?): Any? = XposedHelpers.callMethod(target, name, *args)

    private fun after(type: Class<*>, name: String, vararg args: Class<*>, action: (XC_MethodHook.MethodHookParam) -> Unit) {
        XposedBridge.hookMethod(type.getDeclaredMethod(name, *args), object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) = safe(name) { if (!param.hasThrowable()) action(param) }
        })
    }

    private inline fun safe(where: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { report(where, t) }
    }

    private fun report(where: String, t: Throwable) {
        runCatching {
            if (errors.add(where)) {
                Log.w(TAG, where, t)
                XposedBridge.log("$TAG: $where")
                XposedBridge.log(t)
                ErrorLogger.log(TAG, where, t)
            }
        }
    }
}
