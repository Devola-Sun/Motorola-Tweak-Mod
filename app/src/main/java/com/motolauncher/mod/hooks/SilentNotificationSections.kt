package com.motolauncher.mod.hooks

import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Resolve the actual section implementations, including R8-merged and inherited predicates. */
internal class SilentNotificationSections {
    private data class Section(
        val minimized: Boolean,
        val originalMinimized: WeakReference<Any>,
        val originalMembership: Method
    )

    private val sections = WeakHashMap<Any, Section>()
    private val hookedMethods = mutableSetOf<Method>()

    @Synchronized
    fun register(minimized: Any, silent: Any, hook: (Method) -> Unit) {
        require(minimized !== silent) { "Minimized and silent sections must be distinct" }
        if (sections.containsKey(minimized) && sections.containsKey(silent)) return
        val minimizedMethod = membershipMethod(minimized.javaClass)
        val silentMethod = membershipMethod(silent.javaClass)
        require(minimizedMethod.parameterTypes.contentEquals(silentMethod.parameterTypes)) {
            "Incompatible notification section predicates"
        }
        // Publish neither role until both hooks are installed, so an unsupported
        // silent implementation cannot remove notifications from the shade.
        setOf(minimizedMethod, silentMethod).forEach { method ->
            if (method !in hookedMethods) {
                hook(method)
                hookedMethods.add(method)
            }
        }
        val original = WeakReference(minimized)
        sections[minimized] = Section(true, original, minimizedMethod)
        sections[silent] = Section(false, original, minimizedMethod)
    }

    fun route(
        sectioner: Any,
        stockResult: Boolean,
        matchesMinimized: (Any, Method) -> Boolean
    ): Boolean {
        val section = synchronized(this) { sections[sectioner] } ?: return stockResult
        if (section.minimized) return false
        if (stockResult) return true
        val original = section.originalMinimized.get() ?: return stockResult
        return matchesMinimized(original, section.originalMembership)
    }

    private fun membershipMethod(type: Class<*>): Method {
        var current: Class<*>? = type
        while (current != null) {
            val candidates = current.declaredMethods.filter {
                it.name == "isInSection" && it.parameterCount == 1 &&
                    it.returnType == Boolean::class.javaPrimitiveType && !it.isBridge
            }
            if (candidates.isNotEmpty()) return candidates.single().apply { isAccessible = true }
            current = current.superclass
        }
        error("No notification section predicate on ${type.name}")
    }
}
