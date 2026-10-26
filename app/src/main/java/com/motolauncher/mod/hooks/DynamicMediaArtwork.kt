package com.motolauncher.mod.hooks

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/** Decorative overlay: never participates in TransitionLayout constraints or input. */
internal class DynamicMediaArtwork {
    private var source: WeakReference<Drawable>? = null
    private var cover: Cover? = null

    fun apply(root: ViewGroup) {
        val children = descendants(root)
        val icon = children.firstOrNull { name(it) == "icon" } as? ImageView
        val album = children.firstOrNull { name(it) == "album_art" } as? ImageView
        val art = album?.drawable
        if (icon == null || icon.width <= 0 || icon.height <= 0 || art == null) {
            restore(root)
            return
        }
        val d = root.resources.displayMetrics.density
        val iconBounds = Rect(0, 0, icon.width, icon.height)
        root.offsetDescendantRectToMyCoords(icon, iconBounds)
        val size = (maxOf(icon.width, icon.height) * 1.25f).roundToInt()
        val gap = (8f * d).roundToInt()
        val rtl = root.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val left = if (rtl) iconBounds.left - gap - size else iconBounds.right + gap
        val top = iconBounds.centerY() - size / 2
        val target = Rect(left, top, left + size, top + size)
        // Never cover the native output switcher or labels on narrow layouts.
        val blocked = children.filter { name(it) in setOf("media_seamless", "header_title", "header_artist") }
            .filter { it.visibility == View.VISIBLE && it.alpha > 0f }.any {
                val bounds = Rect(0, 0, it.width, it.height)
                root.offsetDescendantRectToMyCoords(it, bounds)
                Rect.intersects(bounds, target)
            }
        if (blocked || left < 0 || top < 0 || target.right > root.width || target.bottom > root.height) {
            restore(root)
            return
        }
        if (source?.get() !== art || cover == null) {
            restore(root)
            // A private drawable avoids changing the full-card artwork's bounds,
            // alpha, tint, or callback while native track-change binding is running.
            val copy = art.constantState?.newDrawable(root.resources)?.mutate() ?: return
            copy.alpha = 255
            copy.clearColorFilter()
            cover = Cover(copy, 6f * d).also { root.overlay.add(it) }
            source = WeakReference(art)
        }
        cover?.let { if (it.bounds != target) it.bounds = target }
    }

    fun restore(root: ViewGroup) {
        cover?.let { root.overlay.remove(it) }
        cover = null
        source = null
    }

    private class Cover(private val image: Drawable, private val radius: Float) : Drawable() {
        private val clip = Path()
        override fun onBoundsChange(bounds: Rect) {
            clip.reset()
            clip.addRoundRect(RectF(bounds), radius, radius, Path.Direction.CW)
            val width = image.intrinsicWidth.coerceAtLeast(1)
            val height = image.intrinsicHeight.coerceAtLeast(1)
            val scale = maxOf(bounds.width().toFloat() / width, bounds.height().toFloat() / height)
            val w = (width * scale).roundToInt()
            val h = (height * scale).roundToInt()
            val left = bounds.centerX() - w / 2
            val top = bounds.centerY() - h / 2
            image.setBounds(left, top, left + w, top + h)
        }
        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            try { canvas.clipPath(clip); image.draw(canvas) } finally { canvas.restoreToCount(save) }
        }
        override fun setAlpha(alpha: Int) { image.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { image.colorFilter = colorFilter; invalidateSelf() }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun descendants(root: ViewGroup): List<View> = buildList {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            add(child)
            if (child is ViewGroup) addAll(descendants(child))
        }
    }

    private fun name(view: View): String =
        if (view.id <= 0 || (view.id ushr 24) == 0) "" else view.resources.getResourceEntryName(view.id)
}
