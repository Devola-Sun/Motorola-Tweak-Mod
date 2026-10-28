package com.motolauncher.mod.hooks

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.current
import com.highcapable.yukihookapi.hook.type.java.BooleanType
import com.highcapable.yukihookapi.hook.type.java.IntType
import com.motolauncher.mod.ErrorLogger
import com.motolauncher.mod.HookConfig
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Status bar battery customization (Android 16 / SystemUI).
 *
 * `com.android.systemui.battery.BatteryMeterView` is a `LinearLayout` that owns
 * two children:
 *
 *  - `mBatteryIconView` (`ImageView`) - the battery icon. Style/size changes
 *    apply to this child; only leading spacing is adjusted on status-bar hosts.
 *  - `mBatteryPercentView` (`TextView`) - the percentage text.
 *
 * The percentage is controlled through `mShowPercentMode`
 * (0 = AUTO, 1 = ON, 2 = OFF) and the level is delivered through
 * `onBatteryLevelChanged(int level, boolean pluggedIn)`.
 *
 * Every hook is wrapped in `runCatching`: when a setting is disabled the
 * original behaviour is passed through untouched, and a missing member on
 * another ROM simply disables that single hook.
 */
object BatteryHook : YukiBaseHooker() {

    private const val TAG = "BatteryHook"
    private const val BATTERY_METER_VIEW = "com.android.systemui.battery.BatteryMeterView"

    /** `mShowPercentMode` values used by BatteryMeterView. */
    private const val MODE_ON = 1
    private const val MODE_OFF = 2

    /** Keeps the native drawable so the icon can be restored at any time. */
    private val originalDrawables = WeakHashMap<ImageView, Drawable>()
    private val originalLayouts = WeakHashMap<ImageView, IconLayout>()
    private val originalLeadingSpacing = WeakHashMap<View, LeadingSpacing>()

    private data class LeadingSpacing(
        val paddingStart: Int,
        val marginStart: Int?,
        val relativeMargin: Boolean
    )

    private data class IconLayout(
        val width: Int,
        val height: Int,
        val paddingLeft: Int,
        val paddingTop: Int,
        val paddingRight: Int,
        val paddingBottom: Int,
        val scaleType: ImageView.ScaleType,
        val gravity: Int?
    )

    private val iconStyle: HookConfig.BatteryIconStyle
        get() = HookConfig.BatteryIconStyle.fromKey(
            modulePrefs.getString(
                HookConfig.KEY_BATTERY_ICON_STYLE,
                HookConfig.DEFAULT_BATTERY_ICON_STYLE
            )
        )

    private val percentStyle: HookConfig.BatteryPercentStyle
        get() = HookConfig.BatteryPercentStyle.fromKey(
            modulePrefs.getString(
                HookConfig.KEY_BATTERY_PERCENT_STYLE,
                HookConfig.DEFAULT_BATTERY_PERCENT_STYLE
            )
        )

    /** Keeps the legacy "hide battery percent" preference working after the UI migration. */
    private val effectivePercentStyle: HookConfig.BatteryPercentStyle
        get() = if (
            modulePrefs.getBoolean(HookConfig.KEY_SB_HIDE_BATTERY_PERCENT, false)
        ) HookConfig.BatteryPercentStyle.HIDDEN else percentStyle

    private val displayPolicy: BatteryDisplayPolicy
        get() = BatteryDisplayPolicy.resolve(iconStyle, effectivePercentStyle)

    override fun onHook() {
        runCatching {
            findClass(BATTERY_METER_VIEW).hook {
                // Forces the percentage mode before Motorola reads it.
                runCatching {
                    injectMember {
                        method {
                            name = "setPercentShowMode"
                            param(IntType)
                        }
                        beforeHook {
                            runCatching {
                                when (displayPolicy.showExternalPercent) {
                                    true -> args(0).set(MODE_ON)
                                    false -> args(0).set(MODE_OFF)
                                    null -> Unit
                                }
                            }.onFailure {
                                ErrorLogger.log(TAG, "setPercentShowMode update failed", it)
                            }
                        }
                    }
                }.onFailure {
                    ErrorLogger.log(TAG, "setPercentShowMode hook failed", it)
                }

                // Re-applies the state after every code path that decides
                // whether the percentage is shown.
                listOf(
                    "innerUpdateShowPercent",
                    "updateShowPercent",
                    "updatePercentText",
                    "setPercentTextAtCurrentLevel",
                    "onAttachedToWindow",
                    "scaleBatteryMeterViews"
                ).forEach { methodName ->
                    runCatching {
                        injectMember {
                            method {
                                name = methodName
                                emptyParam()
                            }
                            beforeHook {
                                runCatching {
                                    if (methodName == "scaleBatteryMeterViews") {
                                        // Let the system recalculate its own dimensions first.
                                        // The after hook then saves the current density's native layout.
                                        instance.current {
                                            (instance as? View)?.let { restoreLeadingSpacing(it) }
                                            val icon = field { name = "mBatteryIconView" }.any() as? ImageView
                                            if (icon != null) restoreNativeLayout(icon)
                                        }
                                    }
                                }.onFailure {
                                    ErrorLogger.log(TAG, "$methodName layout reset failed", it)
                                }
                            }
                            afterHook {
                                runCatching {
                                    applyPercent(instance)
                                    applyIcon(instance)
                                }.onFailure {
                                    ErrorLogger.log(TAG, "$methodName update failed", it)
                                }
                            }
                        }
                    }.onFailure {
                        ErrorLogger.log(TAG, "$methodName hook failed", it)
                    }
                }

                // Android 16 signature: onBatteryLevelChanged(int level, boolean pluggedIn).
                runCatching {
                    injectMember {
                        method {
                            name = "onBatteryLevelChanged"
                            param(IntType, BooleanType)
                        }
                        afterHook {
                            runCatching {
                                applyPercent(instance)
                                applyIcon(instance)
                            }.onFailure {
                                ErrorLogger.log(TAG, "Battery level update failed", it)
                            }
                        }
                    }
                }.onFailure {
                    ErrorLogger.log(TAG, "onBatteryLevelChanged hook failed", it)
                }

                runCatching {
                    injectMember {
                        method {
                            name = "updateColors"
                            param(IntType, IntType, IntType)
                        }
                        afterHook {
                            runCatching {
                                applyIcon(instance)
                            }.onFailure {
                                ErrorLogger.log(TAG, "Battery color update failed", it)
                            }
                        }
                    }
                }.onFailure {
                    ErrorLogger.log(TAG, "updateColors hook failed", it)
                }

                runCatching {
                    injectMember {
                        method {
                            name = "onConfigurationChanged"
                            param(android.content.res.Configuration::class.java)
                        }
                        afterHook {
                            runCatching {
                                applyPercent(instance)
                                applyIcon(instance)
                            }.onFailure {
                                ErrorLogger.log(TAG, "Battery configuration update failed", it)
                            }
                        }
                    }
                }.onFailure {
                    ErrorLogger.log(TAG, "onConfigurationChanged hook failed", it)
                }
            }
        }.onFailure {
            ErrorLogger.log(TAG, "BatteryMeterView hooks could not be installed", it)
        }
    }

    private fun applyPercent(target: Any?) {
        val showText = displayPolicy.showExternalPercent ?: return
        target?.current {
            field { name = "mShowPercentMode" }.set(if (showText) MODE_ON else MODE_OFF)
            val percentView = field { name = "mBatteryPercentView" }.any() as? TextView
            percentView?.visibility = if (showText) View.VISIBLE else View.GONE
        }
    }

    private fun applyIcon(target: Any?) {
        val policy = displayPolicy
        val style = policy.iconStyle
        val host = target as? View ?: return
        val context = host.context ?: return

        target.current {
            // Keep native container size/visibility and percentage layout intact.
            // Custom status-bar icons only need a smaller leading gap on the host.
            val icon = field { name = "mBatteryIconView" }.any() as? ImageView
                ?: return@current
            when (style) {
                HookConfig.BatteryIconStyle.SYSTEM -> {
                    restoreLeadingSpacing(host)
                    icon.visibility = View.VISIBLE
                    restoreNativeIcon(icon)
                }

                HookConfig.BatteryIconStyle.HIDDEN -> {
                    restoreLeadingSpacing(host)
                    restoreNativeIcon(icon)
                    icon.visibility = View.GONE
                }

                HookConfig.BatteryIconStyle.CIRCLE,
                HookConfig.BatteryIconStyle.PILL -> {
                    icon.visibility = View.VISIBLE
                    val level = field { name = "mLevel" }.int()
                    val pluggedIn = field { name = "mPluggedIn" }.boolean()
                    val textColor = field { name = "mTextColor" }.int().let {
                        if (it == 0) Color.WHITE else it
                    }
                    rememberNativeIcon(icon)
                    applyLeadingSpacing(host)
                    applyCustomLayout(icon, policy.customSize ?: return@current)
                    val current = icon.drawable as? BatteryStyleDrawable
                    if (current != null && current.style == style) {
                        current.update(level, pluggedIn, textColor, policy.showPercentInside)
                    } else {
                        icon.setImageDrawable(BatteryStyleDrawable(
                            context = context,
                            style = style,
                            level = level,
                            charging = pluggedIn,
                            tint = textColor,
                            showPercent = policy.showPercentInside
                        ))
                    }
                }
            }
        }
    }

    private fun rememberNativeIcon(icon: ImageView) {
        if (originalDrawables.containsKey(icon)) return
        val current = icon.drawable ?: return
        if (current is BatteryStyleDrawable) return
        originalDrawables[icon] = current
    }

    /**
     * Restores the native drawable. Never clears the image: when the native
     * drawable was not captured (the icon was never replaced) the icon has to
     * stay exactly as the system drew it, otherwise the battery icon would
     * disappear from the status bar.
     */
    private fun restoreNativeIcon(icon: ImageView) {
        restoreNativeLayout(icon)
        val original = originalDrawables.remove(icon)
        if (icon.drawable is BatteryStyleDrawable && original != null) {
            icon.setImageDrawable(original)
        }
    }

    private fun applyLeadingSpacing(host: View) {
        // Do not tighten battery meters in QS or other unrelated layouts.
        var parent = host.parent
        var statusBar = false
        while (parent is View) {
            if (parent.javaClass.simpleName in setOf("PhoneStatusBarView", "KeyguardStatusBarView")) {
                statusBar = true
                break
            }
            parent = parent.parent
        }
        if (!statusBar) return
        val margins = host.layoutParams as? ViewGroup.MarginLayoutParams
        if (!originalLeadingSpacing.containsKey(host)) {
            originalLeadingSpacing[host] = LeadingSpacing(
                host.paddingStart,
                margins?.let { if (it.isMarginRelative) it.marginStart else it.leftMargin },
                margins?.isMarginRelative == true
            )
        }
        // The drawable already has a small inset. Remove stacked native start
        // padding and cap the external gap at 1dp, without using overlap/translation.
        if (host.paddingStart != 0) {
            host.setPaddingRelative(0, host.paddingTop, host.paddingEnd, host.paddingBottom)
        }
        if (margins != null) {
            val relative = originalLeadingSpacing[host]?.relativeMargin == true
            val current = if (relative) margins.marginStart else margins.leftMargin
            val compact = current.coerceIn(0, host.resources.displayMetrics.density.roundToInt())
            if (current != compact) {
                if (relative) margins.marginStart = compact else margins.leftMargin = compact
                host.layoutParams = margins
            }
        }
    }

    private fun restoreLeadingSpacing(host: View) {
        val original = originalLeadingSpacing.remove(host) ?: return
        host.setPaddingRelative(original.paddingStart, host.paddingTop, host.paddingEnd, host.paddingBottom)
        val margins = host.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        original.marginStart?.let {
            if (original.relativeMargin) margins.marginStart = it else margins.leftMargin = it
            host.layoutParams = margins
        }
    }

    private fun applyCustomLayout(icon: ImageView, size: BatteryIconSize) {
        val params = icon.layoutParams ?: return
        if (!originalLayouts.containsKey(icon)) {
            originalLayouts[icon] = IconLayout(
                params.width, params.height,
                icon.paddingLeft, icon.paddingTop, icon.paddingRight, icon.paddingBottom,
                icon.scaleType, (params as? LinearLayout.LayoutParams)?.gravity
            )
        }
        val density = icon.resources.displayMetrics.density
        val width = (size.widthDp * density).roundToInt()
        val height = (size.heightDp * density).roundToInt()
        val linearParams = params as? LinearLayout.LayoutParams
        val centerVertically = linearParams != null && linearParams.gravity != Gravity.CENTER_VERTICAL
        if (params.width != width || params.height != height || centerVertically) {
            params.width = width
            params.height = height
            linearParams?.gravity = Gravity.CENTER_VERTICAL
            icon.layoutParams = params
        }
        if (icon.paddingLeft != 0 || icon.paddingTop != 0 || icon.paddingRight != 0 || icon.paddingBottom != 0) {
            icon.setPadding(0, 0, 0, 0)
        }
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private fun restoreNativeLayout(icon: ImageView) {
        val original = originalLayouts.remove(icon) ?: return
        icon.layoutParams?.let { params ->
            params.width = original.width
            params.height = original.height
            if (params is LinearLayout.LayoutParams && original.gravity != null) {
                params.gravity = original.gravity
            }
            icon.layoutParams = params
        }
        icon.setPadding(original.paddingLeft, original.paddingTop, original.paddingRight, original.paddingBottom)
        icon.scaleType = original.scaleType
    }
}
