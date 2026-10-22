package com.motolauncher.mod

import android.content.Context
import android.os.SystemClock
import android.os.UserManager
import androidx.annotation.StringRes
import com.highcapable.yukihookapi.hook.xposed.prefs.YukiHookPrefsBridge
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared preference contract between the settings UI and the hooked launcher.
 *
 * Both sides use YukiHookAPI's `prefs("moto_launcher_tweaks")` bridge, so the
 * launcher process reads exactly the values written by [MainActivity].
 */
object HookConfig {

    /** YukiHookAPI / XSharedPreferences file name (without the .xml suffix). */
    const val PREFS_NAME: String = "moto_launcher_tweaks"

    /** Appearance. */
    const val KEY_HIDE_LABELS: String = "hide_labels"
    const val KEY_ICON_SHAPE: String = "icon_shape"
    const val KEY_LAST_APPLIED_SHAPE: String = "last_applied_shape"
    const val KEY_FOLDER_PREVIEW_3X3: String = "folder_preview_3x3"
    const val KEY_WIDGET_FREE_RESIZE: String = "widget_free_resize"

    /** Layout. */
    const val KEY_GRID_ROWS: String = "grid_rows"
    const val KEY_GRID_COLUMNS: String = "grid_columns"
    const val KEY_DOCK_ICONS: String = "dock_icons"

    /** Cover-screen Hotseat (Dock). */
    const val KEY_COVER_DOCK_ENABLED: String = "cover_dock_enabled"
    const val KEY_COVER_DOCK_FLOATING_ISLAND: String = "cover_dock_floating_island"
    const val KEY_COVER_DOCK_FROST: String = "cover_dock_frost"
    const val KEY_COVER_DOCK_HEIGHT_PERCENT: String = "cover_dock_height_percent"
    const val KEY_COVER_DOCK_WIDTH: String = "cover_dock_width_percent"
    const val KEY_COVER_DOCK_WIDTH_PERCENT: String = KEY_COVER_DOCK_WIDTH
    const val KEY_COVER_DOCK_SIZE_PERCENT: String = "cover_dock_size_percent"
    const val DEFAULT_COVER_DOCK_ENABLED: Boolean = false
    const val DEFAULT_COVER_DOCK_FLOATING_ISLAND: Boolean = false
    const val DEFAULT_COVER_DOCK_FROST: Boolean = false
    const val DEFAULT_COVER_DOCK_HEIGHT_PERCENT: Int = 100
    const val DEFAULT_COVER_DOCK_WIDTH_PERCENT: Int = 100
    const val DEFAULT_COVER_DOCK_SIZE_PERCENT: Int = 100
    const val MIN_COVER_DOCK_PERCENT: Int = 50
    const val MAX_COVER_DOCK_PERCENT: Int = 150
    const val MIN_COVER_DOCK_HEIGHT_PERCENT: Int = 50
    const val MAX_COVER_DOCK_HEIGHT_PERCENT: Int = 200
    const val MIN_COVER_DOCK_WIDTH_PERCENT: Int = 50
    const val MAX_COVER_DOCK_WIDTH_PERCENT: Int = 100

    /** SystemUI status bar. */
    const val KEY_SB_SHOW_SECONDS: String = "sb_show_seconds"
    const val KEY_SB_HIDE_BATTERY_PERCENT: String = "sb_hide_battery_percent"
    const val KEY_SB_HIDE_VOLTE: String = "sb_hide_volte"
    const val KEY_SB_IOS_ICONS: String = "sb_ios_icons"
    const val KEY_SB_ENABLE_CUSTOM_PADDING: String = "sb_enable_custom_padding"

    /** Battery (SystemUI status bar + system_server charging control). */
    const val KEY_BATTERY_ICON_STYLE: String = "battery_icon_style"
    const val KEY_BATTERY_PERCENT_STYLE: String = "battery_percent_style"
    const val KEY_BATTERY_CHARGE_LIMIT: String = "battery_charge_limit"
    const val KEY_BATTERY_CHARGE_LIMIT_VALUE: String = "battery_charge_limit_value"
    const val KEY_BATTERY_DISABLE_OVERHEAT_SHUTDOWN: String = "battery_disable_overheat_shutdown"

    /** SystemUI control center. */
    const val KEY_CC_SWAP_SLIDERS: String = "cc_swap_sliders"
    const val KEY_CC_IOS_CONNECTIVITY: String = "cc_ios_connectivity"

    /** Status bar element order (comma-separated keys). */
    const val KEY_SB_LEFT_ORDER: String = "sb_left_order"
    const val KEY_SB_RIGHT_ORDER: String = "sb_right_order"
    const val DEFAULT_SB_LEFT_ORDER: String = "clock,ongoing,notifications"
    const val DEFAULT_SB_RIGHT_ORDER: String = "user_switcher,wifi,mobile,battery"

    /** Screenshot / screen recording. */
    const val KEY_DISABLE_FLAG_SECURE: String = "disable_flag_secure"
    const val DEFAULT_DISABLE_FLAG_SECURE: Boolean = false

    /** Camera shutter sound (system framework). */
    const val KEY_DISABLE_CAMERA_SOUND: String = "disable_camera_sound"
    const val DEFAULT_DISABLE_CAMERA_SOUND: Boolean = false

    /** CorePatch-style installer policy bypass (system framework). */
    const val KEY_COREPATCH_BYPASS_SIGNATURES: String = "corepatch_bypass_signatures"
    const val KEY_COREPATCH_ALLOW_DOWNGRADE: String = "corepatch_allow_downgrade"
    const val KEY_COREPATCH_BYPASS_DIGEST: String = "corepatch_bypass_digest"
    const val DEFAULT_COREPATCH_BYPASS_SIGNATURES: Boolean = false
    const val DEFAULT_COREPATCH_ALLOW_DOWNGRADE: Boolean = false
    const val DEFAULT_COREPATCH_BYPASS_DIGEST: Boolean = false

    /**
     * Safe mode: on devices other than the razr fold the framework flags can
     * crash system_server / cause a boot loop, so the framework hooks are only
     * installed on the razr fold while this is enabled.
     */
    /** Display language ("ja" / "en"). */
    const val KEY_APP_LANGUAGE: String = "app_language"
    const val DEFAULT_APP_LANGUAGE: String = "ja"
    const val LANGUAGE_JA: String = "ja"
    const val LANGUAGE_EN: String = "en"

    const val KEY_SAFE_MODE: String = "safe_mode"
    const val DEFAULT_SAFE_MODE: Boolean = true

    /** All hook errors are force-written to this file. */
    const val ERROR_LOG_PATH: String = "/sdcard/Download/Errorlog"

    /** True on the Motorola razr fold (the reference device of this module). */
    fun isRazrFoldDevice(): Boolean {
        val model = runCatching { android.os.Build.MODEL.orEmpty().lowercase() }.getOrDefault("")
        return model.contains("razr") && model.contains("fold")
    }

    /**
     * True once `sys.boot_completed` is `1`. During cold boot or right after a
     * soft reboot (`ctl.restart zygote` / `ksud soft-reboot`), this is false until
     * system_server finishes booting.
     */
    fun isBootCompleted(): Boolean = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getMethod("getBoolean", String::class.java, java.lang.Boolean.TYPE)
        method.invoke(null, "sys.boot_completed", false) as? Boolean ?: false
    } catch (_: Throwable) {
        false
    }

    /**
     * True when Credential Encrypted (CE) storage is unlocked for the user
     * associated with [context]. Before Direct Boot unlock, reading CE resources
     * or preferences may fail.
     */
    fun isUserUnlocked(context: Context?): Boolean = try {
        if (context == null) {
            isBootCompleted()
        } else {
            val um = context.getSystemService(UserManager::class.java)
            um?.isUserUnlocked ?: isBootCompleted()
        }
    } catch (_: Throwable) {
        false
    }

    private const val PREFS_CACHE_TTL_MS = 750L
    private val prefsLock = Any()
    private val boolCache = ConcurrentHashMap<String, Pair<Long, Boolean>>()
    private val intCache = ConcurrentHashMap<String, Pair<Long, Int>>()
    private val stringCache = ConcurrentHashMap<String, Pair<Long, String>>()

    fun safeGetBoolean(prefs: YukiHookPrefsBridge?, key: String, defaultValue: Boolean): Boolean {
        if (prefs == null) return defaultValue
        val now = try {
            SystemClock.uptimeMillis()
        } catch (_: Throwable) {
            0L
        }
        if (now > 0L) {
            val cached = boolCache[key]
            if (cached != null && now - cached.first in 0 until PREFS_CACHE_TTL_MS) {
                return cached.second
            }
        }
        return try {
            synchronized(prefsLock) {
                if (now > 0L) {
                    val cached = boolCache[key]
                    if (cached != null && now - cached.first in 0 until PREFS_CACHE_TTL_MS) {
                        return@synchronized cached.second
                    }
                }
                val value = prefs.getBoolean(key, defaultValue)
                if (now > 0L) {
                    boolCache[key] = now to value
                }
                value
            }
        } catch (_: Throwable) {
            defaultValue
        }
    }

    fun safeGetInt(prefs: YukiHookPrefsBridge?, key: String, defaultValue: Int): Int {
        if (prefs == null) return defaultValue
        val now = try {
            SystemClock.uptimeMillis()
        } catch (_: Throwable) {
            0L
        }
        if (now > 0L) {
            val cached = intCache[key]
            if (cached != null && now - cached.first in 0 until PREFS_CACHE_TTL_MS) {
                return cached.second
            }
        }
        return try {
            synchronized(prefsLock) {
                if (now > 0L) {
                    val cached = intCache[key]
                    if (cached != null && now - cached.first in 0 until PREFS_CACHE_TTL_MS) {
                        return@synchronized cached.second
                    }
                }
                val value = prefs.getInt(key, defaultValue)
                if (now > 0L) {
                    intCache[key] = now to value
                }
                value
            }
        } catch (_: Throwable) {
            defaultValue
        }
    }

    fun safeGetString(prefs: YukiHookPrefsBridge?, key: String, defaultValue: String): String {
        if (prefs == null) return defaultValue
        val now = try {
            SystemClock.uptimeMillis()
        } catch (_: Throwable) {
            0L
        }
        if (now > 0L) {
            val cached = stringCache[key]
            if (cached != null && now - cached.first in 0 until PREFS_CACHE_TTL_MS) {
                return cached.second
            }
        }
        return try {
            synchronized(prefsLock) {
                if (now > 0L) {
                    val cached = stringCache[key]
                    if (cached != null && now - cached.first in 0 until PREFS_CACHE_TTL_MS) {
                        return@synchronized cached.second
                    }
                }
                val value = prefs.getString(key, defaultValue)
                if (now > 0L) {
                    stringCache[key] = now to value
                }
                value
            }
        } catch (_: Throwable) {
            defaultValue
        }
    }

    const val DEFAULT_HIDE_LABELS: Boolean = false
    const val DEFAULT_ICON_SHAPE: String = "SYSTEM"
    const val DEFAULT_FOLDER_PREVIEW_3X3: Boolean = false
    const val DEFAULT_WIDGET_FREE_RESIZE: Boolean = true

    const val DEFAULT_GRID_ROWS: Int = 6
    const val DEFAULT_GRID_COLUMNS: Int = 6
    const val DEFAULT_DOCK_ICONS: Int = 6

    const val DEFAULT_SB_SHOW_SECONDS: Boolean = true
    const val DEFAULT_SB_HIDE_BATTERY_PERCENT: Boolean = false
    const val DEFAULT_SB_HIDE_VOLTE: Boolean = true
    const val DEFAULT_SB_IOS_ICONS: Boolean = false
    const val DEFAULT_SB_ENABLE_CUSTOM_PADDING: Boolean = true

    const val DEFAULT_CC_SWAP_SLIDERS: Boolean = true
    const val DEFAULT_CC_IOS_CONNECTIVITY: Boolean = true

    const val DEFAULT_BATTERY_ICON_STYLE: String = "SYSTEM"
    const val DEFAULT_BATTERY_PERCENT_STYLE: String = "SYSTEM"
    const val DEFAULT_BATTERY_CHARGE_LIMIT: Boolean = false
    const val DEFAULT_BATTERY_CHARGE_LIMIT_VALUE: Int = 80
    const val DEFAULT_BATTERY_DISABLE_OVERHEAT_SHUTDOWN: Boolean = false

    /** Charge-limit slider range (%). */
    const val MIN_BATTERY_CHARGE_LIMIT: Int = 60
    const val MAX_BATTERY_CHARGE_LIMIT: Int = 100

    const val SYSTEM_UI_PACKAGE: String = "com.android.systemui"

    /** System framework ("android" / system_server) scope. */
    const val FRAMEWORK_PACKAGE: String = "android"

    /** Inclusive range used by the settings screen and by the hooks. */
    const val MIN_GRID_VALUE: Int = 4
    const val MAX_GRID_VALUE: Int = 10

    const val TARGET_PACKAGE: String = "com.motorola.launcher3"

    /**
     * Icon mask shapes. Keys are stored verbatim in [KEY_ICON_SHAPE]; labels
     * live in strings.xml ([labelRes]) and are never hardcoded here.
     */
    enum class IconShape(val key: String, @StringRes val labelRes: Int) {
        SYSTEM("SYSTEM", R.string.icon_shape_system),
        SQUIRCLE("SQUIRCLE", R.string.icon_shape_squircle),
        CIRCLE("CIRCLE", R.string.icon_shape_circle),
        SQUARE("SQUARE", R.string.icon_shape_square),
        DIAMOND("DIAMOND", R.string.icon_shape_diamond);

        companion object {
            fun fromKey(value: String?): IconShape =
                entries.firstOrNull { it.key.equals(value, ignoreCase = true) } ?: SYSTEM
        }
    }

    /**
     * Battery icon styles drawn by [com.motolauncher.mod.hooks.BatteryHook].
     * SYSTEM leaves Motorola's icon untouched.
     */
    enum class BatteryIconStyle(val key: String, @StringRes val labelRes: Int) {
        SYSTEM("SYSTEM", R.string.battery_icon_style_system),
        CIRCLE("CIRCLE", R.string.battery_icon_style_circle),
        PILL("PILL", R.string.battery_icon_style_pill),
        HIDDEN("HIDDEN", R.string.battery_icon_style_hidden);

        companion object {
            fun fromKey(value: String?): BatteryIconStyle =
                entries.firstOrNull { it.key.equals(value, ignoreCase = true) } ?: SYSTEM
        }
    }

    /** Percentage display modes: system default, always, hidden or inside the icon. */
    enum class BatteryPercentStyle(val key: String, @StringRes val labelRes: Int) {
        SYSTEM("SYSTEM", R.string.battery_percent_style_system),
        ALWAYS("ALWAYS", R.string.battery_percent_style_always),
        HIDDEN("HIDDEN", R.string.battery_percent_style_hidden),
        IN_ICON("IN_ICON", R.string.battery_percent_style_in_icon);

        companion object {
            fun fromKey(value: String?): BatteryPercentStyle =
                entries.firstOrNull { it.key.equals(value, ignoreCase = true) } ?: SYSTEM
        }
    }

    fun coerceGridValue(value: Int, defaultValue: Int): Int =
        if (value in MIN_GRID_VALUE..MAX_GRID_VALUE) value else defaultValue

    fun coerceChargeLimit(value: Int): Int =
        value.coerceIn(MIN_BATTERY_CHARGE_LIMIT, MAX_BATTERY_CHARGE_LIMIT)

    fun coerceCoverDockPercent(value: Int, defaultValue: Int = 100): Int =
        if (value in MIN_COVER_DOCK_PERCENT..MAX_COVER_DOCK_PERCENT) value else defaultValue

    fun coerceCoverDockHeightPercent(
        value: Int,
        defaultValue: Int = DEFAULT_COVER_DOCK_HEIGHT_PERCENT
    ): Int = if (value in MIN_COVER_DOCK_HEIGHT_PERCENT..MAX_COVER_DOCK_HEIGHT_PERCENT) {
        value
    } else {
        defaultValue
    }

    fun coerceCoverDockWidthPercent(
        value: Int,
        defaultValue: Int = DEFAULT_COVER_DOCK_WIDTH_PERCENT
    ): Int = if (value in MIN_COVER_DOCK_WIDTH_PERCENT..MAX_COVER_DOCK_WIDTH_PERCENT) {
        value
    } else {
        defaultValue
    }

    /** Cover-dock settings are read through YukiHookAPI's XSharedPreferences bridge. */
    fun isCoverDockEnabled(prefs: YukiHookPrefsBridge?): Boolean =
        safeGetBoolean(prefs, KEY_COVER_DOCK_ENABLED, DEFAULT_COVER_DOCK_ENABLED)

    fun isCoverDockFloatingIslandEnabled(prefs: YukiHookPrefsBridge?): Boolean =
        safeGetBoolean(prefs, KEY_COVER_DOCK_FLOATING_ISLAND, DEFAULT_COVER_DOCK_FLOATING_ISLAND)

    fun isCoverDockFrostEnabled(prefs: YukiHookPrefsBridge?): Boolean =
        safeGetBoolean(prefs, KEY_COVER_DOCK_FROST, DEFAULT_COVER_DOCK_FROST)

    fun coverDockHeightPercent(prefs: YukiHookPrefsBridge?): Int =
        coerceCoverDockHeightPercent(
            safeGetInt(prefs, KEY_COVER_DOCK_HEIGHT_PERCENT, DEFAULT_COVER_DOCK_HEIGHT_PERCENT),
            DEFAULT_COVER_DOCK_HEIGHT_PERCENT
        )

    fun coverDockWidthPercent(prefs: YukiHookPrefsBridge?): Int =
        coerceCoverDockWidthPercent(
            safeGetInt(prefs, KEY_COVER_DOCK_WIDTH, DEFAULT_COVER_DOCK_WIDTH_PERCENT),
            DEFAULT_COVER_DOCK_WIDTH_PERCENT
        )

    fun coverDockSizePercent(prefs: YukiHookPrefsBridge?): Int =
        coerceCoverDockPercent(
            safeGetInt(prefs, KEY_COVER_DOCK_SIZE_PERCENT, DEFAULT_COVER_DOCK_SIZE_PERCENT),
            DEFAULT_COVER_DOCK_SIZE_PERCENT
        )

    /**
     * True when the camera shutter sound has to be suppressed.
     *
     * The flag lives in the module prefs; the framework hook reads it through
     * the YukiHookAPI prefs bridge, because in the system_server process the
     * module prefs are exposed as XSharedPreferences.
     */
    fun isCameraSoundDisabled(prefs: YukiHookPrefsBridge?): Boolean =
        safeGetBoolean(prefs, KEY_DISABLE_CAMERA_SOUND, DEFAULT_DISABLE_CAMERA_SOUND)
}
