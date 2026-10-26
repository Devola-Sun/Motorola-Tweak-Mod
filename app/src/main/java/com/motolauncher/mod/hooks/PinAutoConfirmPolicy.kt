package com.motolauncher.mod.hooks

/** Length metadata only: never reads, stores or logs a credential. */
internal object PinAutoConfirmPolicy {
    fun applies(enabled: Boolean, selectedLength: Int, storedLength: Int): Boolean =
        enabled && selectedLength in 4..16 && (storedLength == -1 || selectedLength == storedLength)

    fun shouldSubmit(target: Int, entered: Int, appending: Boolean, submitted: Boolean): Boolean =
        appending && !submitted && entered == target
}
