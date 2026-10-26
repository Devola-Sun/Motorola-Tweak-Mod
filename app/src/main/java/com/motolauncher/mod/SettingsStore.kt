package com.motolauncher.mod

import android.content.Context
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringWriter

/**
 * Reads and writes the module's SharedPreferences XML directly.
 *
 * Once LSPosed enables this module, the module app process is also treated as
 * an Xposed environment. In that state Android's SharedPreferences instance can
 * be backed by LSPosed's read-only XSharedPreferences cache, so writes may never
 * reach the file that the launcher process reads. Direct file I/O avoids that
 * layer while keeping the exact XML format expected by XSharedPreferences.
 */
class SettingsStore(context: Context) {

    var onChanged: (() -> Unit)? = null

    private val appContext: Context = context.applicationContext ?: context

    /** The app's private data directory; holds the shared_prefs XML. */
    private val dataDir = File(appContext.applicationInfo.dataDir)

    private val prefsFile = File(
        appContext.applicationInfo.dataDir,
        "shared_prefs/${HookConfig.PREFS_NAME}.xml"
    )

    private val values: MutableMap<String, Any> = load()

    init {
        // Always materialize the XML file so the LSPosed sync command has a
        // source file, even if the user has not changed a setting yet.
        if (!prefsFile.exists()) write()

        // Migrate the removed "hide battery percentage" switch to the new
        // percentage-style setting so existing users keep their preference.
        if ((values[HookConfig.KEY_SB_HIDE_BATTERY_PERCENT] as? Boolean) == true) {
            values[HookConfig.KEY_BATTERY_PERCENT_STYLE] =
                HookConfig.BatteryPercentStyle.HIDDEN.key
            values[HookConfig.KEY_SB_HIDE_BATTERY_PERCENT] = false
            write()
        }
    }

    var hideLabels: Boolean
        get() = values[HookConfig.KEY_HIDE_LABELS] as? Boolean
            ?: HookConfig.DEFAULT_HIDE_LABELS
        set(value) = update(HookConfig.KEY_HIDE_LABELS, value)

    var iconShape: String
        get() = values[HookConfig.KEY_ICON_SHAPE] as? String
            ?: HookConfig.DEFAULT_ICON_SHAPE
        set(value) = update(HookConfig.KEY_ICON_SHAPE, value)

    var folderPreview3x3: Boolean
        get() = values[HookConfig.KEY_FOLDER_PREVIEW_3X3] as? Boolean
            ?: HookConfig.DEFAULT_FOLDER_PREVIEW_3X3
        set(value) = update(HookConfig.KEY_FOLDER_PREVIEW_3X3, value)

    var widgetFreeResize: Boolean
        get() = values[HookConfig.KEY_WIDGET_FREE_RESIZE] as? Boolean
            ?: HookConfig.DEFAULT_WIDGET_FREE_RESIZE
        set(value) = update(HookConfig.KEY_WIDGET_FREE_RESIZE, value)

    var motoWidgetClockLiquidGlass: Boolean
        get() = values[HookConfig.KEY_MOTO_WIDGET_CLOCK_LIQUID_GLASS] as? Boolean
            ?: HookConfig.DEFAULT_MOTO_WIDGET_CLOCK_LIQUID_GLASS
        set(value) = update(HookConfig.KEY_MOTO_WIDGET_CLOCK_LIQUID_GLASS, value)

    /** The shape that was active when the icon cache was last cleared. */
    var lastAppliedShape: String
        get() = values[HookConfig.KEY_LAST_APPLIED_SHAPE] as? String
            ?: HookConfig.DEFAULT_ICON_SHAPE
        set(value) = update(HookConfig.KEY_LAST_APPLIED_SHAPE, value)

    var gridRows: Int
        get() = gridValue(HookConfig.KEY_GRID_ROWS, HookConfig.DEFAULT_GRID_ROWS)
        set(value) = update(HookConfig.KEY_GRID_ROWS, value)

    var gridColumns: Int
        get() = gridValue(HookConfig.KEY_GRID_COLUMNS, HookConfig.DEFAULT_GRID_COLUMNS)
        set(value) = update(HookConfig.KEY_GRID_COLUMNS, value)

    var dockIcons: Int
        get() = gridValue(HookConfig.KEY_DOCK_ICONS, HookConfig.DEFAULT_DOCK_ICONS)
        set(value) = update(HookConfig.KEY_DOCK_ICONS, value)

    var squareGridCells: Boolean
        get() = values[HookConfig.KEY_SQUARE_GRID_CELLS] as? Boolean
            ?: HookConfig.DEFAULT_SQUARE_GRID_CELLS
        set(value) = update(HookConfig.KEY_SQUARE_GRID_CELLS, value)

    var coverDockEnabled: Boolean
        get() = values[HookConfig.KEY_COVER_DOCK_ENABLED] as? Boolean
            ?: HookConfig.DEFAULT_COVER_DOCK_ENABLED
        set(value) = update(HookConfig.KEY_COVER_DOCK_ENABLED, value)

    var coverDockFloatingIsland: Boolean
        get() = values[HookConfig.KEY_COVER_DOCK_FLOATING_ISLAND] as? Boolean
            ?: HookConfig.DEFAULT_COVER_DOCK_FLOATING_ISLAND
        set(value) = update(HookConfig.KEY_COVER_DOCK_FLOATING_ISLAND, value)

    var coverDockFrost: Boolean
        get() = (values[HookConfig.KEY_COVER_DOCK_FROST] as? Boolean
            ?: HookConfig.DEFAULT_COVER_DOCK_FROST) && !coverDockLiquidGlass
        set(value) = updateExclusive(HookConfig.KEY_COVER_DOCK_FROST, HookConfig.KEY_COVER_DOCK_LIQUID_GLASS, value)

    var coverDockLiquidGlass: Boolean
        get() = values[HookConfig.KEY_COVER_DOCK_LIQUID_GLASS] as? Boolean ?: false
        set(value) = updateExclusive(HookConfig.KEY_COVER_DOCK_LIQUID_GLASS, HookConfig.KEY_COVER_DOCK_FROST, value)

    var mainDockFrost: Boolean
        get() = (values[HookConfig.KEY_MAIN_DOCK_FROST] as? Boolean ?: false) && !mainDockLiquidGlass
        set(value) = updateExclusive(HookConfig.KEY_MAIN_DOCK_FROST, HookConfig.KEY_MAIN_DOCK_LIQUID_GLASS, value)

    var mainDockLiquidGlass: Boolean
        get() = values[HookConfig.KEY_MAIN_DOCK_LIQUID_GLASS] as? Boolean ?: false
        set(value) = updateExclusive(HookConfig.KEY_MAIN_DOCK_LIQUID_GLASS, HookConfig.KEY_MAIN_DOCK_FROST, value)

    var folderLiquidGlass: Boolean
        get() = values[HookConfig.KEY_FOLDER_LIQUID_GLASS] as? Boolean ?: false
        set(value) = update(HookConfig.KEY_FOLDER_LIQUID_GLASS, value)

    var coverDockHeightPercent: Int
        get() = HookConfig.coerceCoverDockHeightPercent(
            values[HookConfig.KEY_COVER_DOCK_HEIGHT_PERCENT] as? Int
                ?: HookConfig.DEFAULT_COVER_DOCK_HEIGHT_PERCENT,
            HookConfig.DEFAULT_COVER_DOCK_HEIGHT_PERCENT
        )
        set(value) = update(
            HookConfig.KEY_COVER_DOCK_HEIGHT_PERCENT,
            HookConfig.coerceCoverDockHeightPercent(value, HookConfig.DEFAULT_COVER_DOCK_HEIGHT_PERCENT)
        )

    var coverDockWidthPercent: Int
        get() = HookConfig.coerceCoverDockWidthPercent(
            values[HookConfig.KEY_COVER_DOCK_WIDTH] as? Int
                ?: HookConfig.DEFAULT_COVER_DOCK_WIDTH_PERCENT,
            HookConfig.DEFAULT_COVER_DOCK_WIDTH_PERCENT
        )
        set(value) = update(
            HookConfig.KEY_COVER_DOCK_WIDTH,
            HookConfig.coerceCoverDockWidthPercent(value, HookConfig.DEFAULT_COVER_DOCK_WIDTH_PERCENT)
        )

    var coverDockSizePercent: Int
        get() = HookConfig.coerceCoverDockPercent(
            values[HookConfig.KEY_COVER_DOCK_SIZE_PERCENT] as? Int
                ?: HookConfig.DEFAULT_COVER_DOCK_SIZE_PERCENT,
            HookConfig.DEFAULT_COVER_DOCK_SIZE_PERCENT
        )
        set(value) = update(
            HookConfig.KEY_COVER_DOCK_SIZE_PERCENT,
            HookConfig.coerceCoverDockPercent(value, HookConfig.DEFAULT_COVER_DOCK_SIZE_PERCENT)
        )

    var sbShowSeconds: Boolean
        get() = values[HookConfig.KEY_SB_SHOW_SECONDS] as? Boolean
            ?: HookConfig.DEFAULT_SB_SHOW_SECONDS
        set(value) = update(HookConfig.KEY_SB_SHOW_SECONDS, value)

    var sbHideBatteryPercent: Boolean
        get() = values[HookConfig.KEY_SB_HIDE_BATTERY_PERCENT] as? Boolean
            ?: HookConfig.DEFAULT_SB_HIDE_BATTERY_PERCENT
        set(value) = update(HookConfig.KEY_SB_HIDE_BATTERY_PERCENT, value)

    var sbHideVolte: Boolean
        get() = values[HookConfig.KEY_SB_HIDE_VOLTE] as? Boolean
            ?: HookConfig.DEFAULT_SB_HIDE_VOLTE
        set(value) = update(HookConfig.KEY_SB_HIDE_VOLTE, value)

    var sbIosIcons: Boolean
        get() = values[HookConfig.KEY_SB_IOS_ICONS] as? Boolean
            ?: HookConfig.DEFAULT_SB_IOS_ICONS
        set(value) = update(HookConfig.KEY_SB_IOS_ICONS, value)

    var sbEnableCustomPadding: Boolean
        get() = values[HookConfig.KEY_SB_ENABLE_CUSTOM_PADDING] as? Boolean
            ?: HookConfig.DEFAULT_SB_ENABLE_CUSTOM_PADDING
        set(value) = update(HookConfig.KEY_SB_ENABLE_CUSTOM_PADDING, value)

    var ccSwapSliders: Boolean
        get() = values[HookConfig.KEY_CC_SWAP_SLIDERS] as? Boolean
            ?: HookConfig.DEFAULT_CC_SWAP_SLIDERS
        set(value) = update(HookConfig.KEY_CC_SWAP_SLIDERS, value)

    var ccCompactLayout: Boolean
        get() = values[HookConfig.KEY_CC_COMPACT_LAYOUT] as? Boolean
            ?: HookConfig.DEFAULT_CC_COMPACT_LAYOUT
        set(value) = update(HookConfig.KEY_CC_COMPACT_LAYOUT, value)

    var ccLiquidGlass: Boolean
        get() = values[HookConfig.KEY_CC_LIQUID_GLASS] as? Boolean
            ?: HookConfig.DEFAULT_CC_LIQUID_GLASS
        set(value) = update(HookConfig.KEY_CC_LIQUID_GLASS, value)

    var ccBackgroundBlurPercent: Int
        get() = HookConfig.coerceCcBackgroundBlur(values[HookConfig.KEY_CC_BACKGROUND_BLUR_PERCENT] as? Int
            ?: HookConfig.DEFAULT_CC_BACKGROUND_BLUR_PERCENT)
        set(value) = update(HookConfig.KEY_CC_BACKGROUND_BLUR_PERCENT, HookConfig.coerceCcBackgroundBlur(value))

    var notificationMediaFrost: Boolean
        get() = values[HookConfig.KEY_NOTIFICATION_MEDIA_FROST] as? Boolean
            ?: HookConfig.DEFAULT_NOTIFICATION_MEDIA_FROST
        set(value) = update(HookConfig.KEY_NOTIFICATION_MEDIA_FROST, value)

    var notificationKeepSilentVisible: Boolean
        get() = values[HookConfig.KEY_NOTIFICATION_KEEP_SILENT_VISIBLE] as? Boolean
            ?: HookConfig.DEFAULT_NOTIFICATION_KEEP_SILENT_VISIBLE
        set(value) = update(HookConfig.KEY_NOTIFICATION_KEEP_SILENT_VISIBLE, value)

    var ccIosConnectivity: Boolean
        get() = values[HookConfig.KEY_CC_IOS_CONNECTIVITY] as? Boolean
            ?: HookConfig.DEFAULT_CC_IOS_CONNECTIVITY
        set(value) = update(HookConfig.KEY_CC_IOS_CONNECTIVITY, value)

    var lockKeepLargeClock: Boolean
        get() = values[HookConfig.KEY_LOCK_KEEP_LARGE_CLOCK] as? Boolean
            ?: HookConfig.DEFAULT_LOCK_KEEP_LARGE_CLOCK
        set(value) = update(HookConfig.KEY_LOCK_KEEP_LARGE_CLOCK, value)

    var lockClockSizePercent: Int
        get() = HookConfig.coerceLockClockSize(
            values[HookConfig.KEY_LOCK_CLOCK_SIZE_PERCENT] as? Int
                ?: HookConfig.DEFAULT_LOCK_CLOCK_SIZE_PERCENT
        )
        set(value) = update(HookConfig.KEY_LOCK_CLOCK_SIZE_PERCENT, HookConfig.coerceLockClockSize(value))

    var lockClockLiquidGlass: Boolean
        get() = values[HookConfig.KEY_LOCK_CLOCK_LIQUID_GLASS] as? Boolean
            ?: HookConfig.DEFAULT_LOCK_CLOCK_LIQUID_GLASS
        set(value) = update(HookConfig.KEY_LOCK_CLOCK_LIQUID_GLASS, value)

    var lockShortcutBlur: Boolean
        get() = values[HookConfig.KEY_LOCK_SHORTCUT_BLUR] as? Boolean
            ?: HookConfig.DEFAULT_LOCK_SHORTCUT_BLUR
        set(value) = update(HookConfig.KEY_LOCK_SHORTCUT_BLUR, value)

    var lockEdgeCamera: Boolean
        get() = values[HookConfig.KEY_LOCK_EDGE_CAMERA] as? Boolean
            ?: HookConfig.DEFAULT_LOCK_EDGE_CAMERA
        set(value) = update(HookConfig.KEY_LOCK_EDGE_CAMERA, value)

    var lockMediaCompact: Boolean
        get() = values[HookConfig.KEY_LOCK_MEDIA_COMPACT] as? Boolean
            ?: HookConfig.DEFAULT_LOCK_MEDIA_COMPACT
        set(value) = update(HookConfig.KEY_LOCK_MEDIA_COMPACT, value)

    var lockPinAutoConfirm: Boolean
        get() = values[HookConfig.KEY_LOCK_PIN_AUTO_CONFIRM] as? Boolean
            ?: HookConfig.DEFAULT_LOCK_PIN_AUTO_CONFIRM
        set(value) = update(HookConfig.KEY_LOCK_PIN_AUTO_CONFIRM, value)

    var lockPinLength: Int
        get() = HookConfig.coerceLockPinLength(
            values[HookConfig.KEY_LOCK_PIN_LENGTH] as? Int ?: HookConfig.DEFAULT_LOCK_PIN_LENGTH
        )
        set(value) = update(HookConfig.KEY_LOCK_PIN_LENGTH, HookConfig.coerceLockPinLength(value))

    var lockShortcutSizePercent: Int
        get() = HookConfig.coerceLockShortcutSize(
            values[HookConfig.KEY_LOCK_SHORTCUT_SIZE_PERCENT] as? Int
                ?: HookConfig.DEFAULT_LOCK_SHORTCUT_SIZE_PERCENT
        )
        set(value) = update(HookConfig.KEY_LOCK_SHORTCUT_SIZE_PERCENT, HookConfig.coerceLockShortcutSize(value))

    var batteryIconStyle: String
        get() = values[HookConfig.KEY_BATTERY_ICON_STYLE] as? String
            ?: HookConfig.DEFAULT_BATTERY_ICON_STYLE
        set(value) = update(HookConfig.KEY_BATTERY_ICON_STYLE, value)

    var batteryPercentStyle: String
        get() = values[HookConfig.KEY_BATTERY_PERCENT_STYLE] as? String
            ?: HookConfig.DEFAULT_BATTERY_PERCENT_STYLE
        set(value) = update(HookConfig.KEY_BATTERY_PERCENT_STYLE, value)

    var batteryChargeLimit: Boolean
        get() = values[HookConfig.KEY_BATTERY_CHARGE_LIMIT] as? Boolean
            ?: HookConfig.DEFAULT_BATTERY_CHARGE_LIMIT
        set(value) = update(HookConfig.KEY_BATTERY_CHARGE_LIMIT, value)

    var batteryChargeLimitValue: Int
        get() = HookConfig.coerceChargeLimit(
            values[HookConfig.KEY_BATTERY_CHARGE_LIMIT_VALUE] as? Int
                ?: HookConfig.DEFAULT_BATTERY_CHARGE_LIMIT_VALUE
        )
        set(value) = update(
            HookConfig.KEY_BATTERY_CHARGE_LIMIT_VALUE,
            HookConfig.coerceChargeLimit(value)
        )

    var batteryDisableOverheatShutdown: Boolean
        get() = values[HookConfig.KEY_BATTERY_DISABLE_OVERHEAT_SHUTDOWN] as? Boolean
            ?: HookConfig.DEFAULT_BATTERY_DISABLE_OVERHEAT_SHUTDOWN
        set(value) = update(HookConfig.KEY_BATTERY_DISABLE_OVERHEAT_SHUTDOWN, value)

    var statusBarLeftOrder: String
        get() = values[HookConfig.KEY_SB_LEFT_ORDER] as? String
            ?: HookConfig.DEFAULT_SB_LEFT_ORDER
        set(value) = update(HookConfig.KEY_SB_LEFT_ORDER, value)

    var statusBarRightOrder: String
        get() = values[HookConfig.KEY_SB_RIGHT_ORDER] as? String
            ?: HookConfig.DEFAULT_SB_RIGHT_ORDER
        set(value) = update(HookConfig.KEY_SB_RIGHT_ORDER, value)

    var disableFlagSecure: Boolean
        get() = values[HookConfig.KEY_DISABLE_FLAG_SECURE] as? Boolean
            ?: HookConfig.DEFAULT_DISABLE_FLAG_SECURE
        set(value) = update(HookConfig.KEY_DISABLE_FLAG_SECURE, value)

    var disableCameraSound: Boolean
        get() = values[HookConfig.KEY_DISABLE_CAMERA_SOUND] as? Boolean
            ?: HookConfig.DEFAULT_DISABLE_CAMERA_SOUND
        set(value) = update(HookConfig.KEY_DISABLE_CAMERA_SOUND, value)

    var corepatchBypassSignatures: Boolean
        get() = values[HookConfig.KEY_COREPATCH_BYPASS_SIGNATURES] as? Boolean
            ?: HookConfig.DEFAULT_COREPATCH_BYPASS_SIGNATURES
        set(value) = update(HookConfig.KEY_COREPATCH_BYPASS_SIGNATURES, value)

    var corepatchAllowDowngrade: Boolean
        get() = values[HookConfig.KEY_COREPATCH_ALLOW_DOWNGRADE] as? Boolean
            ?: HookConfig.DEFAULT_COREPATCH_ALLOW_DOWNGRADE
        set(value) = update(HookConfig.KEY_COREPATCH_ALLOW_DOWNGRADE, value)

    var corepatchBypassDigest: Boolean
        get() = values[HookConfig.KEY_COREPATCH_BYPASS_DIGEST] as? Boolean
            ?: HookConfig.DEFAULT_COREPATCH_BYPASS_DIGEST
        set(value) = update(HookConfig.KEY_COREPATCH_BYPASS_DIGEST, value)

    var safeMode: Boolean
        get() = values[HookConfig.KEY_SAFE_MODE] as? Boolean
            ?: HookConfig.DEFAULT_SAFE_MODE
        set(value) = update(HookConfig.KEY_SAFE_MODE, value)

    var autoUpdateCheck: Boolean
        get() = values[HookConfig.KEY_AUTO_UPDATE_CHECK] as? Boolean
            ?: HookConfig.DEFAULT_AUTO_UPDATE_CHECK
        set(value) = update(HookConfig.KEY_AUTO_UPDATE_CHECK, value)

    var appLanguage: String
        get() = values[HookConfig.KEY_APP_LANGUAGE] as? String
            ?: HookConfig.DEFAULT_APP_LANGUAGE
        set(value) = update(HookConfig.KEY_APP_LANGUAGE, value)

    fun setGridValue(key: String, value: Int) {
        when (key) {
            HookConfig.KEY_GRID_ROWS -> gridRows = value
            HookConfig.KEY_GRID_COLUMNS -> gridColumns = value
            HookConfig.KEY_DOCK_ICONS -> dockIcons = value
        }
    }

    fun snapshot(): Map<String, Any> = values.toMap()

    private fun gridValue(key: String, defaultValue: Int): Int =
        HookConfig.coerceGridValue(values[key] as? Int ?: defaultValue, defaultValue)

    @Synchronized
    private fun update(key: String, value: Any) {
        if (values[key] == value) return
        values[key] = value
        write()
        onChanged?.invoke()
    }

    /** Commit both switches together so the launcher never reads two active styles. */
    @Synchronized
    private fun updateExclusive(key: String, otherKey: String, enabled: Boolean) {
        values[key] = enabled
        if (enabled) values[otherKey] = false
        write()
        onChanged?.invoke()
    }

    private fun load(): MutableMap<String, Any> {
        val result = mutableMapOf<String, Any>(
            HookConfig.KEY_HIDE_LABELS to HookConfig.DEFAULT_HIDE_LABELS,
            HookConfig.KEY_ICON_SHAPE to HookConfig.DEFAULT_ICON_SHAPE,
            HookConfig.KEY_LAST_APPLIED_SHAPE to HookConfig.DEFAULT_ICON_SHAPE,
            HookConfig.KEY_FOLDER_PREVIEW_3X3 to HookConfig.DEFAULT_FOLDER_PREVIEW_3X3,
            HookConfig.KEY_WIDGET_FREE_RESIZE to HookConfig.DEFAULT_WIDGET_FREE_RESIZE,
            HookConfig.KEY_GRID_ROWS to HookConfig.DEFAULT_GRID_ROWS,
            HookConfig.KEY_GRID_COLUMNS to HookConfig.DEFAULT_GRID_COLUMNS,
            HookConfig.KEY_SQUARE_GRID_CELLS to HookConfig.DEFAULT_SQUARE_GRID_CELLS,
            HookConfig.KEY_DOCK_ICONS to HookConfig.DEFAULT_DOCK_ICONS,
            HookConfig.KEY_COVER_DOCK_ENABLED to HookConfig.DEFAULT_COVER_DOCK_ENABLED,
            HookConfig.KEY_COVER_DOCK_FLOATING_ISLAND to HookConfig.DEFAULT_COVER_DOCK_FLOATING_ISLAND,
            HookConfig.KEY_COVER_DOCK_FROST to HookConfig.DEFAULT_COVER_DOCK_FROST,
            HookConfig.KEY_COVER_DOCK_LIQUID_GLASS to false,
            HookConfig.KEY_MAIN_DOCK_FROST to false,
            HookConfig.KEY_MAIN_DOCK_LIQUID_GLASS to false,
            HookConfig.KEY_FOLDER_LIQUID_GLASS to false,
            HookConfig.KEY_COVER_DOCK_HEIGHT_PERCENT to HookConfig.DEFAULT_COVER_DOCK_HEIGHT_PERCENT,
            HookConfig.KEY_COVER_DOCK_WIDTH to HookConfig.DEFAULT_COVER_DOCK_WIDTH_PERCENT,
            HookConfig.KEY_COVER_DOCK_SIZE_PERCENT to HookConfig.DEFAULT_COVER_DOCK_SIZE_PERCENT,
            HookConfig.KEY_SB_SHOW_SECONDS to HookConfig.DEFAULT_SB_SHOW_SECONDS,
            HookConfig.KEY_SB_HIDE_BATTERY_PERCENT to HookConfig.DEFAULT_SB_HIDE_BATTERY_PERCENT,
            HookConfig.KEY_SB_HIDE_VOLTE to HookConfig.DEFAULT_SB_HIDE_VOLTE,
            HookConfig.KEY_SB_IOS_ICONS to HookConfig.DEFAULT_SB_IOS_ICONS,
            HookConfig.KEY_SB_ENABLE_CUSTOM_PADDING to HookConfig.DEFAULT_SB_ENABLE_CUSTOM_PADDING,
            HookConfig.KEY_CC_SWAP_SLIDERS to HookConfig.DEFAULT_CC_SWAP_SLIDERS,
            HookConfig.KEY_CC_COMPACT_LAYOUT to HookConfig.DEFAULT_CC_COMPACT_LAYOUT,
            HookConfig.KEY_CC_LIQUID_GLASS to HookConfig.DEFAULT_CC_LIQUID_GLASS,
            HookConfig.KEY_CC_BACKGROUND_BLUR_PERCENT to HookConfig.DEFAULT_CC_BACKGROUND_BLUR_PERCENT,
            HookConfig.KEY_NOTIFICATION_MEDIA_FROST to HookConfig.DEFAULT_NOTIFICATION_MEDIA_FROST,
            HookConfig.KEY_NOTIFICATION_KEEP_SILENT_VISIBLE to HookConfig.DEFAULT_NOTIFICATION_KEEP_SILENT_VISIBLE,
            HookConfig.KEY_CC_IOS_CONNECTIVITY to HookConfig.DEFAULT_CC_IOS_CONNECTIVITY,
            HookConfig.KEY_LOCK_KEEP_LARGE_CLOCK to HookConfig.DEFAULT_LOCK_KEEP_LARGE_CLOCK,
            HookConfig.KEY_LOCK_CLOCK_SIZE_PERCENT to HookConfig.DEFAULT_LOCK_CLOCK_SIZE_PERCENT,
            HookConfig.KEY_LOCK_CLOCK_LIQUID_GLASS to HookConfig.DEFAULT_LOCK_CLOCK_LIQUID_GLASS,
            HookConfig.KEY_LOCK_PIN_AUTO_CONFIRM to HookConfig.DEFAULT_LOCK_PIN_AUTO_CONFIRM,
            HookConfig.KEY_LOCK_PIN_LENGTH to HookConfig.DEFAULT_LOCK_PIN_LENGTH,
            HookConfig.KEY_LOCK_MEDIA_COMPACT to HookConfig.DEFAULT_LOCK_MEDIA_COMPACT,
            HookConfig.KEY_LOCK_SHORTCUT_BLUR to HookConfig.DEFAULT_LOCK_SHORTCUT_BLUR,
            HookConfig.KEY_LOCK_EDGE_CAMERA to HookConfig.DEFAULT_LOCK_EDGE_CAMERA,
            HookConfig.KEY_LOCK_SHORTCUT_SIZE_PERCENT to HookConfig.DEFAULT_LOCK_SHORTCUT_SIZE_PERCENT,
            HookConfig.KEY_BATTERY_ICON_STYLE to HookConfig.DEFAULT_BATTERY_ICON_STYLE,
            HookConfig.KEY_BATTERY_PERCENT_STYLE to HookConfig.DEFAULT_BATTERY_PERCENT_STYLE,
            HookConfig.KEY_BATTERY_CHARGE_LIMIT to HookConfig.DEFAULT_BATTERY_CHARGE_LIMIT,
            HookConfig.KEY_BATTERY_CHARGE_LIMIT_VALUE to HookConfig.DEFAULT_BATTERY_CHARGE_LIMIT_VALUE,
            HookConfig.KEY_BATTERY_DISABLE_OVERHEAT_SHUTDOWN to HookConfig.DEFAULT_BATTERY_DISABLE_OVERHEAT_SHUTDOWN,
            HookConfig.KEY_SB_LEFT_ORDER to HookConfig.DEFAULT_SB_LEFT_ORDER,
            HookConfig.KEY_SB_RIGHT_ORDER to HookConfig.DEFAULT_SB_RIGHT_ORDER,
            HookConfig.KEY_DISABLE_FLAG_SECURE to HookConfig.DEFAULT_DISABLE_FLAG_SECURE,
            HookConfig.KEY_DISABLE_CAMERA_SOUND to HookConfig.DEFAULT_DISABLE_CAMERA_SOUND,
            HookConfig.KEY_COREPATCH_BYPASS_SIGNATURES to HookConfig.DEFAULT_COREPATCH_BYPASS_SIGNATURES,
            HookConfig.KEY_COREPATCH_ALLOW_DOWNGRADE to HookConfig.DEFAULT_COREPATCH_ALLOW_DOWNGRADE,
            HookConfig.KEY_COREPATCH_BYPASS_DIGEST to HookConfig.DEFAULT_COREPATCH_BYPASS_DIGEST,
            HookConfig.KEY_SAFE_MODE to HookConfig.DEFAULT_SAFE_MODE,
            HookConfig.KEY_APP_LANGUAGE to HookConfig.DEFAULT_APP_LANGUAGE,
            HookConfig.KEY_AUTO_UPDATE_CHECK to HookConfig.DEFAULT_AUTO_UPDATE_CHECK
        )
        if (!prefsFile.exists()) return result

        runCatching {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(prefsFile.inputStream(), null)

            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue

                val key = parser.getAttributeValue(null, "name") ?: continue
                when (parser.name) {
                    "boolean" -> parser.getAttributeValue(null, "value")
                        ?.toBooleanStrictOrNull()
                        ?.let { result[key] = it }

                    "int" -> parser.getAttributeValue(null, "value")
                        ?.toIntOrNull()
                        ?.let { result[key] = it }

                    "string" -> result[key] = parser.nextText()
                }
            }
        }

        return result
    }

    @Synchronized
    private fun write() {
        runCatching {
            prefsFile.parentFile?.mkdirs()

            val writer = StringWriter()
            val serializer = Xml.newSerializer()
            serializer.setOutput(writer)
            serializer.startDocument("utf-8", true)
            serializer.startTag(null, "map")

            for ((key, value) in values) {
                when (value) {
                    is Boolean -> serializer.startTag(null, "boolean")
                        .attribute(null, "name", key)
                        .attribute(null, "value", value.toString())
                        .endTag(null, "boolean")

                    is Int -> serializer.startTag(null, "int")
                        .attribute(null, "name", key)
                        .attribute(null, "value", value.toString())
                        .endTag(null, "int")

                    is String -> serializer.startTag(null, "string")
                        .attribute(null, "name", key)
                        .text(value)
                        .endTag(null, "string")
                }
            }

            serializer.endTag(null, "map")
            serializer.endDocument()
            serializer.flush()

            val temp = File(prefsFile.parentFile, "${prefsFile.name}.tmp")
            temp.writeText(writer.toString())
            if (!temp.renameTo(prefsFile)) {
                prefsFile.writeText(writer.toString())
                temp.delete()
            }

            // The XML file is the source of truth for the hooked processes,
            // but the plain SharedPreferences instance is kept in sync as
            // well: some readers (and the LSPosed prefs bridge) go through
            // it instead of the file.
            syncSharedPreferences()

            // LSPosed's daemon can read this regardless, but make the file
            // readable for a direct XSharedPreferences fallback as well.
            runCatching { prefsFile.setReadable(true, false) }
            runCatching { prefsFile.parentFile?.setExecutable(true, false) }
            runCatching { dataDir.setExecutable(true, false) }
        }
    }

    /**
     * Mirrors the current values into the module's SharedPreferences.
     *
     * The file this writes is the same file the direct XML writer touches,
     * so the values are identical either way; the extra write keeps the
     * in-memory SharedPreferences of this process consistent with the file.
     */
    @Suppress("DEPRECATION")
    private fun syncSharedPreferences() {
        runCatching {
            val prefs = appContext.getSharedPreferences(
                HookConfig.PREFS_NAME,
                Context.MODE_WORLD_READABLE
            )
            val editor = prefs.edit()
            for ((key, value) in values) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is String -> editor.putString(key, value)
                }
            }
            editor.commit()
        }.onFailure {
            Log.w("MotoLauncherMod", "SettingsStore: SharedPreferences sync failed", it)
        }
    }
}
