package com.motolauncher.mod

/** Numeric release tags, with SemVer prerelease precedence (never lexical version order). */
internal object ReleaseVersion {
    private val pattern = Regex(
        "^[vV]?(\\d+(?:\\.\\d+)*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$"
    )

    fun isStable(tag: String): Boolean =
        pattern.matchEntire(tag.trim())?.groupValues?.get(2) == ""

    fun isNewer(candidate: String, installed: String): Boolean {
        val next = pattern.matchEntire(candidate.trim()) ?: return false
        val current = pattern.matchEntire(installed.trim()) ?: return false
        val nextParts = next.groupValues[1].split('.').map { it.toBigInteger() }
        val currentParts = current.groupValues[1].split('.').map { it.toBigInteger() }
        for (index in 0 until maxOf(nextParts.size, currentParts.size)) {
            val comparison = (nextParts.getOrNull(index) ?: java.math.BigInteger.ZERO)
                .compareTo(currentParts.getOrNull(index) ?: java.math.BigInteger.ZERO)
            if (comparison != 0) return comparison > 0
        }
        val nextPre = next.groupValues[2]
        val currentPre = current.groupValues[2]
        if (nextPre == currentPre) return false
        if (nextPre.isEmpty()) return true
        if (currentPre.isEmpty()) return false
        val nextIds = nextPre.split('.')
        val currentIds = currentPre.split('.')
        for (index in 0 until minOf(nextIds.size, currentIds.size)) {
            val left = nextIds[index]
            val right = currentIds[index]
            val leftNumber = left.takeIf { it.all(Char::isDigit) }?.toBigInteger()
            val rightNumber = right.takeIf { it.all(Char::isDigit) }?.toBigInteger()
            val comparison = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (comparison != 0) return comparison > 0
        }
        return nextIds.size > currentIds.size
    }
}
