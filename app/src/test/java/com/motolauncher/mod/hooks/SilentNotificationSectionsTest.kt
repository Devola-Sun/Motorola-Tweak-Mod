package com.motolauncher.mod.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

class SilentNotificationSectionsTest {
    private class MinimizedSection {
        fun isInSection(entry: String) = entry == "minimized"
    }

    private class SilentSection {
        fun isInSection(entry: String) = entry == "silent"
    }

    private open class MergedSection(private val kind: String) {
        fun isInSection(entry: String) = entry == kind
    }

    private class InheritedSection(kind: String) : MergedSection(kind)

    private fun result(sections: SilentNotificationSections, section: Any, entry: String, stock: Boolean) =
        sections.route(section, stock) { original, method -> method.invoke(original, entry) == true }

    @Test fun distinctSectionClassesPromoteUsingTheMinimizedPredicate() {
        val sections = SilentNotificationSections()
        val minimized = MinimizedSection()
        val silent = SilentSection()
        val methods = mutableListOf<Method>()
        sections.register(minimized, silent, methods::add)

        assertEquals(2, methods.size)
        assertFalse(result(sections, minimized, "minimized", true))
        assertTrue(result(sections, silent, "minimized", false))
        assertTrue(result(sections, silent, "silent", true))
        assertFalse(result(sections, silent, "alerting", false))
    }

    @Test fun mergedImplementationsAreHookedOnceAndRoutedByInstance() {
        val sections = SilentNotificationSections()
        val minimized = MergedSection("minimized")
        val silent = MergedSection("silent")
        val methods = mutableListOf<Method>()
        sections.register(minimized, silent, methods::add)
        sections.register(minimized, silent, methods::add)

        assertEquals(1, methods.size)
        assertFalse(result(sections, minimized, "minimized", true))
        assertTrue(result(sections, silent, "minimized", false))
        assertTrue(result(sections, MergedSection("alerting"), "alerting", true))
        assertFalse(result(sections, MergedSection("alerting"), "minimized", false))
    }

    @Test fun inheritedMembershipIsResolvedOnItsDeclaringClass() {
        val sections = SilentNotificationSections()
        val minimized = InheritedSection("minimized")
        val silent = InheritedSection("silent")
        val methods = mutableListOf<Method>()
        sections.register(minimized, silent, methods::add)

        assertEquals(MergedSection::class.java, methods.single().declaringClass)
        assertTrue(result(sections, silent, "minimized", false))
    }

    @Test fun unsupportedSectionLeavesBothRolesUnchanged() {
        val sections = SilentNotificationSections()
        val minimized = MinimizedSection()
        val silent = Any()
        val methods = mutableListOf<Method>()

        assertTrue(runCatching { sections.register(minimized, silent, methods::add) }.isFailure)
        assertTrue(methods.isEmpty())
        assertTrue(result(sections, minimized, "minimized", true))
        assertFalse(result(sections, silent, "minimized", false))
    }

    @Test fun failedHookInstallationDoesNotSuppressNotifications() {
        val sections = SilentNotificationSections()
        val minimized = MinimizedSection()
        val silent = SilentSection()
        var calls = 0

        assertTrue(runCatching {
            sections.register(minimized, silent) {
                if (++calls == 2) error("Hook installation failed")
            }
        }.isFailure)
        assertTrue(result(sections, minimized, "minimized", true))
        assertFalse(result(sections, silent, "minimized", false))

        sections.register(minimized, silent) { calls++ }
        assertEquals(3, calls)
        assertTrue(result(sections, silent, "minimized", false))
    }
}
