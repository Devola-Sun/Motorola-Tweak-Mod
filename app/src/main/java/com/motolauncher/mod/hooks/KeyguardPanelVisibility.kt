package com.motolauncher.mod.hooks

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import com.motolauncher.mod.ErrorLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap

/** A locked device can still be showing Modern QS without changing status-bar state. */
internal object KeyguardPanelVisibility {
    private val panels = WeakHashMap<View, Boolean>()
    private val listeners = mutableListOf<() -> Unit>()
    private var installed = false
    private val errors = mutableSetOf<String>()
    val controlCenterVisible: Boolean get() = panels.values.any { it }

    fun addListener(listener: () -> Unit) { listeners.add(listener) }

    fun install(loader: ClassLoader) {
        if (installed) return
        installed = true
        runCatching {
            val panel = XposedHelpers.findClass("com.android.systemui.qs.QSModernPanel", loader)
            XposedBridge.hookAllConstructors(panel, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { observe(param.thisObject as View) }.onFailure { report("observe", it) }
                }
            })
        }.onFailure { report("install", it) }
    }

    private fun observe(panel: View) {
        if (panels.containsKey(panel)) return
        panels[panel] = false
        val bounds = Rect()
        fun update(attached: Boolean) {
            val before = controlCenterVisible
            // isShown alone ignores alpha and translated/off-screen parent bounds.
            val visible = attached && panel.isShown && panel.width > 0 && panel.height > 0 &&
                generateSequence(panel as View?) { it.parent as? View }.all { it.alpha > 0f } &&
                panel.getGlobalVisibleRect(bounds) && !bounds.isEmpty
            panels[panel] = visible
            if (before != controlCenterVisible) listeners.forEach { it() }
        }
        val draw = ViewTreeObserver.OnPreDrawListener {
            runCatching { update(panel.isAttachedToWindow) }.onFailure { report("visibility", it) }
            true
        }
        val attachment = object : View.OnAttachStateChangeListener {
            private var observer: ViewTreeObserver? = null
            override fun onViewAttachedToWindow(v: View) {
                runCatching {
                    observer = v.viewTreeObserver
                    observer?.addOnPreDrawListener(draw)
                    update(true)
                }.onFailure { report("attach", it) }
            }
            override fun onViewDetachedFromWindow(v: View) {
                runCatching {
                    observer?.let { if (it.isAlive) it.removeOnPreDrawListener(draw) }
                    observer = null
                    update(false)
                }.onFailure { report("detach", it) }
            }
        }
        panel.addOnAttachStateChangeListener(attachment)
        if (panel.isAttachedToWindow) attachment.onViewAttachedToWindow(panel)
    }

    private fun report(stage: String, error: Throwable) {
        if (errors.add(stage)) ErrorLogger.log("MotoNotificationFrost", "QS visibility $stage", error)
    }
}
