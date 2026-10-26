package com.motolauncher.mod.hooks

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import de.robv.android.xposed.XposedHelpers
import kotlin.math.roundToInt

/** Shared material for notification/media cards and independently enabled keyguard shortcuts. */
internal object LiquidGlassSurface {
    enum class Material { CARD, LAUNCHER }
    /** Keyguard cards share one silhouette as well as the same optical material. */
    fun cardRadius(view: View, keyguard: Boolean): Float =
        (if (keyguard) 30f else 26f) * view.resources.displayMetrics.density

    fun create(
        view: View,
        radius: Float,
        blurAvailable: Boolean,
        keyguard: Boolean,
        night: Boolean,
        oval: Boolean = false,
        liquid: Boolean = true,
        material: Material = Material.CARD,
        backdropAlpha: Int = 255,
        sharedBackdrop: Boolean = false,
        report: (Throwable) -> Unit
    ): Drawable {
        val density = view.resources.displayMetrics.density
        val backdrop = if (blurAvailable) runCatching {
            val root = XposedHelpers.callMethod(view, "getViewRootImpl") ?: error("ViewRootImpl unavailable")
            val drawable = XposedHelpers.callMethod(root, "createBackgroundBlurDrawable") as Drawable
            // Control-center backdrops start inactive and receive their effective
            // ancestor alpha explicitly before being submitted to SurfaceFlinger.
            drawable.alpha = backdropAlpha.coerceIn(0, 255)
            // Preserve wallpaper detail rather than turning the entire card into a milky slab.
            XposedHelpers.callMethod(drawable, "setBlurRadius", ((if (liquid) 18f else 28f) * density).roundToInt().coerceIn(1, 96))
            XposedHelpers.callMethod(drawable, "setCornerRadius", radius)
            // Vendor implementations can start with an opaque black fill. The shared
            // surface below supplies the tint; the backdrop must only contribute blur.
            XposedHelpers.callMethod(drawable, "setColor", Color.TRANSPARENT)
            drawable
        }.onFailure(report).getOrNull() else null
        if (!liquid) {
            // Plain wallpaper blur with an even dark tint: no reflection, rim or gradient.
            val tint = GradientDrawable().apply {
                cornerRadius = radius
                setColor(Color.argb(if (backdrop != null) 170 else 222, 48, 37, 48))
            }
            return if (backdrop != null) LayerDrawable(arrayOf(backdrop, tint)) else tint
        }
        val dark = keyguard || night
        val hasBackdrop = backdrop != null || sharedBackdrop
        val colors = when {
            material == Material.LAUNCHER -> if (dark) intArrayOf(
                Color.argb(82, 65, 74, 90), Color.argb(112, 24, 30, 42)) else intArrayOf(
                Color.argb(100, 245, 250, 255), Color.argb(62, 165, 188, 210))
            dark -> intArrayOf(
                Color.argb(if (hasBackdrop) 76 else 156, 30, 32, 36),
                Color.argb(if (hasBackdrop) 102 else 184, 18, 20, 24))
            else -> intArrayOf(
                Color.argb(if (hasBackdrop) 116 else 222, 255, 255, 255),
                Color.argb(if (hasBackdrop) 146 else 238, 244, 245, 247))
        }
        val surface = LiquidGlassDrawable(radius, density, oval, dark, colors)
        return if (backdrop != null) LayerDrawable(arrayOf(backdrop, surface)) else surface
    }
}

/** Optical cues only: no content blur, bitmap captures or per-frame shader allocations. */
private class LiquidGlassDrawable(
    private val radius: Float,
    private val density: Float,
    private val oval: Boolean,
    private val dark: Boolean,
    private val colors: IntArray
) : Drawable() {
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val reflection = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = (0.75f * density).coerceAtLeast(1f)
    }
    private val innerRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = (0.5f * density).coerceAtLeast(0.75f)
    }
    private val surfaceBounds = RectF()
    private val rimBounds = RectF()
    private val innerBounds = RectF()
    private val paints = arrayOf(body, reflection, rim, innerRim)
    private var opacity = 255

    override fun onBoundsChange(bounds: Rect) {
        surfaceBounds.set(bounds)
        if (bounds.isEmpty) return
        rimBounds.set(surfaceBounds)
        rimBounds.inset(rim.strokeWidth / 2f, rim.strokeWidth / 2f)
        innerBounds.set(surfaceBounds)
        innerBounds.inset(rim.strokeWidth + innerRim.strokeWidth / 2f,
            rim.strokeWidth + innerRim.strokeWidth / 2f)
        body.shader = LinearGradient(0f, surfaceBounds.top, 0f, surfaceBounds.bottom,
            colors, null, Shader.TileMode.CLAMP)
        // A faint wash, not an opaque white patch across the controls.
        reflection.shader = LinearGradient(surfaceBounds.left, surfaceBounds.top,
            surfaceBounds.right, surfaceBounds.bottom,
            intArrayOf(Color.argb(if (dark) 20 else 38, 255, 255, 255),
                Color.TRANSPARENT, Color.argb(7, 255, 255, 255)),
            floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP)
        // Directional edge reflections replace the old uniform white outline.
        rim.shader = LinearGradient(surfaceBounds.left, surfaceBounds.top,
            surfaceBounds.right, surfaceBounds.bottom,
            intArrayOf(Color.argb(142, 255, 255, 255), Color.argb(42, 255, 255, 255),
                Color.argb(16, 255, 255, 255), Color.argb(76, 255, 255, 255)),
            floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
        innerRim.shader = LinearGradient(0f, surfaceBounds.top, 0f, surfaceBounds.bottom,
            intArrayOf(Color.argb(if (dark) 28 else 20, 0, 0, 0), Color.TRANSPARENT,
                Color.argb(if (dark) 18 else 30, 0, 0, 0)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty || opacity == 0) return
        drawSurface(canvas, surfaceBounds, radius, body)
        drawSurface(canvas, surfaceBounds, radius, reflection)
        drawSurface(canvas, rimBounds, (radius - rim.strokeWidth / 2f).coerceAtLeast(0f), rim)
        if (innerBounds.width() > 0f && innerBounds.height() > 0f) {
            drawSurface(canvas, innerBounds, (radius - rim.strokeWidth - innerRim.strokeWidth / 2f)
                .coerceAtLeast(0f), innerRim)
        }
    }

    private fun drawSurface(canvas: Canvas, rect: RectF, corner: Float, paint: Paint) {
        if (oval) canvas.drawOval(rect, paint) else canvas.drawRoundRect(rect, corner, corner, paint)
    }

    override fun getOutline(outline: Outline) {
        if (oval) outline.setOval(bounds) else outline.setRoundRect(bounds, radius)
    }

    override fun setAlpha(alpha: Int) {
        val value = alpha.coerceIn(0, 255)
        if (opacity == value) return
        opacity = value
        paints.forEach { it.alpha = value }
        invalidateSelf()
    }

    override fun getAlpha(): Int = opacity

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paints.forEach { it.colorFilter = colorFilter }
        invalidateSelf()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
