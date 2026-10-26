package com.motolauncher.mod.hooks

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import com.motolauncher.mod.ErrorLogger
import kotlin.math.roundToInt

/** Background-only optical material: never blurs launcher icons or text. */
internal object LauncherGlassSurface {
    fun isNight(view: View): Boolean = view.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    fun create(view: View, radius: Float, liquid: Boolean): Drawable {
        if (liquid) return LiquidGlassSurface.create(view, radius,
            blurAvailable = false, keyguard = false, night = isNight(view),
            material = LiquidGlassSurface.Material.LAUNCHER) {
            ErrorLogger.log("MotoLauncherGlass", "create surface", it)
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(150, 235, 240, 248), Color.argb(115, 100, 110, 125))).apply {
            cornerRadius = radius
            setStroke(view.resources.displayMetrics.density.roundToInt().coerceAtLeast(1),
                Color.argb(85, 255, 255, 255))
        }
    }
}
