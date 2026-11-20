package com.motolauncher.mod.hooks

/** Organizer-owned roots can expose an app's metadata after a split is dismissed. */
internal object NativeThreeColumnTaskGuard {
    data class Identity(val id: Int, val token: Any)

    fun isApplicationCandidate(task: Identity, shellRoots: Collection<Identity>): Boolean =
        task.id > 0 && shellRoots.none { it.id == task.id || it.token == task.token }

    fun canReparent(tasks: List<Identity>, shellRoots: Collection<Identity>): Boolean =
        shellRoots.size == 4 && shellRoots.map { it.id }.toSet().size == 4 &&
            shellRoots.map { it.token }.toSet().size == 4 && shellRoots.all { it.id > 0 } &&
            tasks.size == 3 && tasks.map { it.id }.toSet().size == 3 && tasks.map { it.token }.toSet().size == 3 &&
            tasks.all { isApplicationCandidate(it, shellRoots) }

    /** Keep the ROM's physical slot order, but never accept duplicates or foreign stages. */
    fun <T : Any> orderedStages(active: List<T>, expected: Collection<T>): List<T>? =
        active.takeIf { it.size == 3 && expected.size == 3 && it.toSet().size == 3 && it.toSet() == expected.toSet() }
}
