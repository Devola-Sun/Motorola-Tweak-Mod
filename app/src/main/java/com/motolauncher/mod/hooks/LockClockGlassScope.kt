package com.motolauncher.mod.hooks

/** Explicit time-only targets: never style dates, widgets or status-bar clocks. */
internal object LockClockGlassScope {
    private val hosts = setOf("keyguard_clock_container", "keyguard_clock_switch",
        "lockscreen_clock_view", "lockscreen_clock_view_large", "large_clock_container",
        "small_clock_container", "clock_large", "clock_small")

    fun isClockHost(id: String, className: String): Boolean = id in hosts ||
        className == "com.android.keyguard.KeyguardClockSwitch" ||
        className.endsWith(".FlexClockView")

    fun isClockText(tag: String?, className: String, id: String = "",
        insideFace: Boolean = false, text: CharSequence? = null): Boolean {
        if (tag == "clock_face_time") return true
        if (className == "com.android.keyguard.AnimatableClockView" ||
            className == "com.android.systemui.shared.clocks.AnimatableClockView") return true
        if (!insideFace) return false
        val excluded = listOf("date", "battery", "weather", "smartspace", "alarm", "statusbar", "status_bar")
        if (excluded.any { id.contains(it, true) || className.contains(it, true) }) return false
        if (className.endsWith(".SimpleDigitalClockTextView") ||
            className.endsWith(".ClockDigitView")) return true
        return !text.isNullOrBlank() && text.any { it.isDigit() } &&
            text.all { it.isDigit() || it.isWhitespace() || it in ":.\u2236\u200e\u200f" }
    }

    fun shouldApply(enabled: Boolean, keyguard: Boolean, dozing: Boolean,
        interactive: Boolean, hardwareAccelerated: Boolean): Boolean =
        enabled && keyguard && !dozing && interactive && hardwareAccelerated
}
