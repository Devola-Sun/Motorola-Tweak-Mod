package com.motolauncher.mod.hooks

import android.graphics.Region
import android.os.IBinder
import android.view.Display
import android.view.SurfaceControl
import android.view.WindowManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

/** Track Motorola's own carousel input channels in the Shell process only. */
internal class NativeThreeColumnOffscreenTouch(
    private val onShellThread: () -> Boolean,
    private val shouldMute: () -> Boolean,
    private val report: (String, Throwable) -> Unit,
) {
    private class Zone(
        val session: Any,
        val client: IBinder,
        val surface: SurfaceControl,
        var flags: Int,
        var privateFlags: Int,
        var features: Int,
        var region: Region? = null,
        var muted: Boolean = false,
    )

    private var update: Method? = null
    private var replaying = false
    private val zones = mutableMapOf<IBinder, Zone>()

    fun summary() = "offscreenTouchTracked=${zones.size} offscreenTouchMuted=${zones.values.count { it.muted }}"

    fun install(loader: ClassLoader?) {
        if (update != null) return
        val session = Class.forName("android.view.IWindowSession\$Stub\$Proxy", false, loader)
        val inputChannel = Class.forName("android.view.InputChannel", false, loader)
        val transfer = Class.forName("android.window.InputTransferToken", false, loader)
        val int = Int::class.javaPrimitiveType!!
        val grant = session.getDeclaredMethod("grantInputChannel", int, SurfaceControl::class.java,
            IBinder::class.java, transfer, int, int, int, int, IBinder::class.java, transfer,
            String::class.java, inputChannel)
        val change = session.getDeclaredMethod("updateInputChannel", IBinder::class.java, int,
            SurfaceControl::class.java, int, int, int, Region::class.java).apply { isAccessible = true }
        val copySurface = SurfaceControl::class.java.getConstructor(SurfaceControl::class.java, String::class.java)
        val close = session.getDeclaredMethod("remove", IBinder::class.java)
        XposedBridge.hookMethod(close, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) = safely("remove") {
                if (!onShellThread() || param.hasThrowable() || zones.isEmpty()) return@safely
                val client = param.args[0]
                val it = zones.values.iterator()
                while (it.hasNext()) {
                    val zone = it.next()
                    if (zone.session === param.thisObject && zone.client == client) {
                        it.remove()
                        zone.surface.release()
                    }
                }
            }
        })
        XposedBridge.hookMethod(change, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) = safely("update") {
                if (!onShellThread() || replaying) return@safely
                val zone = zones[param.args[0] as? IBinder] ?: return@safely
                if (zone.session !== param.thisObject || param.args[1] != Display.DEFAULT_DISPLAY) return@safely
                val mute = shouldMute()
                zone.flags = param.args[3] as Int
                zone.privateFlags = param.args[4] as Int
                zone.features = param.args[5] as Int
                zone.region = (param.args[6] as? Region)?.let(::Region)
                if (mute) {
                    param.args[3] = zone.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    param.args[6] = Region()
                }
                param.setObjectExtra("nativeThreeColumnMuted", mute)
            }

            override fun afterHookedMethod(param: MethodHookParam) = safely("update-result") {
                if (!onShellThread() || param.hasThrowable()) return@safely
                val muted = param.getObjectExtra("nativeThreeColumnMuted") as? Boolean ?: return@safely
                zones[param.args[0] as? IBinder]?.muted = muted
            }
        })
        XposedBridge.hookMethod(grant, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) = safely("grant") {
                if (!onShellThread() || param.hasThrowable() || param.args[0] != Display.DEFAULT_DISPLAY ||
                    !isCarouselTitle(param.args[10] as? String ?: return@safely)) return@safely
                val channel = param.args[11] ?: return@safely
                val token = XposedHelpers.callMethod(channel, "getToken") as? IBinder ?: return@safely
                val client = param.args[2] as? IBinder ?: return@safely
                val surface = (param.args[1] as? SurfaceControl)?.takeIf { it.isValid } ?: return@safely
                val flags = param.args[4] as Int
                val privateFlags = param.args[5] as Int
                val features = param.args[6] as Int
                // Keep a separate handle; Shell still owns and releases the original.
                val ownSurface = copySurface.newInstance(surface, "NativeThreeColumnOffscreenTouch")
                val zone = Zone(param.thisObject, client, ownSurface, flags, privateFlags, features)
                zones.put(token, zone)?.surface?.release()
                if (shouldMute()) send(token, zone, true)
            }
        })
        update = change
    }

    fun refresh() = safely("refresh") { if (onShellThread()) replay(shouldMute()) }
    fun restore() = safely("restore") { if (onShellThread()) replay(false) }

    private fun replay(mute: Boolean) {
        for ((token, zone) in zones) {
            if (zone.muted != mute) safely("replay") { send(token, zone, mute) }
        }
    }

    private fun send(token: IBinder, zone: Zone, mute: Boolean) {
        val method = update ?: return
        if (!zone.surface.isValid) return
        val flags = if (mute) zone.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else zone.flags
        replaying = true
        try {
            method.invoke(zone.session, token, Display.DEFAULT_DISPLAY, zone.surface, flags,
                zone.privateFlags, zone.features, if (mute) Region() else zone.region)
            zone.muted = mute
        } finally {
            replaying = false
        }
    }

    private inline fun safely(operation: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { report("offscreen-touch-$operation", t) }
    }

    companion object {
        private val titles = setOf("OffscreenTouchZoneTopLeft", "OffscreenTouchZoneBottomRight")
        internal fun isCarouselTitle(title: String) = title in titles
    }
}
