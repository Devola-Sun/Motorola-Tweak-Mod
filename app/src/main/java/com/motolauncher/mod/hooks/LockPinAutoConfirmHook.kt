package com.motolauncher.mod.hooks

import android.view.View
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.motolauncher.mod.ErrorLogger
import com.motolauncher.mod.HookConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Legacy/AOSP keyguard PIN controller only. Unknown ROMs fail open to stock. */
object LockPinAutoConfirmHook : YukiBaseHooker() {
    private const val PIN_CONTROLLER = "com.android.keyguard.KeyguardPINViewController"
    private const val TAG = "MotoLauncherMod"
    private class State(controller: Any, entry: View, button: View) {
        val controller = WeakReference(controller)
        val entry = WeakReference(entry)
        val button = WeakReference(button)
        var originalVisibility = button.visibility
        var managed = false
        var appending = false
        var submitted = false
        var failed = false
        var target = 0
    }
    private val entries = WeakHashMap<View, State>()
    private val controllers = WeakHashMap<Any, State>()
    private val reported = mutableSetOf<String>()

    override fun onHook() {
        runCatching {
            val pinClass = Class.forName(PIN_CONTROLLER, false, appClassLoader)
            val passwordClass = Class.forName("com.android.keyguard.PasswordTextView", false, appClassLoader)
            // Resolve all mandatory entry points before installing or hiding anything.
            val verify = Class.forName("com.android.keyguard.KeyguardAbsKeyInputViewController", false,
                appClassLoader).getDeclaredMethod("verifyPasswordAndUnlock")
            val append = XposedHelpers.findMethodExact(passwordClass, "append", Char::class.javaPrimitiveType)
            val delete = passwordClass.getDeclaredMethod("deleteLastChar")
            val reset = XposedHelpers.findMethodExact(passwordClass, "reset", Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType)
            XposedBridge.hookMethod(verify, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val state = controllers[param.thisObject] ?: return
                    runCatching {
                        refresh(state)
                        if (!state.managed) return@runCatching
                        val entry = state.entry.get() ?: return@runCatching
                        if (!entry.isEnabled || !PinAutoConfirmPolicy.shouldSubmit(
                                state.target, enteredLength(entry), state.appending, state.submitted)) {
                            param.result = null
                        } else {
                            state.submitted = true
                        }
                    }.onFailure { fail(state, "verify", it) }
                }
            })
            XposedBridge.hookMethod(append, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val state = entries[param.thisObject] ?: return
                    runCatching {
                        refresh(state)
                        state.appending = state.managed
                    }.onFailure { fail(state, "append start", it) }
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    val state = entries[param.thisObject] ?: return
                    runCatching {
                        val entry = state.entry.get() ?: return@runCatching
                        if (!param.hasThrowable() && state.managed && entry.isShown && entry.isEnabled &&
                            PinAutoConfirmPolicy.shouldSubmit(state.target, enteredLength(entry),
                                state.appending, state.submitted)) {
                            // Use stock credential verification; never fake an unlock or inject keys globally.
                            state.controller.get()?.let { XposedHelpers.callMethod(it, "verifyPasswordAndUnlock") }
                        }
                    }.onFailure { fail(state, "append finish", it) }
                    state.appending = false
                }
            })
            val editCallback = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val state = entries[param.thisObject] ?: return
                    runCatching {
                        state.appending = false
                        state.submitted = false
                        refresh(state)
                    }.onFailure { fail(state, "edit", it) }
                }
            }
            XposedBridge.hookMethod(delete, editCallback)
            XposedBridge.hookMethod(reset, editCallback)
            val attachHooks = XposedBridge.hookAllMethods(pinClass, "onViewAttached", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { attach(param.thisObject) }.onFailure { report("attach", it) }
                }
            })
            check(attachHooks.isNotEmpty()) { "PIN attach entry point unavailable; keeping stock UI" }
            // Stock auto-confirm updates can otherwise show the OK button again.
            listOf("updateAutoConfirmationState", "resetState", "onResume").forEach { method ->
                XposedBridge.hookAllMethods(pinClass, method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val state = controllers[param.thisObject] ?: return
                        runCatching { refresh(state) }.onFailure { fail(state, method, it) }
                    }
                })
            }
        }.onFailure { report("install (stock PIN UI retained)", it) }
    }

    private fun attach(controller: Any) {
        // Explicit allowlist excludes KeyguardSimPinViewController and SIM PUK.
        if (controller.javaClass.name != PIN_CONTROLLER) return
        controllers[controller]?.let { refresh(it); return }
        val pinView = XposedHelpers.getObjectField(controller, "mView") as View
        val entry = XposedHelpers.getObjectField(pinView, "mPasswordEntry") as View
        val button = XposedHelpers.getObjectField(pinView, "mOkButton") as View
        // Never hide confirmation unless reading the input length is supported too.
        enteredLength(entry)
        val state = State(controller, entry, button)
        controllers[controller] = state
        entries[entry] = state
        refresh(state)
    }

    private fun refresh(state: State) {
        val controller = state.controller.get() ?: return
        val button = state.button.get() ?: return
        val prefs = modulePrefs
        val enabled = !state.failed && HookConfig.safeGetBoolean(prefs,
            HookConfig.KEY_LOCK_PIN_AUTO_CONFIRM, HookConfig.DEFAULT_LOCK_PIN_AUTO_CONFIRM)
        val target = HookConfig.safeGetInt(prefs, HookConfig.KEY_LOCK_PIN_LENGTH, HookConfig.DEFAULT_LOCK_PIN_LENGTH)
        val applies = enabled && PinAutoConfirmPolicy.applies(true, target, storedLength(controller))
        if (applies) {
            if (!state.managed) state.originalVisibility = button.visibility
            // INVISIBLE removes the action without shifting the numeric keypad.
            button.visibility = View.INVISIBLE
            state.target = target
            state.managed = true
        } else {
            restore(state)
        }
    }

    private fun storedLength(controller: Any): Int = runCatching {
        val utils = XposedHelpers.getObjectField(controller, "mLockPatternUtils")
        val userId = selectedUserId(controller)
        if (userId < 0) return@runCatching -1
        (XposedHelpers.callMethod(utils, "getPinLength", userId) as Number).toInt()
    }.getOrDefault(-1) // AOSP can omit length metadata, notably for four-digit PINs.

    private fun selectedUserId(controller: Any): Int {
        val candidates = listOf(
            "mSelectedUserInteractor" to "getSelectedUserId",
            "mUserInteractor" to "getSelectedUserId",
            "mUserTracker" to "getUserId"
        )
        for ((field, method) in candidates) {
            val id = runCatching {
                val source = XposedHelpers.getObjectField(controller, field)
                (XposedHelpers.callMethod(source, method) as Number).toInt()
            }.getOrNull()
            if (id != null) return id
        }
        return (XposedHelpers.callStaticMethod(
            Class.forName("com.android.keyguard.KeyguardUpdateMonitor", false, appClassLoader),
            "getCurrentUser"
        ) as Number).toInt()
    }

    private fun enteredLength(entry: View): Int =
        (XposedHelpers.getObjectField(entry, "mText") as CharSequence).length

    private fun restore(state: State) {
        if (state.managed) state.button.get()?.visibility = state.originalVisibility
        state.managed = false
        state.appending = false
        state.submitted = false
    }

    private fun fail(state: State, stage: String, error: Throwable) {
        state.failed = true
        runCatching { restore(state) }.onFailure { report("restore", it) }
        report(stage, error)
    }

    private fun report(stage: String, error: Throwable) {
        if (reported.add(stage)) ErrorLogger.log(TAG, "LockPinAutoConfirm $stage", error)
    }
}
