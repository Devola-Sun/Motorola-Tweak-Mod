package com.motolauncher.mod.hooks

import com.motolauncher.mod.hooks.InnerSnapGeometry.Box
import kotlin.math.roundToInt

/** Pure policy shared by the server and regression tests. */
internal object ThreeColumnPolicy {
    data class Style(val headerDp: Int, val densityPercent: Int)
    data class Card(val visible: Box, val task: Box) {
        val cropTop get() = visible.top - task.top
    }

    data class Candidate(val id: Int, val user: Int, val pkg: String, val mode: Int,
        val standard: Boolean, val root: Boolean, val embedded: Boolean, val lastActive: Long)

    fun select(tasks: List<Candidate>, anchor: Int, excluded: Set<String>): List<Int> {
        val first = tasks.firstOrNull { it.id == anchor } ?: return emptyList()
        fun allowed(t: Candidate) = t.user == first.user && t.standard && t.root && !t.embedded &&
            t.mode in listOf(1, 5) && t.pkg !in excluded
        if (!allowed(first)) return emptyList()
        val ordered = listOf(first) + tasks.filter { it.id != anchor }.sortedByDescending { it.lastActive }
        val selected = ordered.filter(::allowed).distinctBy { it.pkg }.take(3)
        return if (selected.size == 3) selected.map { it.id } else emptyList()
    }

    fun cards(safe: Box, density: Float, headerDp: Int): List<Card> {
        if (!density.isFinite() || density <= 0 || safe.width <= safe.height || safe.height <= 0) return emptyList()
        val gap = (3 * density).roundToInt().coerceAtLeast(1)
        val available = safe.width.toLong() - 2L * gap
        if (available / 3 < (150 * density).roundToInt().coerceAtLeast(1)) return emptyList()
        val header = (headerDp * density).roundToInt()
        // Preserve both outside edges; distribute remainder pixels across the cards.
        return (0..2).map { i ->
            val visible = Box(safe.left + (available * i / 3).toInt() + i * gap, safe.top,
                safe.left + (available * (i + 1) / 3).toInt() + i * gap, safe.bottom)
            Card(visible, if (header > 0) visible.copy(top = visible.top - header) else visible)
        }
    }

    fun targetDensity(displayDpi: Int, percent: Int): Int = (displayDpi * (percent / 100f)).roundToInt()

    fun <T> swapWithCenter(items: MutableList<T>, index: Int) {
        if (items.size != 3 || index !in items.indices) return
        val peer = if (index == 1) 2 else 1
        val moved = items[index]
        items[index] = items[peer]
        items[peer] = moved
    }

    private val menuTitle = Regex("Additional view container of Task=(\\d+)")

    fun menuTask(title: String): Int? = menuTitle.matchEntire(title)?.groupValues?.get(1)?.toIntOrNull()

    /** Shell uses positive, fixed-size windows. Leave enough room for the menu and surrounding UI. */
    fun expandedMenuHeight(original: Int, extra: Int, displayHeight: Int): Int? {
        if (original <= 0 || extra <= 0 || displayHeight <= 0) return null
        val total = original.toLong() + extra
        return if (total <= displayHeight.toLong() * 3 / 4) total.toInt() else null
    }
}
