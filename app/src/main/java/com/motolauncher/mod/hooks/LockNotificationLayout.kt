package com.motolauncher.mod.hooks

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.view.accessibility.AccessibilityManager
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.motolauncher.mod.ErrorLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** Event-driven notification viewport below the clock and keyguard widgets. */
internal object LockNotificationLayout {
    private const val TAG = "MotoLauncherMod"
    private const val TOP_FRACTION = 0.37f
    private val errors = ConcurrentHashMap.newKeySet<String>()
    private val states = WeakHashMap<View, State>()
    private lateinit var enabled: () -> Boolean
    private var statusBarState: Int? = null
    private var dozing = false
    private var topPadding: Field? = null
    private var ambientState: Field? = null
    private var ambientTopPadding: Field? = null
    private var maxNotifications: Field? = null
    private var scrollingEnabled: Field? = null
    private var contentHeight: Field? = null
    private var updateContentHeight: Method? = null
    private var requestChildrenUpdate: Method? = null

    private class State {
        val stockBounds = Rect()
        val appliedBounds = Rect()
        val location = IntArray(2)
        val rootLocation = IntArray(2)
        var hasBounds = false
        var adjusted = false
        var internal = false
        var posted = false
        var observer: ViewTreeObserver? = null
        var receiver: BroadcastReceiver? = null
        var lastTarget = -1
        var stockMaxNotifications: Int? = null
        var stockScrollingEnabled: Boolean? = null
        var scrollPolicyApplied = false
        var updatingContent = false
        var stockTopPadding: Int? = null
        var stockAmbientTopPadding: Float? = null
        var stockClipChildren: Boolean? = null
        var stockClipToPadding: Boolean? = null
        lateinit var globalLayout: ViewTreeObserver.OnGlobalLayoutListener
    }

    fun install(loader: ClassLoader?, isEnabled: () -> Boolean) {
        enabled = isEnabled
        if (loader != null) KeyguardPanelVisibility.install(loader)
        KeyguardPanelVisibility.addListener {
            states.entries.toList().forEach { (view, state) ->
                runCatching {
                    // Restore synchronously, before the opening QS frame is drawn.
                    if (KeyguardPanelVisibility.controlCenterVisible) restore(view, state)
                    else schedule(view, state)
                }.onFailure { report("QS transition", it) }
            }
        }
        runCatching {
            val stack = Class.forName(
                "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout", false, loader
            )
            topPadding = findField(stack, "mTopPadding")
            ambientState = findField(stack, "mAmbientState")
            ambientTopPadding = ambientState?.type?.let { findField(it, "mTopPadding") }
            maxNotifications = findField(stack, "mMaxDisplayedNotifications")
            scrollingEnabled = findField(stack, "mScrollingEnabled")
            contentHeight = findField(stack, "mContentHeight")
            updateContentHeight = stack.declaredMethods.firstOrNull {
                it.name == "updateContentHeight" && it.parameterCount == 0
            }?.apply { isAccessible = true }
            requestChildrenUpdate = stack.declaredMethods.firstOrNull {
                it.name == "requestChildrenUpdate" && it.parameterCount == 0
            }?.apply { isAccessible = true }
            LockNotificationStack.install(stack, ::onStackKeyguard)
            installScrolling(stack)
            XposedBridge.hookAllConstructors(stack, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { observe(param.thisObject as View) }.onFailure { report("observe", it) }
                }
            })
            Log.i(TAG, "LockClock notification viewport installed: screen fraction=$TOP_FRACTION; " +
                "native padding=${topPadding?.name ?: ambientTopPadding?.name}; layout events only; " +
                "scrolling=${scrollingEnabled != null}; notification limit=${maxNotifications != null}")
        }.onFailure { report("install", it) }

        // Keyguard -> shade / unlock / AOD can change without resizing the view.
        // Observe just this controller, not framework View methods or all flows.
        runCatching {
            val controller = Class.forName("com.android.systemui.statusbar.StatusBarStateControllerImpl", false, loader)
            controller.declaredMethods.filter {
                (it.name == "setState" && it.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType) ||
                    (it.name == "setIsDozing" && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)))
            }.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            if (param.hasThrowable()) return@runCatching
                            if (method.name == "setState") {
                                val current = findField(controller, "mState")?.getInt(param.thisObject)
                                    ?: param.args[0] as Int
                                if (statusBarState == current) return@runCatching
                                statusBarState = current
                            } else {
                                val current = param.args[0] as Boolean
                                if (dozing == current) return@runCatching
                                dozing = current
                            }
                            states.entries.toList().forEach { (view, state) -> schedule(view, state) }
                        }.onFailure { report("keyguard state", it) }
                    }
                })
            }
        }.onFailure { report("state controller", it) }
    }

    internal fun isKeyguard(view: View): Boolean = !dozing &&
        (statusBarState == null || statusBarState == 1) &&
        view.context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun onStackKeyguard(view: View): Boolean = LockNotificationStack.supported &&
        onKeyguard(view) &&
        view.context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled != true

    private fun onKeyguard(view: View): Boolean = enabled() && isKeyguard(view) &&
        !KeyguardPanelVisibility.controlCenterVisible

    private fun installScrolling(stack: Class<*>) {
        stack.declaredMethods.filter {
            (it.name == "getScrollRange" && it.parameterCount == 0 && it.returnType == Int::class.javaPrimitiveType) ||
                (it.name in setOf("updateContentHeight", "onScrollTouch", "onInterceptTouchEventScroll"))
        }.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        if (method.name == "getScrollRange") return@runCatching
                        val view = param.thisObject as View
                        val state = states[view] ?: return@runCatching
                        if (state.updatingContent) return@runCatching
                        if (onKeyguard(view)) setScrollPolicy(view, state)
                        else restoreScrollPolicy(view, state)
                    }.onFailure { report("scroll policy", it) }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        if (param.hasThrowable()) return@runCatching
                        val view = param.thisObject as View
                        val state = states[view] ?: return@runCatching
                        if (method.name == "getScrollRange") {
                            if (!state.scrollPolicyApplied || !onKeyguard(view)) return@runCatching
                            // Keep the native gesture/fling implementation, but
                            // measure overflow against our smaller real viewport.
                            val deck = if (onStackKeyguard(view)) LockNotificationStack.metrics(view as ViewGroup) else null
                            if (deck != null) param.result = deck.scrollRange
                            else {
                                val overflow = ((contentHeight?.getInt(view) ?: 0) - view.height).coerceAtLeast(0)
                                param.result = maxOf(param.result as Int, overflow)
                            }
                        } else if (method.name == "updateContentHeight" && !state.updatingContent) {
                            schedule(view, state)
                        }
                    }.onFailure { report("scroll range", it) }
                }
            })
        }
    }

    private fun setScrollPolicy(view: View, state: State): Boolean {
        var changed = false
        if (onStackKeyguard(view)) {
            topPadding?.let { field ->
                val current = field.getInt(view)
                if (state.stockTopPadding == null || current != 0) state.stockTopPadding = current
                if (current != 0) { field.setInt(view, 0); changed = true }
            }
            ambientState?.get(view)?.let { ambient ->
                ambientTopPadding?.let { field ->
                    val current = (field.get(ambient) as Number).toFloat()
                    if (state.stockAmbientTopPadding == null || current != 0f) state.stockAmbientTopPadding = current
                    if (field.type == Float::class.javaPrimitiveType) field.setFloat(ambient, 0f)
                    else field.setInt(ambient, 0)
                }
            }
            (view as? ViewGroup)?.let { host ->
                if (state.stockClipChildren == null) state.stockClipChildren = host.clipChildren
                if (state.stockClipToPadding == null) state.stockClipToPadding = host.clipToPadding
                host.clipChildren = true
                host.clipToPadding = true
            }
        } else if (restoreStackPolicy(view, state)) changed = true
        maxNotifications?.let { field ->
            val current = field.getInt(view)
            if (!state.scrollPolicyApplied || current != -1) state.stockMaxNotifications = current
            if (current != -1) {
                field.setInt(view, -1) // Native sentinel: no keyguard display-count cap.
                changed = true
            }
        }
        scrollingEnabled?.let { field ->
            val current = field.getBoolean(view)
            if (!state.scrollPolicyApplied || !current) state.stockScrollingEnabled = current
            if (!current) field.setBoolean(view, true)
        }
        state.scrollPolicyApplied = true
        return changed
    }

    private fun restoreScrollPolicy(view: View, state: State): Boolean {
        if (!state.scrollPolicyApplied) return false
        var changed = restoreStackPolicy(view, state)
        state.stockMaxNotifications?.let { stock ->
            maxNotifications?.let { field ->
                if (field.getInt(view) == -1) {
                    field.setInt(view, stock)
                    changed = true
                }
            }
        }
        state.stockScrollingEnabled?.let { stock ->
            scrollingEnabled?.let { field -> if (field.getBoolean(view)) field.setBoolean(view, stock) }
        }
        state.scrollPolicyApplied = false
        state.stockMaxNotifications = null
        state.stockScrollingEnabled = null
        return changed
    }

    private fun restoreStackPolicy(view: View, state: State): Boolean {
        val hadStack = state.stockTopPadding != null || state.stockClipChildren != null
        state.stockTopPadding?.let { stock -> topPadding?.let { if (it.getInt(view) == 0) it.setInt(view, stock) } }
        state.stockAmbientTopPadding?.let { stock ->
            ambientState?.get(view)?.let { ambient ->
                ambientTopPadding?.let { field ->
                    if ((field.get(ambient) as Number).toFloat() == 0f) {
                        if (field.type == Float::class.javaPrimitiveType) field.setFloat(ambient, stock)
                        else field.setInt(ambient, stock.toInt())
                    }
                }
            }
        }
        (view as? ViewGroup)?.let { host ->
            state.stockClipChildren?.let { host.clipChildren = it }
            state.stockClipToPadding?.let { host.clipToPadding = it }
            if (hadStack) LockNotificationStack.restoreStates(host)
        }
        state.stockTopPadding = null
        state.stockAmbientTopPadding = null
        state.stockClipChildren = null
        state.stockClipToPadding = null
        return hadStack
    }

    private fun refreshContent(view: View, state: State) {
        if (state.updatingContent) return
        state.updatingContent = true
        try {
            updateContentHeight?.invoke(view)
        } finally {
            state.updatingContent = false
        }
    }

    private fun observe(view: View) {
        if (states.containsKey(view)) return
        val state = State()
        states[view] = state
        state.globalLayout = ViewTreeObserver.OnGlobalLayoutListener {
            runCatching { apply(view, state) }.onFailure { report("layout", it) }
        }
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            runCatching {
                if (!state.internal) {
                    captureBounds(v, state)
                    schedule(v, state)
                }
            }.onFailure { report("stack bounds", it) }
        }
        val attachment = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                runCatching {
                    state.observer = v.viewTreeObserver
                    state.observer?.addOnGlobalLayoutListener(state.globalLayout)
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            runCatching { schedule(v, state) }.onFailure { report("screen event", it) }
                        }
                    }
                    v.context.registerReceiver(receiver, IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_ON)
                        addAction(Intent.ACTION_SCREEN_OFF)
                        addAction(Intent.ACTION_USER_PRESENT)
                        addAction(Intent.ACTION_CONFIGURATION_CHANGED)
                    }, Context.RECEIVER_NOT_EXPORTED)
                    state.receiver = receiver
                    schedule(v, state)
                }.onFailure { report("attach", it) }
            }

            override fun onViewDetachedFromWindow(v: View) {
                runCatching {
                    if (state.observer?.isAlive == true) {
                        state.observer?.removeOnGlobalLayoutListener(state.globalLayout)
                    }
                    state.observer = null
                    state.receiver?.let { v.context.unregisterReceiver(it) }
                    state.receiver = null
                    restore(v, state)
                    state.hasBounds = false
                }.onFailure { report("detach", it) }
            }
        }
        view.addOnAttachStateChangeListener(attachment)
        if (view.isAttachedToWindow) attachment.onViewAttachedToWindow(view)
    }

    private fun schedule(view: View, state: State) {
        if (state.posted || !view.isAttachedToWindow) return
        state.posted = true
        if (!view.post {
                state.posted = false
                runCatching { if (view.isAttachedToWindow) apply(view, state) }
                    .onFailure { report("apply", it) }
            }) state.posted = false
    }

    private fun captureBounds(view: View, state: State) {
        // Never use our previous correction as the next baseline (no drift).
        if (state.adjusted && state.appliedBounds.let {
                view.left == it.left && view.top == it.top && view.right == it.right && view.bottom == it.bottom
            }) return
        state.stockBounds.set(view.left, view.top, view.right, view.bottom)
        state.hasBounds = true
        state.adjusted = false
    }

    private fun apply(view: View, state: State) {
        if (state.internal || !view.isLaidOut || view.height <= 0 || view.width <= 0) return
        captureBounds(view, state)
        if (!onKeyguard(view)) {
            restore(view, state)
            return
        }
        val contentChanged = setScrollPolicy(view, state)
        val root = view.rootView
        if (root.height <= 0) return
        root.getLocationOnScreen(state.rootLocation)
        view.getLocationOnScreen(state.location)
        val deck = if (onStackKeyguard(view)) LockNotificationStack.metrics(view as ViewGroup) else null
        val density = view.resources.displayMetrics.density
        // Keep the deck above the native corner shortcuts/navigation area, while
        // retaining enough real viewport height for expanded notifications.
        val bottomInset = (96f * density).toInt()
        val deckHeight = deck?.height?.coerceAtMost((root.height * .48f).toInt())
        val target = state.rootLocation[1] + if (deckHeight != null) {
            (root.height - bottomInset - deckHeight).coerceAtLeast((root.height * .42f).toInt())
        } else (root.height * TOP_FRACTION).toInt()
        val stock = state.stockBounds
        val originalScreenTop = state.location[1] - (view.top - stock.top)
        // The stack already positions rows using its native top padding. Count
        // that padding once, not again as an additional margin below 2/3.
        val padding = (topPadding?.getInt(view) ?: ambientState?.get(view)?.let {
            (ambientTopPadding?.get(it) as? Number)?.toInt()
        } ?: 0).coerceAtLeast(0)
        val shift = (target - originalScreenTop - padding).coerceAtLeast(0)
        val desiredTop = (stock.top + shift).coerceAtMost(stock.bottom - 1)
        val bottom = if (deckHeight != null) {
            minOf(stock.bottom, desiredTop + deckHeight).coerceAtLeast(desiredTop + 1)
        } else stock.bottom
        val bounds = Rect(stock.left, desiredTop, stock.right, bottom)
        setBounds(view, state, bounds)
        state.appliedBounds.set(bounds)
        state.adjusted = bounds != stock
        if (contentChanged) refreshContent(view, state)
        if (state.lastTarget != target) {
            Log.i(TAG, "LockClock notification viewport: targetY=$target; windowHeight=${root.height}; " +
                "stock=$stock; applied=$bounds; nativePadding=$padding; keyguard=$statusBarState")
            state.lastTarget = target
        }
    }

    private fun restore(view: View, state: State) {
        val contentChanged = restoreScrollPolicy(view, state)
        if (state.adjusted && state.hasBounds) {
            setBounds(view, state, state.stockBounds)
            state.adjusted = false
        }
        if (contentChanged) refreshContent(view, state)
        state.lastTarget = -1
    }

    private fun setBounds(view: View, state: State, bounds: Rect) {
        if (view.left == bounds.left && view.top == bounds.top &&
            view.right == bounds.right && view.bottom == bounds.bottom) return
        state.internal = true
        try {
            // Real layout bounds keep input coordinates and the scroll viewport together.
            view.layout(bounds.left, bounds.top, bounds.right, bounds.bottom)
            requestChildrenUpdate?.invoke(view)
            view.invalidate()
        } finally {
            state.internal = false
        }
    }

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            val field = current.declaredFields.firstOrNull { it.name == name }
            if (field != null) return field.apply { isAccessible = true }
            current = current.superclass
        }
        return null
    }

    private fun report(stage: String, error: Throwable) {
        if (errors.add(stage)) ErrorLogger.log(TAG, "LockClock notification viewport $stage", error)
    }
}
