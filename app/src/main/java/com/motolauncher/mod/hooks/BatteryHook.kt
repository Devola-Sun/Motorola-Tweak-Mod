package com.motolauncher.mod.hooks

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.current
import com.highcapable.yukihookapi.hook.type.java.BooleanType
import com.highcapable.yukihookapi.hook.type.java.IntType
import com.motolauncher.mod.ErrorLogger
import com.motolauncher.mod.HookConfig
import java.util.WeakHashMap

/**
 * Status bar battery customization (Android 16 / SystemUI).
 *
 * `com.android.systemui.battery.BatteryMeterView` is a `LinearLayout` that owns
 * two children:
 *
 *  - `mBatteryIconView` (`ImageView`) - the battery icon. Every style change is
 *    applied to this child only, never to the container itself.
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
    private const val MODE_AUTO = 0
    private const val MODE_ON = 1
    private const val MODE_OFF = 2

    /** Keeps the native drawable so the icon can be restored at any time. */
    private val originalDrawables = WeakHashMap<ImageView, Drawable>()

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

    /**
     * IN_ICON needs a drawable that can render text. A circle fills the small
     * square icon slot much better than a horizontal pill, so use it when the
     * user did not explicitly choose CIRCLE or PILL.
     */
    private val effectiveIconStyle: HookConfig.BatteryIconStyle
        get() = if (
            effectivePercentStyle == HookConfig.BatteryPercentStyle.IN_ICON &&
            iconStyle == HookConfig.BatteryIconStyle.SYSTEM
        ) HookConfig.BatteryIconStyle.CIRCLE else iconStyle

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
                                when (effectivePercentStyle) {
                                    HookConfig.BatteryPercentStyle.ALWAYS -> args(0).set(MODE_ON)
                                    HookConfig.BatteryPercentStyle.HIDDEN,
                                    HookConfig.BatteryPercentStyle.IN_ICON -> args(0).set(MODE_OFF)
                                    // AUTO: keep whatever the system decided.
                                    HookConfig.BatteryPercentStyle.SYSTEM -> Unit
                                }
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
                    "setPercentTextAtCurrentLevel"
                ).forEach { methodName ->
                    runCatching {
                        injectMember {
                            method {
                                name = methodName
                                emptyParam()
                            }
                            afterHook {
                                runCatching {
                                    applyPercent(instance)
                                    applyIcon(instance)
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
                            }
                        }
                    }
                }.onFailure {
                    ErrorLogger.log(TAG, "onBatteryLevelChanged hook failed", it)
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
        val style = effectivePercentStyle
        if (style == HookConfig.BatteryPercentStyle.SYSTEM) return
        // If the icon slot is too small for a legible custom icon, keep the
        // native icon and show the percentage as text instead.
        val showText = style == HookConfig.BatteryPercentStyle.ALWAYS ||
            (style == HookConfig.BatteryPercentStyle.IN_ICON && !canDrawInIcon(target))
        target?.current {
            field { name = "mShowPercentMode" }.set(if (showText) MODE_ON else MODE_OFF)
            val percentView = field { name = "mBatteryPercentView" }.any() as? TextView
            percentView?.visibility = if (showText) View.VISIBLE else View.GONE
        }
    }

    /**
     * The Razr's icon slot is roughly 12dp square. Anything smaller than this
     * cannot render three digits legibly, so use the native icon and the
     * percentage TextView in that case.
     */
    private fun canDrawInIcon(target: Any?): Boolean {
        val host = target as? View ?: return false
        val minSize = (10 * host.resources.displayMetrics.density).toInt()
        var canDraw = false
        runCatching {
            target.current {
                val icon = field { name = "mBatteryIconView" }.any() as? ImageView
                canDraw = icon != null && icon.width >= minSize && icon.height >= minSize
            }
        }
        return canDraw
    }

    private fun applyIcon(target: Any?) {
        val style = effectiveIconStyle
        val showPercentInside =
            effectivePercentStyle == HookConfig.BatteryPercentStyle.IN_ICON
        val host = target as? View ?: return
        val context = host.context ?: return

        target.current {
            // Only the icon child view is touched; the LinearLayout container
            // and the percentage view are left to the system.
            val icon = field { name = "mBatteryIconView" }.any() as? ImageView
                ?: return@current
            val level = field { name = "mLevel" }.int()
            val pluggedIn = field { name = "mPluggedIn" }.boolean()
            val textColor = field { name = "mTextColor" }.int().let {
                if (it == 0) Color.WHITE else it
            }

            when (style) {
                HookConfig.BatteryIconStyle.SYSTEM -> {
                    icon.visibility = View.VISIBLE
                    restoreNativeIcon(icon)
                }

                HookConfig.BatteryIconStyle.HIDDEN -> {
                    icon.visibility = View.GONE
                }

                HookConfig.BatteryIconStyle.CIRCLE,
                HookConfig.BatteryIconStyle.PILL -> {
                    icon.visibility = View.VISIBLE
                    if (!canDrawInIcon(target)) {
                        // Too small for a legible custom icon: restore the
                        // native drawable. applyPercent() shows the text then.
                        restoreNativeIcon(icon)
                        return@current
                    }
                    rememberNativeIcon(icon)
                    icon.setImageDrawable(
                        BatteryStyleDrawable(
                            context = context,
                            style = style,
                            level = level,
                            charging = pluggedIn,
                            tint = textColor,
                            showPercent = showPercentInside
                        )
                    )
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
        if (icon.drawable !is BatteryStyleDrawable) return
        val original = originalDrawables[icon] ?: return
        icon.setImageDrawable(original)
    }

    /**
     * Lightweight circle/pill icon. It intentionally does not depend on
     * Motorola's internal drawable classes so a ROM update cannot break it.
     */
    private class BatteryStyleDrawable(
        context: Context,
        private val style: HookConfig.BatteryIconStyle,
        private val level: Int,
        private val charging: Boolean,
        private val tint: Int,
        private val showPercent: Boolean
    ) : Drawable() {

        private val density = context.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val arcRect = RectF()
        private var alphaValue = 255

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            if (bounds.isEmpty) return

            val width = bounds.width().toFloat()
            val height = bounds.height().toFloat()
            val shortSide = minOf(width, height)
            val percent = level.coerceIn(0, 100)
            val accent = if (charging) COLOR_CHARGING else tint

            if (style == HookConfig.BatteryIconStyle.CIRCLE) {
                val radius = shortSide * 0.46f
                val cx = bounds.exactCenterX()
                val cy = bounds.exactCenterY()

                if (showPercent) {
                    // A filled badge is much more legible than an outline when
                    // the status bar only gives us a ~12dp square.
                    paint.style = Paint.Style.FILL
                    paint.color = accent
                    paint.alpha = alphaValue
                    canvas.drawCircle(cx, cy, radius, paint)

                    drawPercentText(
                        canvas = canvas,
                        text = percent.toString(),
                        centerX = cx,
                        centerY = cy,
                        textSize = shortSide * 0.38f,
                        textColor = contrastColor(accent)
                    )
                } else {
                    paint.style = Paint.Style.STROKE
                    paint.color = accent
                    paint.alpha = alphaValue
                    paint.strokeWidth = (shortSide * 0.08f).coerceAtLeast(density)
                    canvas.drawCircle(cx, cy, radius, paint)

                    paint.strokeWidth = (shortSide * 0.16f).coerceAtLeast(density * 1.5f)
                    paint.strokeCap = Paint.Cap.ROUND
                    arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
                    canvas.drawArc(arcRect, -90f, 360f * percent / 100f, false, paint)
                    paint.strokeCap = Paint.Cap.BUTT
                }
            } else {
                val body = RectF(
                    width * 0.03f,
                    height * 0.20f,
                    width * 0.84f,
                    height * 0.80f
                )
                val radius = body.height() * 0.28f

                if (showPercent) {
                    paint.style = Paint.Style.FILL
                    paint.color = accent
                    paint.alpha = alphaValue
                    canvas.drawRoundRect(body, radius, radius, paint)
                    canvas.drawRoundRect(
                        RectF(width * 0.84f, height * 0.38f, width * 0.94f, height * 0.62f),
                        height * 0.04f,
                        height * 0.04f,
                        paint
                    )

                    drawPercentText(
                        canvas = canvas,
                        text = percent.toString(),
                        centerX = body.centerX(),
                        centerY = body.centerY(),
                        textSize = height * 0.36f,
                        textColor = contrastColor(accent)
                    )
                } else {
                    paint.style = Paint.Style.STROKE
                    paint.color = accent
                    paint.alpha = alphaValue
                    paint.strokeWidth = (height * 0.09f).coerceAtLeast(density)
                    canvas.drawRoundRect(body, radius, radius, paint)
                    canvas.drawRoundRect(
                        RectF(width * 0.84f, height * 0.38f, width * 0.94f, height * 0.62f),
                        height * 0.04f,
                        height * 0.04f,
                        paint
                    )

                    if (percent > 0) {
                        val fill = RectF(
                            body.left,
                            body.bottom - body.height() * percent / 100f,
                            body.right,
                            body.bottom
                        )
                        val clip = Path().apply {
                            addRoundRect(body, radius, radius, Path.Direction.CW)
                        }
                        canvas.save()
                        canvas.clipPath(clip)
                        paint.style = Paint.Style.FILL
                        paint.alpha = (alphaValue * 0.45f).toInt().coerceIn(0, 255)
                        canvas.drawRect(fill, paint)
                        canvas.restore()
                    }
                }
            }
        }

        private fun drawPercentText(
            canvas: Canvas,
            text: String,
            centerX: Float,
            centerY: Float,
            textSize: Float,
            textColor: Int
        ) {
            textPaint.color = textColor
            textPaint.alpha = alphaValue
            textPaint.textSize = textSize
            val metrics = textPaint.fontMetrics
            val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(text, centerX, baseline, textPaint)
        }

        private fun contrastColor(background: Int): Int =
            if (Color.luminance(background) > 0.55f) Color.BLACK else Color.WHITE

        override fun setAlpha(alpha: Int) {
            alphaValue = alpha.coerceIn(0, 255)
            paint.alpha = alphaValue
            textPaint.alpha = alphaValue
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            textPaint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = (18 * density).toInt()

        override fun getIntrinsicHeight(): Int = (18 * density).toInt()
    }

    private const val COLOR_CHARGING = 0xFF34C759.toInt()
}
