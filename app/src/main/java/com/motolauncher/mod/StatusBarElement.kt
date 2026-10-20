package com.motolauncher.mod

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * Status bar elements that can be reordered by the user.
 *
 * The keys are persisted in SharedPreferences and read by
 * [com.motolauncher.mod.hooks.StatusBarOrderHook] in SystemUI.
 */
enum class StatusBarElement(
    val key: String,
    @StringRes val titleRes: Int,
    @DrawableRes val iconRes: Int,
    val defaultOnLeft: Boolean
) {
    CLOCK(
        "clock",
        R.string.status_bar_element_clock,
        R.drawable.ic_clock,
        true
    ),
    ONGOING(
        "ongoing",
        R.string.status_bar_element_ongoing,
        R.drawable.ic_ongoing,
        true
    ),
    NOTIFICATIONS(
        "notifications",
        R.string.status_bar_element_notifications,
        R.drawable.ic_notifications,
        true
    ),
    USER_SWITCHER(
        "user_switcher",
        R.string.status_bar_element_user_switcher,
        R.drawable.ic_user,
        false
    ),
    WIFI(
        "wifi",
        R.string.status_bar_element_wifi,
        R.drawable.ic_wifi,
        false
    ),
    MOBILE(
        "mobile",
        R.string.status_bar_element_mobile,
        R.drawable.ic_cellular,
        false
    ),
    BATTERY(
        "battery",
        R.string.status_bar_element_battery,
        R.drawable.ic_battery,
        false
    );

    companion object {
        fun fromKey(key: String): StatusBarElement? =
            entries.firstOrNull { it.key == key }

        fun defaults(left: Boolean): List<StatusBarElement> =
            entries.filter { it.defaultOnLeft == left }
    }
}
