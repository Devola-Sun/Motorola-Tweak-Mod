package com.motolauncher.mod.hooks

/** Pure policy for the native folder blur and its independently drawn container. */
internal object FolderGlassPolicy {
    /** Paint a square even if the ROM supplies stale portrait bounds. */
    fun containerSide(width: Int, height: Int): Int = minOf(width, height).coerceAtLeast(0)

    /** Fit every row into the width-sized container without scaling icon artwork. */
    fun squareCellHeight(side: Int, verticalPadding: Int, rows: Int, rowGap: Int): Int {
        if (side <= 0 || rows <= 0) return 0
        return ((side - verticalPadding.coerceAtLeast(0) -
            rowGap.coerceAtLeast(0) * (rows - 1)) / rows).coerceAtLeast(0)
    }

    fun blurProgress(nativeProgress: Float, percent: Int): Float =
        nativeProgress * ControlCenterBlurPolicy.scale(percent)

    fun containerOpacity(fullscreen: Boolean, drawableAlpha: Int, fillAlpha: Int, stockFillAlpha: Int): Int =
        if (fullscreen) 255 else LauncherGlassPalette.folderOpacity(drawableAlpha, fillAlpha, stockFillAlpha)
}
