package com.motolauncher.mod.hooks

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.type.android.ViewClass
import com.motolauncher.mod.HookConfig

/**
 * Reorders the top-level status bar views according to the order saved by the
 * module settings screen.
 *
 * Elements are moved between the two existing status bar containers. This
 * keeps SystemUI's own references alive (the views are only re-parented) and
 * preserves the cutout/safe-inset handling of PhoneStatusBarView.
 */
object StatusBarOrderHook : YukiBaseHooker() {

    private const val TAG = "MotoLauncherMod"

    private val allKeys = listOf(
        "clock",
        "ongoing",
        "notifications",
        "user_switcher",
        "wifi",
        "mobile",
        "battery"
    )

    private val defaultLeft = setOf("clock", "ongoing", "notifications")
    private val clusterKeys = setOf("wifi", "mobile")

    private var lastRootHash = 0
    private var lastSignature = ""

    override fun onHook() {
        findClass("com.android.systemui.statusbar.phone.PhoneStatusBarView").hook {
            injectMember {
                method {
                    name = "onFinishInflate"
                    emptyParam()
                }
                afterHook {
                    runCatching { applyOrder(instance as? View) }
                        .onFailure { Log.w(TAG, "Failed to apply status bar order", it) }
                }
            }

            // Re-apply after SystemUI recalculates the status bar layout. The
            // signature check below makes this a no-op when nothing changed.
            injectMember {
                method {
                    name = "updateSafeInsets"
                    emptyParam()
                }
                afterHook {
                    runCatching { applyOrder(instance as? View) }
                        .onFailure { Log.w(TAG, "Failed to re-apply status bar order", it) }
                }
            }
        }

        // Wi-Fi / mobile children are added after PhoneStatusBarView is
        // inflated. Reorder them once they appear so the saved order is not
        // lost on a fresh SystemUI start.
        findClass("com.android.systemui.statusbar.phone.StatusIconContainer").hook {
            injectMember {
                method {
                    name = "onViewAdded"
                    param(ViewClass)
                }
                afterHook {
                    val container = instance as? ViewGroup ?: return@afterHook
                    val left = readOrder(
                        HookConfig.KEY_SB_LEFT_ORDER,
                        HookConfig.DEFAULT_SB_LEFT_ORDER
                    )
                    val right = readOrder(
                        HookConfig.KEY_SB_RIGHT_ORDER,
                        HookConfig.DEFAULT_SB_RIGHT_ORDER
                    )
                    val combined = left + right
                    val wifiFirst = combined.indexOf("wifi") < combined.indexOf("mobile")
                    container.post {
                        runCatching { reorderWifiAndMobile(container, wifiFirst) }
                    }
                }
            }
        }
    }

    private fun applyOrder(root: View?) {
        if (root == null) return

        val left = readOrder(HookConfig.KEY_SB_LEFT_ORDER, HookConfig.DEFAULT_SB_LEFT_ORDER)
        val right = readOrder(HookConfig.KEY_SB_RIGHT_ORDER, HookConfig.DEFAULT_SB_RIGHT_ORDER)

        // Keep each element on exactly one side. If a key is absent from both
        // lists (old prefs), put it back on its default side.
        right.removeAll { it in left }
        allKeys.forEach { key ->
            if (key !in left && key !in right) {
                if (key in defaultLeft) left.add(key) else right.add(key)
            }
        }

        val signature = left.joinToString(",") + "|" + right.joinToString(",")

        if (System.identityHashCode(root) == lastRootHash && signature == lastSignature) {
            return
        }

        val startContainer = root.findViewById<ViewGroup>(
            id(root, "status_bar_start_side_except_heads_up")
        ) ?: return
        val endContainer = root.findViewById<ViewGroup>(
            id(root, "status_bar_end_side_content")
        ) ?: return

        val systemIcons = root.findViewById<ViewGroup>(id(root, "system_icons"))
        val statusIcons = root.findViewById<ViewGroup>(id(root, "statusIcons"))
        val battery = root.findViewById<View>(id(root, "battery"))

        // The battery element is a child of the icon cluster in the stock
        // layout, and the ROM shows / hides it there. Detaching it (which the
        // old code did unconditionally) left it GONE for ever: the battery
        // icon was not drawn at all and the space it should occupy stayed
        // empty, which looked like an unnaturally wide gap between the
        // Wi-Fi icon and the right edge.
        //
        // The battery is therefore only detached when the user really moves
        // it to the other side; otherwise it stays inside the cluster and is
        // only ordered to the end of it.
        val clusterOnLeft = left.any { it in clusterKeys }
        val batteryOnLeft = "battery" in left
        val keepBatteryInCluster = clusterOnLeft == batteryOnLeft

        if (!keepBatteryInCluster && battery != null) {
            (battery.parent as? ViewGroup)?.removeView(battery)
        }

        // Wi-Fi and cellular are children of statusIcons. Keep the cluster as
        // one unit but order the two icons according to the saved preference.
        if (statusIcons != null) {
            val combined = left + right
            val wifiFirst = combined.indexOf("wifi") < combined.indexOf("mobile")
            reorderWifiAndMobile(statusIcons, wifiFirst)
        }

        val placements = mapOf(
            "clock" to listOfNotNull(root.findViewById<View>(id(root, "clock"))),
            "ongoing" to listOfNotNull(
                root.findViewById<View>(id(root, "ongoing_activity_chip_primary")),
                root.findViewById<View>(id(root, "ongoing_activity_chip_secondary"))
            ),
            "notifications" to listOfNotNull(
                root.findViewById<View>(id(root, "notification_icon_area"))
            ),
            "user_switcher" to listOfNotNull(
                root.findViewById<View>(id(root, "user_switcher_container"))
            ),
            "battery" to listOfNotNull(battery)
        )

        val clusterPlaced = booleanArrayOf(false)

        applySide(startContainer, left, placements, systemIcons, clusterPlaced, keepBatteryInCluster)
        applySide(endContainer, right, placements, systemIcons, clusterPlaced, keepBatteryInCluster)

        // If neither Wi-Fi nor cellular appears in the saved order, keep the
        // cluster on the right side.
        if (systemIcons != null && !clusterPlaced[0]) {
            placeView(endContainer, systemIcons)
        }

        startContainer.requestLayout()
        endContainer.requestLayout()
        lastRootHash = System.identityHashCode(root)
        lastSignature = signature
    }

    private fun applySide(
        target: ViewGroup,
        order: List<String>,
        views: Map<String, List<View>>,
        statusIconsCluster: View?,
        clusterPlaced: BooleanArray,
        keepBatteryInCluster: Boolean
    ) {
        order.forEach { key ->
            if (key in clusterKeys) {
                if (!clusterPlaced[0] && statusIconsCluster != null) {
                    placeView(target, statusIconsCluster)
                    clusterPlaced[0] = true
                }
                return@forEach
            }

            views[key].orEmpty().forEach { view ->
                if (key == "battery" && keepBatteryInCluster) {
                    // The battery keeps its place inside the icon cluster so
                    // that the ROM keeps managing its visibility.
                    placeInsideCluster(statusIconsCluster as? ViewGroup, view)
                } else {
                    placeView(target, view)
                    if (key == "battery") {
                        // A battery that was moved to the other side is not
                        // managed by the cluster any more.
                        view.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    /** Moves [view] to the end of its current cluster without touching it. */
    private fun placeInsideCluster(cluster: ViewGroup?, view: View) {
        if (cluster == null) return
        val params = view.layoutParams
        (view.parent as? ViewGroup)?.removeView(view)
        if (params != null) {
            cluster.addView(view, params)
        } else {
            cluster.addView(view)
        }
    }

    private fun placeView(target: ViewGroup, view: View) {
        val original = view.layoutParams
        val sameParent = view.parent === target

        if (sameParent) {
            // The element already lives in this container: only move it to
            // the requested position and keep its original layout params.
            // Recreating the params would drop the "width = 0, weight = 1"
            // of the icon cluster, the icons would be squeezed together and
            // a wide gap would appear between the Wi-Fi icon and the
            // battery.
            target.removeView(view)
            if (original != null) {
                target.addView(view, original)
            } else {
                target.addView(view)
            }
            return
        }

        (view.parent as? ViewGroup)?.removeView(view)

        // The view is re-parented into a different container, so the original
        // layout params must not be reused as they are: a "width = 0,
        // weight = 1" element would expand and push the other icons apart.
        // Only the margins are kept, the size is always wrap content.
        val margins = (original as? ViewGroup.MarginLayoutParams)?.let {
            intArrayOf(it.leftMargin, it.topMargin, it.rightMargin, it.bottomMargin)
        }
        val height = (original?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT)
            .takeIf { it > 0 } ?: ViewGroup.LayoutParams.WRAP_CONTENT

        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            height
        )
        if (margins != null) {
            params.setMargins(margins[0], margins[1], margins[2], margins[3])
        }
        target.addView(view, params)
    }

    private fun reorderWifiAndMobile(container: ViewGroup, wifiFirst: Boolean) {
        var wifi: View? = null
        var mobile: View? = null
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            val slot = runCatching {
                child.javaClass.getMethod("getSlot").invoke(child) as? String
            }.getOrNull().orEmpty().lowercase()
            if (wifi == null && slot.contains("wifi")) wifi = child
            if (mobile == null && slot.contains("mobile")) mobile = child
        }

        val wifiView = wifi ?: return
        val mobileView = mobile ?: return
        val wifiIndex = container.indexOfChild(wifiView)
        val mobileIndex = container.indexOfChild(mobileView)

        if (wifiFirst && wifiIndex > mobileIndex) {
            container.removeView(wifiView)
            container.addView(wifiView, mobileIndex)
        } else if (!wifiFirst && mobileIndex > wifiIndex) {
            container.removeView(mobileView)
            container.addView(mobileView, wifiIndex)
        }
    }

    private fun id(root: View, name: String): Int =
        root.context.resources.getIdentifier(name, "id", root.context.packageName)

    /**
     * Parses a comma-separated order and drops unknown/duplicate keys. Missing
     * elements are handled globally in [applyOrder] so a key can never be
     * placed on both sides.
     */
    private fun readOrder(key: String, default: String): MutableList<String> {
        val raw = runCatching { modulePrefs.getString(key, default) }.getOrDefault(default)
        val result = mutableListOf<String>()
        raw.orEmpty().split(',').forEach { part ->
            val value = part.trim()
            if (value in allKeys && value !in result) {
                result.add(value)
            }
        }
        return result
    }
}
