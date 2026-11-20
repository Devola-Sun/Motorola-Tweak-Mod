package com.motolauncher.mod.hooks

/** Selection is local until the third choice. A request is not evidence of Shell ownership. */
internal class NativeThreeColumnFlow {
    enum class Phase { IDLE, SELECTING_RIGHT, SELECTING_CENTER, COMMITTING, ACTIVE }

    data class Columns(val left: Int?, val center: Int?, val right: Int?) {
        fun complete(): Set<Int>? = listOfNotNull(left, center, right).toSet().takeIf { it.size == 3 }
    }

    var phase = Phase.IDLE
        private set
    var anchor = -1
        private set
    private var right = -1
    var requested: Columns? = null
        private set
    private var active = emptySet<Int>()

    val selecting get() = phase == Phase.SELECTING_RIGHT || phase == Phase.SELECTING_CENTER

    fun begin(taskId: Int): Boolean {
        if (phase != Phase.IDLE || taskId < 0) return false
        anchor = taskId
        phase = Phase.SELECTING_RIGHT
        return true
    }

    fun chooseRight(task: Int): Boolean {
        if (phase != Phase.SELECTING_RIGHT || task < 0 || task == anchor) return false
        right = task
        phase = Phase.SELECTING_CENTER
        return true
    }

    fun chooseCenter(task: Int): Columns? {
        if (phase != Phase.SELECTING_CENTER || task < 0 || task == anchor || task == right) return null
        return Columns(anchor, task, right).also {
            requested = it
            phase = Phase.COMMITTING
        }
    }

    /** Call only after Shell has settled, with the actual stage membership, not UI visibility. */
    fun observe(columns: Columns) {
        if (phase == Phase.ACTIVE) {
            if (columns.complete() != active) reset()
            return
        }
        if (phase != Phase.COMMITTING || columns != requested) return
        active = columns.complete() ?: return
        phase = Phase.ACTIVE
    }

    fun owns(taskId: Int, columns: Columns): Boolean =
        phase == Phase.ACTIVE && taskId in active && columns.complete() == active

    fun reset() {
        phase = Phase.IDLE
        anchor = -1
        right = -1
        requested = null
        active = emptySet()
    }
}
