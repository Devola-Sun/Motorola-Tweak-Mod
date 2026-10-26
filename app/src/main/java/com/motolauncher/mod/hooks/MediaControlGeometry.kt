package com.motolauncher.mod.hooks

/** One non-overlapping row; the output picker lives in the header, not in this row. */
internal object MediaControlGeometry {
    data class Slot(val left: Int, val size: Int)

    fun actions(width: Int, inset: Int, preferredSize: Int, count: Int): List<Slot> {
        if (count <= 0) return emptyList()
        val available = (width - inset * 2).coerceAtLeast(0)
        val size = minOf(preferredSize, available / count).coerceAtLeast(0)
        return List(count) { index ->
            val center = inset + available.toLong() * (index * 2 + 1) / (count * 2)
            Slot(center.toInt() - size / 2, size)
        }
    }
}
