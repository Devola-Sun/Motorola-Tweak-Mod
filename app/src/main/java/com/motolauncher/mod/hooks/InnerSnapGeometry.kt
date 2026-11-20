package com.motolauncher.mod.hooks

/** Android-free geometry: do not resize windows to make them fit. */
internal object InnerSnapGeometry {
    enum class Axis { HORIZONTAL, VERTICAL }
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    fun snap(window: Box, safe: Box, position: Int): Box? {
        if (window.width <= 0 || window.height <= 0 ||
            window.width > safe.width || window.height > safe.height) return null
        val x = when (position.coerceIn(0, 2)) {
            0 -> safe.left
            1 -> safe.left + (safe.width - window.width) / 2
            else -> safe.right - window.width
        }
        val y = window.top.coerceIn(safe.top, safe.bottom - window.height)
        return Box(x, y, x + window.width, y + window.height)
    }

    /** Whole button must fit in the gap; never place an input window over the app. */
    fun buttonX(gapLeft: Int, gapRight: Int, size: Int): Int? =
        if (size > 0 && gapRight - gapLeft >= size) gapLeft + (gapRight - gapLeft - size) / 2 else null

    fun position(window: Box, safe: Box, tolerance: Int): Int = when {
        kotlin.math.abs(window.left - safe.left) <= tolerance -> 0
        kotlin.math.abs(window.right - safe.right) <= tolerance -> 2
        else -> 1
    }

    /** Task fullscreen does not imply activity fullscreen (Settings two-pane UI). */
    fun supportedActivity(taskMode: Int, activityMode: Int, embedded: Boolean): Boolean =
        !embedded && taskMode in listOf(1, 5) && activityMode == taskMode

    fun axis(window: Box, safe: Box, size: Int, freeform: Boolean): Axis? {
        if (window.width <= 0 || window.height <= 0 || size <= 0) return null
        if (buttonX(safe.left, window.left.coerceAtMost(safe.right), size) != null ||
            buttonX(window.right.coerceAtLeast(safe.left), safe.right, size) != null) return Axis.HORIZONTAL
        if (!freeform && (buttonX(safe.top, window.top.coerceAtMost(safe.bottom), size) != null ||
            buttonX(window.bottom.coerceAtLeast(safe.top), safe.bottom, size) != null)) return Axis.VERTICAL
        return null
    }

    fun position(window: Box, safe: Box, tolerance: Int, axis: Axis): Int =
        if (axis == Axis.HORIZONTAL) position(window, safe, tolerance)
        else position(window.transpose(), safe.transpose(), tolerance)

    /** Fullscreen compatibility windows may extend underneath system bars. */
    fun alignedStart(window: Box, safe: Box, position: Int, axis: Axis): Int? {
        val length = if (axis == Axis.HORIZONTAL) window.width else window.height
        val start = if (axis == Axis.HORIZONTAL) safe.left else safe.top
        val end = if (axis == Axis.HORIZONTAL) safe.right else safe.bottom
        if (length <= 0 || length > end - start) return null
        return when (position.coerceIn(0, 2)) {
            0 -> start
            1 -> start + (end - start - length) / 2
            else -> end - length
        }
    }

    /** One directional button and one center button, fully contained in the gap. */
    fun controls(window: Box, safe: Box, size: Int, axis: Axis): List<Pair<Int, Box>> {
        if (size <= 0) return emptyList()
        if (axis == Axis.VERTICAL) return controls(window.transpose(), safe.transpose(), size, Axis.HORIZONTAL)
            .map { it.first to it.second.transpose() }
        if (safe.height < size * 2) return emptyList()
        val y = (window.top + (window.height - size * 2) / 2).coerceIn(safe.top, safe.bottom - size * 2)
        return listOfNotNull(
            buttonX(safe.left, window.left.coerceAtMost(safe.right), size)?.let {
                0 to Box(it, y, it + size, y + size * 2)
            },
            buttonX(window.right.coerceAtLeast(safe.left), safe.right, size)?.let {
                2 to Box(it, y, it + size, y + size * 2)
            }
        )
    }

    private fun Box.transpose() = Box(top, left, bottom, right)
}
