package com.motolauncher.mod.hooks

/** Pure optical policy shared by notification, keyguard and control-center cards. */
internal object SystemUiGlassPalette {
    // A near-clear light surface cannot support dark text over dark wallpaper.
    // Keep a luminance floor, paired with opaque dark content, even without blur.
    fun cardColors(night: Boolean, hasBackdrop: Boolean, keyguard: Boolean = false): IntArray {
        val alpha = when {
            night && keyguard -> if (hasBackdrop) intArrayOf(48, 68) else intArrayOf(116, 144)
            night -> if (hasBackdrop) intArrayOf(76, 102) else intArrayOf(156, 184)
            keyguard -> if (hasBackdrop) intArrayOf(168, 180) else intArrayOf(184, 196)
            else -> if (hasBackdrop) intArrayOf(176, 188) else intArrayOf(192, 204)
        }
        return if (night) intArrayOf(argb(alpha[0], 30, 32, 36), argb(alpha[1], 18, 20, 24))
        else intArrayOf(argb(alpha[0], 245, 248, 252), argb(alpha[1], 218, 228, 238))
    }

    // Modern QS intentionally uses this dark material in BOTH system themes.
    // Light notifications must reuse it, not approximate it with a white wash.
    fun controlCenterColors(hasBackdrop: Boolean): IntArray = cardColors(true, hasBackdrop)

    fun notificationColors(night: Boolean, hasBackdrop: Boolean, keyguard: Boolean): IntArray =
        if (night) cardColors(true, hasBackdrop, keyguard) else controlCenterColors(hasBackdrop)

    // In light mode the native onDraw can add another white/blur pass. Reuse
    // only its geometry helper, just as we already do for keyguard notifications.
    fun isolateNotificationDraw(night: Boolean, keyguard: Boolean): Boolean = keyguard || !night

    fun cardEdgeShadeColors(night: Boolean): IntArray = if (night) {
        intArrayOf(0, 0, 0, 0, 0, 0)
    } else {
        intArrayOf(argb(22, 28, 42, 56), argb(6, 28, 42, 56), 0, 0,
            argb(7, 28, 42, 56), argb(26, 28, 42, 56))
    }

    fun cardRimColors(night: Boolean): IntArray = if (night) {
        intArrayOf(argb(142, 255, 255, 255), argb(42, 255, 255, 255),
            argb(16, 255, 255, 255), argb(76, 255, 255, 255))
    } else {
        intArrayOf(argb(190, 255, 255, 255), argb(60, 255, 255, 255),
            argb(26, 38, 56, 74), argb(120, 255, 255, 255))
    }

    fun cardReflectionColors(night: Boolean, keyguard: Boolean): IntArray = intArrayOf(
        argb(if (keyguard) 10 else if (night) 20 else 16, 255, 255, 255),
        0, argb(if (keyguard) 4 else 7, 255, 255, 255))

    fun cardBlurRadiusDp(keyguard: Boolean): Float = if (keyguard) 12f else 18f

    // Both liquid themes now use dark glass, just like Modern QS. Keep the API's
    // theme/liquid arguments so callers cannot silently revert to dark-on-dark text.
    @Suppress("UNUSED_PARAMETER")
    fun primaryContentColor(night: Boolean, liquid: Boolean): Int =
        0xffffffff.toInt()

    fun secondaryContentColor(night: Boolean, liquid: Boolean): Int =
        (primaryContentColor(night, liquid) and 0x00ffffff) or (204 shl 24)

    fun progressTrackColor(night: Boolean, liquid: Boolean): Int =
        (primaryContentColor(night, liquid) and 0x00ffffff) or
            (85 shl 24)

    fun nativeControlAlpha(active: Boolean): Int = if (active) 230 else 32
    fun controlBodyAlpha(active: Boolean): Int = if (active) 48 else 255

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}
