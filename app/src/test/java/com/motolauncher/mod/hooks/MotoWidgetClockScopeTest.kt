package com.motolauncher.mod.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotoWidgetClockScopeTest {
    @Test fun recognizesClockDigitsAndImages() {
        listOf("clock", "digital_clock", "time_text", "hour_tens", "minute_units", "clock_colon")
            .forEach { assertTrue(it, MotoWidgetClockScope.isGlassPart(it)) }
    }

    @Test fun recognizesDateWeatherAndTemperature() {
        listOf("clock_date", "day_of_week", "weekday", "month_text", "calendar_year",
            "weather_time", "weather_icon", "weather_description", "temperature", "temp_high",
            "temp_low", "feels_like_temperature", "forecast", "humidity", "precipitation",
            "city", "location_name", "time_zone", "ampm", "weatherIcon", "currentTemperature",
            "dateText", "WEATHER_ICON")
            .forEach { assertTrue(it, MotoWidgetClockScope.isGlassPart(it)) }
    }

    @Test fun excludesBackgroundsControlsAndUnknownStyles() {
        listOf("clock_background", "clock_container", "weather_background", "weather_bg",
            "bg_temperature", "date_container", "weather_refresh", "weather_settings_button",
            "alarm_time", "battery", "battery_temperature", "weatherBackground",
            "unknown", "", "runtime", "update_status")
            .forEach { assertFalse(it, MotoWidgetClockScope.isGlassPart(it)) }
    }

    @Test fun onlyAppliesToEnabledMotoWidgetsOnWorkspace() {
        val moto = "com.motorola.timeweatherwidget"
        assertTrue(MotoWidgetClockScope.shouldApply(true, moto, true))
        assertFalse(MotoWidgetClockScope.shouldApply(false, moto, true))
        assertFalse(MotoWidgetClockScope.shouldApply(true, moto, false))
        assertFalse(MotoWidgetClockScope.shouldApply(true, "com.example.clock", true))
        assertFalse(MotoWidgetClockScope.shouldApply(true, null, true))
    }
}
