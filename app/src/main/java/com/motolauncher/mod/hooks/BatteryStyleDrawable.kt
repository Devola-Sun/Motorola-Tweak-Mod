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
import com.motolauncher.mod.HookConfig.BatteryIconStyle
import kotlin.math.roundToInt

/** Scale fixed design viewports into compact, explicitly sized status-bar slots. */
internal class BatteryStyleDrawable(
    context: Context,
    val style: BatteryIconStyle,
    private var level: Int,
    private var charging: Boolean,
    private var tint: Int,
    private var showPercent: Boolean
) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val size = requireNotNull(BatteryIconSize.forStyle(style))
    private val viewportWidth = if (style == BatteryIconStyle.CIRCLE) 22f else 32f
    private val viewportHeight = if (style == BatteryIconStyle.CIRCLE) 22f else 20f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    // Keep the charging indicator yellow-green even when the host tints the icon.
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val ring = RectF(1.4f, 1.4f, 20.6f, 20.6f)
    private val body = RectF(0.9f, 2f, 29f, 18f)
    private val terminal = RectF(28.5f, 7f, 31.5f, 13f)
    private val fill = RectF()
    private val clip = Path().apply { addRoundRect(body, 3.2f, 3.2f, Path.Direction.CW) }
    private var alphaValue = 255

    fun update(level: Int, charging: Boolean, tint: Int, showPercent: Boolean) {
        if (this.level == level && this.charging == charging && this.tint == tint && this.showPercent == showPercent) return
        this.level = level
        this.charging = charging
        this.tint = tint
        this.showPercent = showPercent
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        // Preserve aspect ratio even if the parent temporarily constrains the view.
        val scale = minOf(bounds.width() / viewportWidth, bounds.height() / viewportHeight)
        val saved = canvas.save()
        try {
            canvas.translate(bounds.exactCenterX() - viewportWidth * scale / 2f,
                bounds.exactCenterY() - viewportHeight * scale / 2f)
            canvas.scale(scale, scale)
            val percent = level.coerceIn(0, 100)
            // Charging never changes the body, level ring or percentage color.
            paint.color = tint
            textPaint.color = tint
            textPaint.alpha = alphaValue
            if (style == BatteryIconStyle.CIRCLE) drawCircle(canvas, percent) else drawBattery(canvas, percent)
            if (charging) drawChargingBadge(canvas)
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    private fun drawCircle(canvas: Canvas, percent: Int) {
        // Keep the level track/arc in percentage mode too: never turn it into a badge.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f
        paint.strokeCap = Paint.Cap.BUTT
        paint.alpha = (alphaValue * 0.24f).roundToInt()
        canvas.drawOval(ring, paint)
        paint.alpha = alphaValue
        canvas.drawArc(ring, -90f, 360f * percent / 100f, false, paint)
        if (showPercent) {
            drawPercentText(canvas, percent, 11f, 11f, 10f, 14.5f)
        }
    }

    private fun drawChargingBadge(canvas: Canvas) {
        // Fully inside the top-right corner so neither parent clipping nor the
        // ImageView bounds cut off the dot; digits and the battery terminal stay clear.
        val cx = viewportWidth - 3f
        val cy = 3f
        badgePaint.alpha = alphaValue
        badgePaint.style = Paint.Style.FILL
        badgePaint.color = COLOR_CHARGING_BADGE
        canvas.drawCircle(cx, cy, 2.4f, badgePaint)
        badgePaint.style = Paint.Style.STROKE
        badgePaint.strokeWidth = 0.6f
        badgePaint.color = Color.DKGRAY
        canvas.drawCircle(cx, cy, 2.4f, badgePaint)
    }

    private fun drawBattery(canvas: Canvas, percent: Int) {
        // Reference: a borderless solid battery silhouette with inset dark digits,
        // not a thin outline or a small floating fill inside an invisible frame.
        paint.style = Paint.Style.FILL
        paint.alpha = (alphaValue * 0.25f).roundToInt()
        canvas.drawRoundRect(body, 3.2f, 3.2f, paint)
        fill.set(body.left, body.top, body.left + body.width() * percent / 100f, body.bottom)
        if (percent > 0) {
            val saved = canvas.save()
            canvas.clipPath(clip)
            paint.alpha = alphaValue
            canvas.drawRect(fill, paint)
            canvas.restoreToCount(saved)
        }
        paint.alpha = (alphaValue * 0.55f).roundToInt()
        canvas.drawRoundRect(terminal, 0.8f, 0.8f, paint)
        if (showPercent) {
            // The unfilled section retains normal foreground digits. Invert only
            // over the solid charge fill so every level, including 0/100, is readable.
            drawPercentText(canvas, percent, body.centerX(), body.centerY(), 13f, 24f)
            if (percent > 0) {
                val saved = canvas.save()
                canvas.clipPath(clip)
                canvas.clipRect(fill)
                textPaint.color = if (Color.luminance(tint) > 0.5f) Color.BLACK else Color.WHITE
                drawPercentText(canvas, percent, body.centerX(), body.centerY(), 13f, 24f)
                canvas.restoreToCount(saved)
            }
        }
    }

    private fun drawPercentText(canvas: Canvas, percent: Int, cx: Float, cy: Float, textSize: Float, maxWidth: Float) {
        val text = percent.toString()
        textPaint.textSize = textSize
        val textWidth = textPaint.measureText(text)
        if (textWidth > maxWidth) textPaint.textSize *= maxWidth / textWidth
        val metrics = textPaint.fontMetrics
        canvas.drawText(text, cx, cy - (metrics.ascent + metrics.descent) / 2f, textPaint)
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        // Do not let a uniform host tint turn the contrasting inset digits into
        // the same color as the battery fill. Circle text still follows host tint.
        textPaint.colorFilter = if (style == BatteryIconStyle.CIRCLE) colorFilter else null
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = (size.widthDp * density).roundToInt()
    override fun getIntrinsicHeight(): Int = (size.heightDp * density).roundToInt()

    private companion object {
        const val COLOR_CHARGING_BADGE = 0xFFB2FF59.toInt()
    }
}
