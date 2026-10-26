package com.motolauncher.mod.hooks

/** Fail closed: unknown widget contents must retain their stock rendering. */
internal object MotoWidgetClockScope {
    private val contentTokens = setOf(
        "clock", "time", "hour", "hours", "minute", "minutes", "colon", "digital", "ampm",
        "date", "day", "days", "weekday", "week", "month", "year", "calendar",
        "weather", "temp", "temperature", "condition", "conditions", "forecast",
        "humidity", "precipitation", "uv", "sunrise", "sunset", "city", "location", "zone"
    )

    fun isGlassPart(resourceName: String): Boolean {
        val name = resourceName.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase(java.util.Locale.ROOT)
        // Only foreground text/icons: never tint the widget's background or controls.
        if (listOf("battery", "alarm", "background", "container", "button", "refresh", "settings")
                .any { it in name }) return false
        val tokens = name.split('_')
        if (tokens.any { it == "bg" }) return false
        return tokens.any { it in contentTokens }
    }

    fun shouldApply(enabled: Boolean, provider: String?, onWorkspace: Boolean): Boolean =
        enabled && provider == "com.motorola.timeweatherwidget" && onWorkspace
}
