package com.motolauncher.mod.hooks

/** One optical palette for cover/inner Docks, folder previews and open folders. */
internal object LauncherGlassPalette {
    const val RIM_WIDTH_DP = 0.9f
    const val INNER_RIM_WIDTH_DP = 0.6f

    fun bodyColors(night: Boolean): IntArray = if (night) {
        intArrayOf(argb(82, 65, 74, 90), argb(112, 24, 30, 42))
    } else {
        intArrayOf(argb(100, 245, 250, 255), argb(62, 165, 188, 210))
    }

    fun reflectionColors(night: Boolean): IntArray = intArrayOf(
        argb(if (night) 26 else 46, 255, 255, 255), 0, argb(9, 255, 255, 255))

    fun rimColors(): IntArray = intArrayOf(
        argb(164, 255, 255, 255), argb(48, 255, 255, 255),
        argb(18, 255, 255, 255), argb(94, 255, 255, 255))

    /** Remove stock tint opacity, but retain native fades and explicitly transparent layers. */
    fun folderOpacity(drawableAlpha: Int, fillAlpha: Int, stockFillAlpha: Int): Int =
        (drawableAlpha.toLong() * fillAlpha / stockFillAlpha.coerceAtLeast(1))
            .coerceIn(0L, 255L).toInt()

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}
