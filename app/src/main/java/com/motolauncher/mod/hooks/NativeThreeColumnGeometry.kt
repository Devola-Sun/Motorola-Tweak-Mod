package com.motolauncher.mod.hooks

import com.motolauncher.mod.hooks.InnerSnapGeometry.Box

/** Pixel geometry for a horizontal three-stage layout. No density or task overrides. */
internal object NativeThreeColumnGeometry {
    fun isLandscapeRoot(root: Box): Boolean = root.right.toLong() - root.left > root.bottom.toLong() - root.top &&
        root.bottom > root.top

    data class Layout(val stageA: Box, val stageB: Box, val stageC: Box) {
        val stages get() = listOf(stageA, stageB, stageC)
        val dividerAB get() = Box(stageA.right, stageA.top, stageB.left, stageA.bottom)
        val dividerBC get() = Box(stageB.right, stageB.top, stageC.left, stageB.bottom)
    }

    /** Use one effective display rectangle and the width of each actual divider gap. */
    fun calculate(effective: Box, dividerPx: Int): Layout? {
        if (effective.bottom <= effective.top || dividerPx <= 0) return null
        val available = effective.right.toLong() - effective.left - 2L * dividerPx
        if (available < 3) return null

        // Round the cumulative cuts to nearest so the left and right widths match.
        // Use Long intermediates even when valid coordinates straddle the origin.
        val cut1 = effective.left.toLong() + (available + 1) / 3
        val cut2 = effective.left.toLong() + (2 * available + 1) / 3 + dividerPx
        return Layout(
            effective.copy(right = cut1.toInt()),
            effective.copy(left = (cut1 + dividerPx).toInt(), right = cut2.toInt()),
            effective.copy(left = (cut2 + dividerPx).toInt()),
        )
    }

    /** The native divider positions are relative to the layout root's left edge. */
    fun fromDividers(effective: Box, dividerPx: Int, first: Int, second: Int): Layout? =
        fromDividers(effective, dividerPx, first.toLong(), second.toLong())

    private fun fromDividers(effective: Box, dividerPx: Int, first: Long, second: Long): Layout? {
        val equal = calculate(effective, dividerPx) ?: return null
        val width = effective.right.toLong() - effective.left
        // Keep even a very large drag from placing any app offscreen. This
        // limits geometry only; app compatibility is still enforced by Shell.
        val min = minimumWidth(equal).toLong()
        val cut1 = first.coerceIn(min, width - 2L * dividerPx - 2L * min)
        val cut2 = second.coerceIn(cut1 + dividerPx + min, width - dividerPx - min)
        val left = effective.left.toLong()
        return Layout(
            effective.copy(right = (left + cut1).toInt()),
            effective.copy(left = (left + cut1 + dividerPx).toInt(), right = (left + cut2).toInt()),
            effective.copy(left = (left + cut2 + dividerPx).toInt()),
        )
    }

    /** Move just the grabbed divider; the other cut must not drift at the clamp. */
    fun moveDivider(effective: Box, dividerPx: Int, current: Layout, index: Int, position: Int): Layout? {
        val equal = calculate(effective, dividerPx) ?: return null
        val min = minimumWidth(equal).toLong()
        val width = effective.right.toLong() - effective.left
        val first = current.dividerAB.left.toLong() - effective.left
        val second = current.dividerBC.left.toLong() - effective.left
        if (fromDividers(effective, dividerPx, first, second) != current) return null
        return when (index) {
            0 -> fromDividers(effective, dividerPx, position.toLong().coerceIn(min, second - dividerPx - min), second)
            1 -> fromDividers(effective, dividerPx, first, position.toLong().coerceIn(first + dividerPx + min, width - dividerPx - min))
            else -> null
        }
    }

    private fun minimumWidth(equal: Layout) = (equal.stageB.width / 2).coerceAtLeast(1)
}
