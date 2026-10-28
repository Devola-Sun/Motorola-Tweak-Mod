package com.motolauncher.mod.hooks

/** One non-overlapping row, including a reserved trailing output-picker column. */
internal object MediaControlGeometry {
    data class Slot(val left: Int, val size: Int)

    /** Five visual columns: optional action, previous, play, next, output. */
    fun referenceActions(width: Int, inset: Int, preferredSize: Int,
        semantic: List<String>, custom: List<String>): Map<String, Slot> {
        val outer = (custom.size + 1).coerceAtLeast(2)
        val slots = actions(width, inset, preferredSize, outer + 3)
        val leading = outer / 2
        return buildMap {
            listOf("actionPrev", "actionPlayPause", "actionNext").forEachIndexed { index, id ->
                if (id in semantic) put(id, slots[leading + index])
            }
            val free = slots.indices.filter { it !in leading until leading + 3 && it != slots.lastIndex }
            custom.zip(free).forEach { (id, index) -> put(id, slots[index]) }
        }
    }

    fun referenceOutput(width: Int, inset: Int, preferredSize: Int, customCount: Int): Slot =
        actions(width, inset, preferredSize, (customCount + 1).coerceAtLeast(2) + 3).last()

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
