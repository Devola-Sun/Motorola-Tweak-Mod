package com.motolauncher.mod.hooks

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Bypass vendor onDraw blur/tint on keyguard AND light shade; keep native geometry. */
internal class KeyguardNotificationSurface(clazz: Class<*>) {
    // AOSP's background helper owns actual width/height, corner radii and expansion
    // bounds. Do not reproduce those with a full-view rounded rectangle/overlay.
    private val drawBackground: Method = clazz.declaredMethods.firstOrNull {
        it.name == "draw" && it.parameterTypes.contentEquals(
            arrayOf(Canvas::class.java, Drawable::class.java))
    }?.apply { isAccessible = true }
        ?: error("Native notification background geometry helper unavailable")
    private val clipTop = field(clazz, "mClipTopAmount")
    private val clipBottom = field(clazz, "mClipBottomAmount")
    private val actualHeight = field(clazz, "mActualHeight")
    private val expanding = field(clazz, "mExpandAnimationRunning")

    fun draw(view: View, canvas: Canvas, surface: Drawable) {
        val save = canvas.save()
        try {
            // These clips live in onDraw rather than the geometry helper. Preserve
            // them for grouped/dismissed cards, but not during native expansion.
            if (expanding?.getBoolean(view) != true) {
                val height = actualHeight?.getInt(view) ?: view.height
                val top = NotificationGlassClip.top(clipTop?.getInt(view) ?: 0, height)
                val bottom = NotificationGlassClip.bottom(clipBottom?.getInt(view) ?: 0, height, top)
                canvas.clipRect(0, top, view.width, bottom)
            }
            drawBackground.invoke(view, canvas, surface)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private fun field(clazz: Class<*>, name: String): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            val found = current.declaredFields.firstOrNull { it.name == name }
            if (found != null) return found.apply { isAccessible = true }
            current = current.superclass
        }
        return null
    }
}
