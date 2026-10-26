package com.motolauncher.mod.hooks

/** Pixel geometry only; native scrolling chooses the active card. */
internal object NotificationStackGeometry {
    data class Placement(val y: Float, val scale: Float, val alpha: Float, val depth: Int, val visible: Boolean)

    fun scrollRange(heights: List<Float>, gap: Float): Float =
        heights.dropLast(1).sumOf { (it + gap).toDouble() }.toFloat().coerceAtLeast(0f)

    fun place(heights: List<Float>, scroll: Float, gap: Float, peek: Float): List<Placement> {
        if (heights.isEmpty()) return emptyList()
        val offset = scroll.coerceIn(0f, scrollRange(heights, gap))
        var first = 0
        var remaining = offset
        while (first < heights.lastIndex && remaining >= heights[first] + gap) {
            remaining -= heights[first] + gap
            first++
        }
        val progress = (remaining / (heights[first] + gap).coerceAtLeast(1f)).coerceIn(0f, 1f)
        return heights.indices.map { index ->
            val relative = index - first
            when {
                relative < 0 -> Placement(-heights[index] - gap, 1f, 0f, 0, false)
                relative == 0 -> Placement(-remaining, 1f, 1f, 3, true)
                relative <= 2 -> {
                    val depth = relative - progress
                    Placement(peek * depth, 1f - .035f * depth, 1f - .12f * depth, 3 - relative, true)
                }
                else -> Placement(peek * 2f, .93f, 0f, 0, false)
            }
        }
    }
}
