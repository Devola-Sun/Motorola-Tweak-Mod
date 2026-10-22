package com.motolauncher.mod

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.slider.Slider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings screen. [SettingsStore] writes the module's prefs XML directly; the
 * launcher process reads that file through YukiHookAPI's prefs() bridge.
 */
class MainActivity : AppCompatActivity() {

    private val TAG = "MotoLauncherMod"

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var settings: SettingsStore

    private lateinit var hideLabelsSwitch: SwitchCompat
    private lateinit var iconShapeSpinner: Spinner
    private lateinit var folderPreview3x3Switch: SwitchCompat
    private lateinit var gridRowsSeekBar: SeekBar
    private lateinit var gridColumnsSeekBar: SeekBar
    private lateinit var dockIconsSeekBar: SeekBar
    private lateinit var gridRowsValue: TextView
    private lateinit var gridColumnsValue: TextView
    private lateinit var dockIconsValue: TextView
    private lateinit var sbShowSeconds: SwitchCompat
    private lateinit var sbHideVolte: SwitchCompat
    private lateinit var sbIosIcons: SwitchCompat
    private lateinit var sbCustomPadding: SwitchCompat
    private lateinit var sbDisableFlagSecure: SwitchCompat
    private lateinit var disableCameraSoundSwitch: SwitchCompat
    private lateinit var ccSwapSliders: SwitchCompat
    private lateinit var ccIosConnectivity: SwitchCompat
    private lateinit var batteryIconStyleSpinner: Spinner
    private lateinit var batteryPercentStyleSpinner: Spinner
    private lateinit var batteryChargeLimitSwitch: SwitchCompat
    private lateinit var batteryChargeLimitSeekBar: SeekBar
    private lateinit var batteryChargeLimitValue: TextView
    private lateinit var batteryDisableOverheatSwitch: SwitchCompat
    private lateinit var corepatchBypassSignatures: SwitchCompat
    private lateinit var corepatchAllowDowngrade: SwitchCompat
    private lateinit var corepatchBypassDigest: SwitchCompat
    private lateinit var safeModeSwitch: SwitchCompat
    private lateinit var languageSpinner: Spinner
    private lateinit var coverDockSwitch: SwitchCompat
    private lateinit var coverDockFloatingIslandSwitch: SwitchCompat
    private lateinit var coverDockFrostSwitch: SwitchCompat
    private lateinit var coverDockHeightSlider: Slider
    private lateinit var coverDockWidthSlider: Slider
    private lateinit var coverDockSizeSlider: Slider
    private lateinit var coverDockHeightValue: TextView
    private lateinit var coverDockWidthValue: TextView
    private lateinit var coverDockSizeValue: TextView
    private lateinit var softRebootButton: Button
    private lateinit var restartMenuButton: View

    private lateinit var statusText: TextView
    private lateinit var restartButton: Button
    private lateinit var clearCacheButton: Button
    private lateinit var restartSystemUiButton: Button
    private lateinit var appearanceShortcutButton: Button
    private lateinit var systemUiShortcutButton: Button

    private lateinit var backupButton: Button
    private lateinit var restoreButton: Button
    private lateinit var backupSpinner: Spinner
    private var backups: List<File> = emptyList()
    private var selectedBackup: File? = null

    private lateinit var textPageTitle: TextView
    private lateinit var textPageSubtitle: TextView
    private lateinit var textOverviewGrid: TextView
    private lateinit var textOverviewDock: TextView
    private lateinit var textOverviewFolder: TextView

    private lateinit var pages: List<View>
    private lateinit var navItems: List<View>

    private lateinit var statusLeftRecycler: RecyclerView
    private lateinit var statusRightRecycler: RecyclerView
    private lateinit var statusLeftAdapter: StatusBarOrderAdapter
    private lateinit var statusRightAdapter: StatusBarOrderAdapter
    private lateinit var resetStatusOrderButton: Button

    private lateinit var backButton: View
    private lateinit var widgetFreeResizeSwitch: SwitchCompat
    private lateinit var launcherFeatureSummary: TextView
    private lateinit var systemUiFeatureSummary: TextView
    private lateinit var frameworkFeatureSummary: TextView
    private lateinit var openLauncherSettingsButton: Button
    private lateinit var openSystemUiSettingsButton: Button
    private lateinit var openFrameworkSettingsButton: Button
    private lateinit var homeScopeSummary: TextView
    private lateinit var featureScopeSummary: TextView
    private lateinit var aboutScopeBody: TextView
    private lateinit var launcherAppIcon: ImageView
    private lateinit var systemUiAppIcon: ImageView
    private lateinit var logRefreshButton: Button
    private lateinit var logClearButton: Button
    private lateinit var logContent: TextView
    private lateinit var logCount: TextView
    private var logsLoading = false

    private val backupRoot: File
        get() = getExternalFilesDir("launcher_backups") ?: File(filesDir, "launcher_backups")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // targetSdk 36 enforces edge-to-edge; keep the content clear of the
        // status and navigation bars.
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        settings = SettingsStore(this)
        Log.i(TAG, "Settings prefs: dir=${applicationInfo.dataDir}, all=${settings.snapshot()}")

        bindViews()
        bindNavigation()
        bindAppearance()
        bindLayout()
        bindCoverDockSettings()
        bindSystemUiSettings()
        bindBatterySettings()
        bindCorePatchSettings()
        bindCameraSettings()
        bindLanguage()
        bindStatusBarOrder()
        bindFeatures()
        bindLog()
        bindBackupRestore()
        bindActions()
        bindVersionLabels()
        updateOverview()
        loadAppIcons()
        refreshScopeList()
        if (settings.safeMode) forceErrorLogDump(showToast = false)
    }

    private fun bindViews() {
        textPageTitle = findViewById(R.id.text_page_title)
        textPageSubtitle = findViewById(R.id.text_page_subtitle)
        textOverviewGrid = findViewById(R.id.text_overview_grid)
        textOverviewDock = findViewById(R.id.text_overview_dock)
        textOverviewFolder = findViewById(R.id.text_overview_folder)
        pages = listOf(
            findViewById(R.id.page_home),
            findViewById(R.id.page_features),
            findViewById(R.id.page_log),
            findViewById(R.id.page_more),
            findViewById(R.id.page_appearance),
            findViewById(R.id.page_systemui),
            findViewById(R.id.page_framework)
        )
        navItems = listOf(
            findViewById(R.id.nav_home),
            findViewById(R.id.nav_features),
            findViewById(R.id.nav_log),
            findViewById(R.id.nav_more)
        )
        backButton = findViewById(R.id.button_back)
        widgetFreeResizeSwitch = findViewById(R.id.switch_widget_free_resize)
        launcherFeatureSummary = findViewById(R.id.text_launcher_feature_summary)
        systemUiFeatureSummary = findViewById(R.id.text_systemui_feature_summary)
        openLauncherSettingsButton = findViewById(R.id.button_open_launcher_settings)
        openSystemUiSettingsButton = findViewById(R.id.button_open_systemui_settings)
        openFrameworkSettingsButton = findViewById(R.id.button_open_framework_settings)
        frameworkFeatureSummary = findViewById(R.id.text_framework_feature_summary)
        homeScopeSummary = findViewById(R.id.text_home_scope_summary)
        featureScopeSummary = findViewById(R.id.text_feature_scope_summary)
        aboutScopeBody = findViewById(R.id.text_about_scope_body)
        launcherAppIcon = findViewById(R.id.icon_launcher_app)
        systemUiAppIcon = findViewById(R.id.icon_systemui_app)
        logRefreshButton = findViewById(R.id.button_log_refresh)
        logClearButton = findViewById(R.id.button_log_clear)
        logContent = findViewById(R.id.text_log_content)
        logCount = findViewById(R.id.text_log_count)

        hideLabelsSwitch = findViewById(R.id.switch_hide_labels)
        iconShapeSpinner = findViewById(R.id.spinner_icon_shape)
        folderPreview3x3Switch = findViewById(R.id.switch_folder_preview_3x3)
        gridRowsSeekBar = findViewById(R.id.seek_grid_rows)
        gridColumnsSeekBar = findViewById(R.id.seek_grid_columns)
        dockIconsSeekBar = findViewById(R.id.seek_dock_icons)
        gridRowsValue = findViewById(R.id.text_grid_rows_value)
        gridColumnsValue = findViewById(R.id.text_grid_columns_value)
        dockIconsValue = findViewById(R.id.text_dock_icons_value)
        sbShowSeconds = findViewById(R.id.switch_sb_show_seconds)
        sbHideVolte = findViewById(R.id.switch_sb_hide_volte)
        sbIosIcons = findViewById(R.id.switch_sb_ios_icons)
        sbCustomPadding = findViewById(R.id.switch_sb_custom_padding)
        sbDisableFlagSecure = findViewById(R.id.switch_disable_flag_secure)
        disableCameraSoundSwitch = findViewById(R.id.switch_disable_camera_sound)
        ccSwapSliders = findViewById(R.id.switch_cc_swap_sliders)
        ccIosConnectivity = findViewById(R.id.switch_cc_ios_connectivity)
        batteryIconStyleSpinner = findViewById(R.id.spinner_battery_icon_style)
        batteryPercentStyleSpinner = findViewById(R.id.spinner_battery_percent_style)
        batteryChargeLimitSwitch = findViewById(R.id.switch_battery_charge_limit)
        batteryChargeLimitSeekBar = findViewById(R.id.seek_battery_charge_limit)
        batteryChargeLimitValue = findViewById(R.id.text_battery_charge_limit_value)
        batteryDisableOverheatSwitch = findViewById(R.id.switch_battery_disable_overheat)
        corepatchBypassSignatures = findViewById(R.id.switch_corepatch_bypass_signatures)
        corepatchAllowDowngrade = findViewById(R.id.switch_corepatch_allow_downgrade)
        corepatchBypassDigest = findViewById(R.id.switch_corepatch_bypass_digest)
        safeModeSwitch = findViewById(R.id.switch_safe_mode)
        languageSpinner = findViewById(R.id.spinner_language)
        coverDockSwitch = findViewById(R.id.switch_enable_cover_dock)
        coverDockFloatingIslandSwitch = findViewById(R.id.switch_cover_dock_floating_island)
        coverDockFrostSwitch = findViewById(R.id.switch_cover_dock_frost)
        coverDockHeightSlider = findViewById(R.id.slider_cover_dock_height)
        coverDockWidthSlider = findViewById(R.id.slider_cover_dock_width)
        coverDockSizeSlider = findViewById(R.id.slider_cover_dock_size)
        coverDockHeightValue = findViewById(R.id.text_cover_dock_height_value)
        coverDockWidthValue = findViewById(R.id.text_cover_dock_width_value)
        coverDockSizeValue = findViewById(R.id.text_cover_dock_size_value)
        softRebootButton = findViewById(R.id.button_soft_reboot)
        restartMenuButton = findViewById(R.id.button_restart_menu)
        statusText = findViewById(R.id.text_status)
        restartButton = findViewById(R.id.button_restart)
        clearCacheButton = findViewById(R.id.button_clear_cache)
        restartSystemUiButton = findViewById(R.id.button_restart_systemui)
        appearanceShortcutButton = findViewById(R.id.button_appearance_shortcut)
        systemUiShortcutButton = findViewById(R.id.button_systemui_shortcut)
        backupButton = findViewById(R.id.button_backup_layout)
        restoreButton = findViewById(R.id.button_restore_layout)
        backupSpinner = findViewById(R.id.spinner_backup_list)
        statusLeftRecycler = findViewById(R.id.recycler_status_left)
        statusRightRecycler = findViewById(R.id.recycler_status_right)
        resetStatusOrderButton = findViewById(R.id.button_reset_status_order)
    }

    private fun bindNavigation() {
        val navPages = intArrayOf(PAGE_HOME, PAGE_FEATURES, PAGE_LOG, PAGE_MORE)
        navItems.forEachIndexed { index, item ->
            item.setOnClickListener { showPage(navPages[index]) }
        }
        backButton.setOnClickListener { showPage(PAGE_FEATURES) }
        restartMenuButton.setOnClickListener { showRestartMenu() }
        showPage(PAGE_HOME)
    }

    /**
     * Right-top app icon: anchored popup menu with all restart actions (root),
     * styled like the ReSukiSU reference (dark rounded popup under the icon).
     */
    private fun showRestartMenu() {
        val popup = androidx.appcompat.widget.PopupMenu(this, restartMenuButton)
        popup.menu.add(0, MENU_RESTART_LAUNCHER, 0, getString(R.string.restart_menu_launcher))
        popup.menu.add(0, MENU_RESTART_SYSTEMUI, 1, getString(R.string.restart_menu_systemui))
        popup.menu.add(0, MENU_SOFT_REBOOT, 2, getString(R.string.restart_menu_soft_reboot))
        popup.menu.add(0, MENU_REBOOT_DEVICE, 3, getString(R.string.restart_menu_device))
        popup.menu.add(0, MENU_CLEAR_CACHE, 4, getString(R.string.restart_menu_clear_cache))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_RESTART_LAUNCHER -> runRootAction(
                    getString(R.string.status_restarting),
                    RESTART_COMMAND
                )

                MENU_RESTART_SYSTEMUI -> runRootAction(
                    getString(R.string.status_restarting_systemui),
                    RESTART_SYSTEMUI_QUICK_COMMAND
                )

                MENU_SOFT_REBOOT -> runRootAction(
                    getString(R.string.status_soft_rebooting),
                    SOFT_REBOOT_COMMAND
                )

                MENU_REBOOT_DEVICE -> runRootAction(
                    getString(R.string.status_rebooting),
                    REBOOT_COMMAND
                )

                MENU_CLEAR_CACHE -> runRootAction(
                    getString(R.string.status_clearing_cache),
                    CLEAR_CACHE_QUICK_COMMAND
                )
            }
            true
        }
        popup.show()
    }

    private fun showPage(index: Int) {
        if (index !in pages.indices) return

        pages.forEachIndexed { pageIndex, page ->
            page.visibility = if (pageIndex == index) View.VISIBLE else View.GONE
        }

        val selectedNav = when (index) {
            PAGE_HOME -> 0
            PAGE_FEATURES, PAGE_LAUNCHER, PAGE_SYSTEMUI, PAGE_FRAMEWORK -> 1
            PAGE_LOG -> 2
            PAGE_MORE -> 3
            else -> 0
        }
        navItems.forEachIndexed { navIndex, item ->
            item.isSelected = navIndex == selectedNav
        }
        backButton.visibility =
            if (index == PAGE_LAUNCHER || index == PAGE_SYSTEMUI || index == PAGE_FRAMEWORK) {
                View.VISIBLE
            } else {
                View.GONE
            }

        val titles = intArrayOf(
            R.string.page_home_title,
            R.string.page_features_title,
            R.string.page_log_title,
            R.string.page_more_title,
            R.string.page_launcher_title,
            R.string.page_systemui_title,
            R.string.page_framework_title
        )
        val subtitles = intArrayOf(
            R.string.page_home_subtitle,
            R.string.page_features_subtitle,
            R.string.page_log_subtitle,
            R.string.page_more_subtitle,
            R.string.page_launcher_subtitle,
            R.string.page_systemui_subtitle,
            R.string.page_framework_subtitle
        )
        textPageTitle.setText(titles[index])
        textPageSubtitle.setText(subtitles[index])

        (pages[index]).scrollTo(0, 0)
        if (index == PAGE_LOG) {
            refreshLogs()
        }
    }

    private fun bindFeatures() {
        openLauncherSettingsButton.setOnClickListener { showPage(PAGE_LAUNCHER) }
        openSystemUiSettingsButton.setOnClickListener { showPage(PAGE_SYSTEMUI) }
        openFrameworkSettingsButton.setOnClickListener { showPage(PAGE_FRAMEWORK) }
    }

    /**
     * The scope list is not hard-coded: [ScopeDetector] reads LSPosed's config
     * database and the hook log markers, so only scopes that are really hooked
     * are shown. "設定を開く" is grayed out for apps whose hooks are not active.
     */
    private fun refreshScopeList() {
        ScopeDetector.detect(this) { result ->
            if (isFinishing || isDestroyed) return@detect

            if (!result.resolved) {
                // Nothing detectable yet (no root output): keep the declared
                // list and leave every settings screen reachable.
                homeScopeSummary.text = getString(R.string.home_scope_summary)
                featureScopeSummary.text = getString(R.string.feature_scope_summary)
                aboutScopeBody.text = getString(R.string.about_scope_body)
                setSettingsButtonState(openLauncherSettingsButton, true)
                setSettingsButtonState(openSystemUiSettingsButton, true)
                setSettingsButtonState(openFrameworkSettingsButton, true)
                return@detect
            }

            val hooked = result.hooked
            val list = if (hooked.isEmpty()) {
                getString(R.string.feature_scope_none)
            } else {
                hooked.joinToString("\n")
            }
            homeScopeSummary.text = list
            featureScopeSummary.text = list
            aboutScopeBody.text = if (hooked.isEmpty()) {
                getString(R.string.feature_scope_none)
            } else {
                getString(
                    R.string.about_scope_body_dynamic,
                    hooked.joinToString("\n") { "• $it" }
                )
            }

            setSettingsButtonState(openLauncherSettingsButton, HookConfig.TARGET_PACKAGE in hooked)
            setSettingsButtonState(openSystemUiSettingsButton, HookConfig.SYSTEM_UI_PACKAGE in hooked)
            setSettingsButtonState(openFrameworkSettingsButton, HookConfig.FRAMEWORK_PACKAGE in hooked)
        }
    }

    private fun setSettingsButtonState(button: Button, hooked: Boolean) {
        button.isEnabled = hooked
        button.alpha = if (hooked) 1f else 0.4f
    }

    /** Shows the real installed app icon; keeps the vector when unavailable. */
    private fun loadAppIcons() {
        loadAppIcon(launcherAppIcon, HookConfig.TARGET_PACKAGE)
        loadAppIcon(systemUiAppIcon, HookConfig.SYSTEM_UI_PACKAGE)
    }

    private fun loadAppIcon(view: ImageView, packageName: String) {
        val icon = runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull() ?: return
        runCatching {
            view.imageTintList = null
            view.setPadding(0, 0, 0, 0)
            view.setImageDrawable(icon)
        }
    }

    private fun bindLog() {
        logRefreshButton.setOnClickListener { refreshLogs() }
        logClearButton.setOnClickListener {
            logContent.setText(R.string.log_empty)
            logCount.text = ""
        }
    }

    private fun refreshLogs() {
        if (logsLoading || !::logContent.isInitialized) return
        logsLoading = true
        logContent.setText(R.string.log_loading)
        logCount.text = ""

        val command = "logcat -d -v time -t 800 -s " +
            "MotoLauncherMod MotoControlCenterHooks MotoSignalIconHook"
        RootShell.run(command) { success, output ->
            logsLoading = false
            if (isFinishing || isDestroyed) return@run

            if (!success) {
                logContent.text = getString(
                    R.string.log_root_error,
                    output.take(300).ifBlank { getString(R.string.status_root_unavailable) }
                )
                return@run
            }

            val lines = output.lineSequence()
                .filter { it.isNotBlank() }
                .toList()
                .takeLast(500)
            if (lines.isEmpty()) {
                logContent.setText(R.string.log_empty)
                logCount.text = ""
            } else {
                logContent.text = lines.joinToString("\n")
                logCount.text = getString(R.string.log_line_count, lines.size)
            }
        }
    }

    private fun bindVersionLabels() {
        // Bind every version label to BuildConfig so the UI always matches the
        // installed APK (no hard-coded version strings in the UI layer).
        val version = getString(R.string.app_version_format, BuildConfig.VERSION_NAME)
        findViewById<TextView>(R.id.text_app_version).text = version
        findViewById<TextView>(R.id.text_about_version).text =
            getString(R.string.app_name) + " " + version
        findViewById<TextView>(R.id.text_footer).text =
            getString(R.string.home_footer, version)
    }

    private fun updateOverview() {
        textOverviewGrid.text = getString(
            R.string.value_grid_format,
            settings.gridColumns,
            settings.gridRows
        )
        textOverviewDock.text = getString(R.string.value_dock_format, settings.dockIcons)
        textOverviewFolder.text = getString(
            if (settings.folderPreview3x3) R.string.value_on else R.string.value_off
        )
        updateFeatureSummaries()
    }

    private fun updateFeatureSummaries() {
        if (!::launcherFeatureSummary.isInitialized || !::systemUiFeatureSummary.isInitialized ||
            !::frameworkFeatureSummary.isInitialized
        ) {
            return
        }

        val onOff = { value: Boolean ->
            getString(if (value) R.string.value_on else R.string.value_off)
        }

        launcherFeatureSummary.text = getString(
            R.string.feature_launcher_summary,
            "${settings.gridColumns}×${settings.gridRows}",
            settings.dockIcons,
            onOff(settings.folderPreview3x3)
        )

        frameworkFeatureSummary.text = getString(
            R.string.feature_framework_summary,
            onOff(settings.disableFlagSecure),
            onOff(settings.disableCameraSound),
            onOff(settings.corepatchBypassSignatures),
            onOff(settings.corepatchAllowDowngrade)
        )

        val orderCustom = settings.statusBarLeftOrder != HookConfig.DEFAULT_SB_LEFT_ORDER ||
            settings.statusBarRightOrder != HookConfig.DEFAULT_SB_RIGHT_ORDER
        systemUiFeatureSummary.text = getString(
            R.string.feature_systemui_summary,
            onOff(settings.sbShowSeconds),
            onOff(settings.sbIosIcons),
            getString(
                if (orderCustom) R.string.feature_order_custom else R.string.feature_order_default
            )
        )
    }

    private fun bindAppearance() {
        hideLabelsSwitch.isChecked = settings.hideLabels
        hideLabelsSwitch.setOnCheckedChangeListener { _, checked ->
            settings.hideLabels = checked
            updateOverview()
        }

        val shapes = HookConfig.IconShape.entries
        val adapter = ArrayAdapter(
            this,
            R.layout.item_spinner,
            shapes.map { getString(it.labelRes) }
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        iconShapeSpinner.adapter = adapter

        folderPreview3x3Switch.isChecked = settings.folderPreview3x3
        folderPreview3x3Switch.setOnCheckedChangeListener { _, value ->
            settings.folderPreview3x3 = value
            updateOverview()
        }

        widgetFreeResizeSwitch.isChecked = settings.widgetFreeResize
        widgetFreeResizeSwitch.setOnCheckedChangeListener { _, value ->
            settings.widgetFreeResize = value
            updateFeatureSummaries()
        }

        val storedShape = settings.iconShape
        val currentShape = HookConfig.IconShape.fromKey(storedShape)
        val currentIndex = shapes.indexOf(currentShape).coerceAtLeast(0)
        Log.i(TAG, "Spinner: stored=$storedShape parsed=${currentShape.key} index=$currentIndex")
        iconShapeSpinner.setSelection(currentIndex, false)

        iconShapeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                settings.iconShape = shapes[position].key
                updateFeatureSummaries()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun bindLayout() {
        bindSeekBar(
            gridRowsSeekBar,
            gridRowsValue,
            HookConfig.KEY_GRID_ROWS,
            settings.gridRows
        )
        bindSeekBar(
            gridColumnsSeekBar,
            gridColumnsValue,
            HookConfig.KEY_GRID_COLUMNS,
            settings.gridColumns
        )
        bindSeekBar(
            dockIconsSeekBar,
            dockIconsValue,
            HookConfig.KEY_DOCK_ICONS,
            settings.dockIcons
        )
    }

    private fun bindCoverDockSettings() {
        coverDockSwitch.isChecked = settings.coverDockEnabled
        coverDockSwitch.setOnCheckedChangeListener { _, enabled ->
            settings.coverDockEnabled = enabled
            updateCoverDockControls()
            syncModulePrefs()
        }

        coverDockFloatingIslandSwitch.isChecked = settings.coverDockFloatingIsland
        coverDockFloatingIslandSwitch.setOnCheckedChangeListener { _, enabled ->
            settings.coverDockFloatingIsland = enabled
            syncModulePrefs()
        }
        coverDockFrostSwitch.isChecked = settings.coverDockFrost
        coverDockFrostSwitch.setOnCheckedChangeListener { _, enabled ->
            settings.coverDockFrost = enabled
            syncModulePrefs()
        }

        bindCoverDockSlider(
            slider = coverDockHeightSlider,
            valueView = coverDockHeightValue,
            initialValue = settings.coverDockHeightPercent,
            minPercent = HookConfig.MIN_COVER_DOCK_HEIGHT_PERCENT,
            maxPercent = HookConfig.MAX_COVER_DOCK_HEIGHT_PERCENT,
            defaultPercent = HookConfig.DEFAULT_COVER_DOCK_HEIGHT_PERCENT,
            onValueChanged = { settings.coverDockHeightPercent = it }
        )
        bindCoverDockSlider(
            slider = coverDockWidthSlider,
            valueView = coverDockWidthValue,
            initialValue = settings.coverDockWidthPercent,
            minPercent = HookConfig.MIN_COVER_DOCK_WIDTH_PERCENT,
            maxPercent = HookConfig.MAX_COVER_DOCK_WIDTH_PERCENT,
            defaultPercent = HookConfig.DEFAULT_COVER_DOCK_WIDTH_PERCENT,
            onValueChanged = { settings.coverDockWidthPercent = it }
        )
        bindCoverDockSlider(
            slider = coverDockSizeSlider,
            valueView = coverDockSizeValue,
            initialValue = settings.coverDockSizePercent,
            onValueChanged = { settings.coverDockSizePercent = it }
        )
        updateCoverDockControls()
    }

    private fun updateCoverDockControls() {
        val enabled = coverDockSwitch.isChecked
        listOf(coverDockFloatingIslandSwitch, coverDockFrostSwitch).forEach { view ->
            view.isEnabled = enabled
            view.alpha = if (enabled) 1f else 0.4f
        }
    }

    private fun bindCoverDockSlider(
        slider: Slider,
        valueView: TextView,
        initialValue: Int,
        minPercent: Int = HookConfig.MIN_COVER_DOCK_PERCENT,
        maxPercent: Int = HookConfig.MAX_COVER_DOCK_PERCENT,
        defaultPercent: Int = HookConfig.DEFAULT_COVER_DOCK_HEIGHT_PERCENT,
        onValueChanged: (Int) -> Unit
    ) {
        val coercedInitial = if (initialValue in minPercent..maxPercent) {
            initialValue
        } else {
            defaultPercent.coerceIn(minPercent, maxPercent)
        }
        slider.valueTo = maxPercent.toFloat()
        slider.value = minPercent.toFloat()
        slider.valueFrom = minPercent.toFloat()
        slider.stepSize = 1f
        slider.value = coercedInitial.toFloat()

        fun showValue(value: Int) {
            valueView.text = getString(R.string.cover_dock_percent_format, value)
        }
        showValue(slider.value.toInt())

        slider.addOnChangeListener { _, value, fromUser ->
            val percent = value.toInt().coerceIn(minPercent, maxPercent)
            showValue(percent)
            if (fromUser) onValueChanged(percent)
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                onValueChanged(slider.value.toInt().coerceIn(minPercent, maxPercent))
                syncModulePrefs()
            }
        })
    }

    private fun bindSeekBar(
        seekBar: SeekBar,
        valueView: TextView,
        key: String,
        initialValue: Int
    ) {
        seekBar.max = HookConfig.MAX_GRID_VALUE - HookConfig.MIN_GRID_VALUE
        seekBar.progress = initialValue - HookConfig.MIN_GRID_VALUE
        valueView.text = initialValue.toString()

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = HookConfig.MIN_GRID_VALUE + progress
                valueView.text = value.toString()
                if (fromUser) {
                    settings.setGridValue(key, value)
                    updateOverview()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (seekBar != null) {
                    settings.setGridValue(key, HookConfig.MIN_GRID_VALUE + seekBar.progress)
                }
            }
        })
    }

    private fun bindSystemUiSettings() {
        sbShowSeconds.isChecked = settings.sbShowSeconds
        sbShowSeconds.setOnCheckedChangeListener { _, value ->
            settings.sbShowSeconds = value
            updateFeatureSummaries()
        }

        sbHideVolte.isChecked = settings.sbHideVolte
        sbHideVolte.setOnCheckedChangeListener { _, value ->
            settings.sbHideVolte = value
            updateFeatureSummaries()
        }

        sbIosIcons.isChecked = settings.sbIosIcons
        sbIosIcons.setOnCheckedChangeListener { _, value ->
            settings.sbIosIcons = value
            updateFeatureSummaries()
        }

        sbCustomPadding.isChecked = settings.sbEnableCustomPadding
        sbCustomPadding.setOnCheckedChangeListener { _, value ->
            settings.sbEnableCustomPadding = value
            updateFeatureSummaries()
        }

        sbDisableFlagSecure.isChecked = settings.disableFlagSecure
        sbDisableFlagSecure.setOnCheckedChangeListener { _, value ->
            settings.disableFlagSecure = value
            syncModulePrefs()
            updateFeatureSummaries()
        }

        ccSwapSliders.isChecked = settings.ccSwapSliders
        ccSwapSliders.setOnCheckedChangeListener { _, value ->
            settings.ccSwapSliders = value
            updateFeatureSummaries()
        }

        ccIosConnectivity.isChecked = settings.ccIosConnectivity
        ccIosConnectivity.setOnCheckedChangeListener { _, value ->
            settings.ccIosConnectivity = value
            updateFeatureSummaries()
        }
    }

    private fun bindBatterySettings() {
        val iconStyles = HookConfig.BatteryIconStyle.entries
        batteryIconStyleSpinner.adapter = ArrayAdapter(
            this,
            R.layout.item_spinner,
            iconStyles.map { getString(it.labelRes) }
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        batteryIconStyleSpinner.setSelection(
            iconStyles.indexOf(
                HookConfig.BatteryIconStyle.fromKey(settings.batteryIconStyle)
            ).coerceAtLeast(0),
            false
        )
        batteryIconStyleSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    settings.batteryIconStyle = iconStyles[position].key
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

        val percentStyles = HookConfig.BatteryPercentStyle.entries
        batteryPercentStyleSpinner.adapter = ArrayAdapter(
            this,
            R.layout.item_spinner,
            percentStyles.map { getString(it.labelRes) }
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        batteryPercentStyleSpinner.setSelection(
            percentStyles.indexOf(
                HookConfig.BatteryPercentStyle.fromKey(settings.batteryPercentStyle)
            ).coerceAtLeast(0),
            false
        )
        batteryPercentStyleSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    settings.batteryPercentStyle = percentStyles[position].key
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

        batteryChargeLimitSwitch.isChecked = settings.batteryChargeLimit
        batteryChargeLimitSwitch.setOnCheckedChangeListener { _, value ->
            settings.batteryChargeLimit = value
            syncModulePrefs()
            updateChargeLimitControls()
        }

        batteryChargeLimitSeekBar.max =
            HookConfig.MAX_BATTERY_CHARGE_LIMIT - HookConfig.MIN_BATTERY_CHARGE_LIMIT
        batteryChargeLimitSeekBar.progress =
            settings.batteryChargeLimitValue - HookConfig.MIN_BATTERY_CHARGE_LIMIT
        updateChargeLimitLabel(settings.batteryChargeLimitValue)
        batteryChargeLimitSeekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    val value = HookConfig.MIN_BATTERY_CHARGE_LIMIT + progress
                    updateChargeLimitLabel(value)
                    if (fromUser) settings.batteryChargeLimitValue = value
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    if (seekBar != null) {
                        settings.batteryChargeLimitValue =
                            HookConfig.MIN_BATTERY_CHARGE_LIMIT + seekBar.progress
                        syncModulePrefs()
                    }
                }
            }
        )

        batteryDisableOverheatSwitch.isChecked = settings.batteryDisableOverheatShutdown
        batteryDisableOverheatSwitch.setOnCheckedChangeListener { _, value ->
            settings.batteryDisableOverheatShutdown = value
            syncModulePrefs()
        }

        updateChargeLimitControls()
    }

    private fun updateChargeLimitLabel(value: Int) {
        batteryChargeLimitValue.text =
            getString(R.string.battery_charge_limit_value_format, value)
    }

    private fun updateChargeLimitControls() {
        val enabled = batteryChargeLimitSwitch.isChecked
        batteryChargeLimitSeekBar.isEnabled = enabled
        batteryChargeLimitSeekBar.alpha = if (enabled) 1f else 0.4f
        batteryChargeLimitValue.alpha = if (enabled) 1f else 0.4f
    }

    private fun bindCorePatchSettings() {
        corepatchBypassSignatures.isChecked = settings.corepatchBypassSignatures
        corepatchBypassSignatures.setOnCheckedChangeListener { _, value ->
            settings.corepatchBypassSignatures = value
            syncModulePrefs()
            updateFeatureSummaries()
        }

        corepatchAllowDowngrade.isChecked = settings.corepatchAllowDowngrade
        corepatchAllowDowngrade.setOnCheckedChangeListener { _, value ->
            settings.corepatchAllowDowngrade = value
            syncModulePrefs()
            updateFeatureSummaries()
        }

        corepatchBypassDigest.isChecked = settings.corepatchBypassDigest
        corepatchBypassDigest.setOnCheckedChangeListener { _, value ->
            settings.corepatchBypassDigest = value
            syncModulePrefs()
            updateFeatureSummaries()
        }

        safeModeSwitch.isChecked = settings.safeMode
        safeModeSwitch.setOnCheckedChangeListener { _, value ->
            settings.safeMode = value
            syncModulePrefs()
            forceErrorLogDump()
            updateFeatureSummaries()
        }

        softRebootButton.setOnClickListener {
            runRootAction(getString(R.string.status_soft_rebooting), SOFT_REBOOT_COMMAND)
        }
    }

    /**
     * Camera shutter sound (system framework).
     *
     * The toggle is read by the system_server hook, so the prefs are pushed to
     * the LSPosed prefs location right away.
     */
    private fun bindCameraSettings() {
        disableCameraSoundSwitch.isChecked = settings.disableCameraSound
        disableCameraSoundSwitch.setOnCheckedChangeListener { _, value ->
            settings.disableCameraSound = value
            syncModulePrefs()
            updateFeatureSummaries()
        }
    }

    /**
     * Pushes the freshly written prefs XML to the LSPosed prefs location so the
     * hooked processes (system_server / SystemUI / launcher) can read the new
     * values through XSharedPreferences without a reboot.
     */
    private fun syncModulePrefs() {
        RootShell.run(SYNC_PREFS_COMMAND) { success, output ->
            if (!success) Log.w(TAG, "Prefs sync failed: $output")
        }
    }

    /**
     * 強制出力: crash buffer + LSPosed module logs to /sdcard/Download/Errorlog
     * so every hook error can be diagnosed later.
     */
    private fun forceErrorLogDump(showToast: Boolean = true) {
        RootShell.run(DUMP_ERROR_LOG_COMMAND) { success, output ->
            if (!success) Log.w(TAG, "Error log dump failed: $output")
            if (isFinishing || isDestroyed) return@run
            if (!showToast) return@run
            val message = getString(R.string.status_safe_mode_log)
            statusText.text = message
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Other tab: display language switcher. The selection is stored in the
     * shared prefs and applied through [LocaleHelper] on the next attach, so
     * the activity is recreated immediately.
     */
    private fun bindLanguage() {
        val languageKeys = listOf(HookConfig.LANGUAGE_JA, HookConfig.LANGUAGE_EN)
        val adapter = ArrayAdapter(
            this,
            R.layout.item_spinner,
            listOf(getString(R.string.language_ja), getString(R.string.language_en))
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        languageSpinner.adapter = adapter
        languageSpinner.setSelection(languageKeys.indexOf(settings.appLanguage).coerceAtLeast(0), false)

        languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val selected = languageKeys[position]
                if (selected != settings.appLanguage) {
                    settings.appLanguage = selected
                    recreate()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun bindStatusBarOrder() {
        statusLeftAdapter = StatusBarOrderAdapter { element ->
            moveStatusElementToOtherSide(element)
        }
        statusRightAdapter = StatusBarOrderAdapter { element ->
            moveStatusElementToOtherSide(element)
        }

        statusLeftRecycler.layoutManager = LinearLayoutManager(this)
        statusRightRecycler.layoutManager = LinearLayoutManager(this)
        statusLeftRecycler.adapter = statusLeftAdapter
        statusRightRecycler.adapter = statusRightAdapter
        attachStatusBarDrag(statusLeftRecycler, statusLeftAdapter)
        attachStatusBarDrag(statusRightRecycler, statusRightAdapter)

        resetStatusOrderButton.setOnClickListener {
            applyStatusBarOrder(
                StatusBarElement.defaults(left = true),
                StatusBarElement.defaults(left = false)
            )
        }

        val (left, right) = loadStatusBarOrder()
        applyStatusBarOrder(left, right)
    }

    private fun loadStatusBarOrder(): Pair<List<StatusBarElement>, List<StatusBarElement>> {
        val left = mutableListOf<StatusBarElement>()
        val right = mutableListOf<StatusBarElement>()

        fun addTo(target: MutableList<StatusBarElement>, key: String) {
            val element = StatusBarElement.fromKey(key) ?: return
            if (element !in left && element !in right) {
                target.add(element)
            }
        }

        settings.statusBarLeftOrder.split(',').forEach { addTo(left, it.trim()) }
        settings.statusBarRightOrder.split(',').forEach { addTo(right, it.trim()) }

        StatusBarElement.entries.forEach { element ->
            if (element !in left && element !in right) {
                if (element.defaultOnLeft) left.add(element) else right.add(element)
            }
        }

        return left to right
    }

    private fun applyStatusBarOrder(
        left: List<StatusBarElement>,
        right: List<StatusBarElement>
    ) {
        statusLeftAdapter.submit(left)
        statusRightAdapter.submit(right)
        statusLeftRecycler.requestLayout()
        statusRightRecycler.requestLayout()
        persistStatusBarOrder()
    }

    private fun persistStatusBarOrder() {
        if (!::statusLeftAdapter.isInitialized || !::statusRightAdapter.isInitialized) {
            return
        }
        settings.statusBarLeftOrder = statusLeftAdapter.keys().joinToString(",")
        settings.statusBarRightOrder = statusRightAdapter.keys().joinToString(",")
        updateFeatureSummaries()
    }

    private fun moveStatusElementToOtherSide(element: StatusBarElement) {
        when {
            statusLeftAdapter.remove(element) -> statusRightAdapter.add(element)
            statusRightAdapter.remove(element) -> statusLeftAdapter.add(element)
            else -> return
        }
        statusLeftRecycler.requestLayout()
        statusRightRecycler.requestLayout()
        persistStatusBarOrder()
    }

    private fun attachStatusBarDrag(
        recycler: RecyclerView,
        adapter: StatusBarOrderAdapter
    ) {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) {
                    return false
                }
                adapter.move(from, to)
                persistStatusBarOrder()
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun isLongPressDragEnabled(): Boolean = true
        }
        val helper = ItemTouchHelper(callback)
        helper.attachToRecyclerView(recycler)
        adapter.attachDragHelper(helper)
    }

    private fun bindBackupRestore() {
        backupButton.setOnClickListener { createLayoutBackup() }
        restoreButton.setOnClickListener { restoreLayoutBackup() }
        refreshBackupList()
    }

    private fun createLayoutBackup() {
        val dir = File(
            backupRoot,
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        )
        if (!dir.mkdirs()) {
            Toast.makeText(this, R.string.status_failed, Toast.LENGTH_SHORT).show()
            return
        }

        setBusy(true)
        statusText.text = getString(R.string.status_backing_up)

        RootShell.run(STOP_LAUNCHER_COMMAND) { stopped, stopOutput ->
            if (!stopped) {
                finishBackup(false, stopOutput, dir)
                return@run
            }

            RootShell.run(LIST_LAUNCHER_FILES_COMMAND) { _, listOutput ->
                val paths = listOutput.lineSequence()
                    .map { it.trim() }
                    .filter { it.startsWith("/data/data/${HookConfig.TARGET_PACKAGE}/") }
                    .toList()

                if (paths.isEmpty()) {
                    finishBackup(false, listOutput, dir)
                    return@run
                }

                copyBackupFiles(paths, 0, dir) { success, error ->
                    finishBackup(success, error, dir)
                }
            }
        }
    }

    private fun copyBackupFiles(
        paths: List<String>,
        index: Int,
        dir: File,
        callback: (Boolean, String) -> Unit
    ) {
        if (index >= paths.size) {
            callback(true, "")
            return
        }

        val path = paths[index]
        RootShell.runBytes("cat '$path'") { success, output, bytes ->
            if (!success) {
                callback(false, "read failed: $path $output")
                return@runBytes
            }

            val file = File(dir, path.substringAfterLast('/'))
            if (runCatching { file.writeBytes(bytes) }.isFailure) {
                callback(false, "write failed: ${file.name}")
                return@runBytes
            }

            copyBackupFiles(paths, index + 1, dir, callback)
        }
    }

    private fun finishBackup(success: Boolean, detail: String, dir: File) {
        RootShell.run(START_LAUNCHER_COMMAND) { _, _ ->
            if (isFinishing || isDestroyed) return@run

            setBusy(false)
            if (!success) dir.deleteRecursively()

            val message = if (success) {
                getString(R.string.status_backup_success)
            } else {
                getString(
                    R.string.status_failed,
                    detail.take(500).ifBlank { getString(R.string.status_root_unavailable) }
                )
            }
            statusText.text = message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            if (success) refreshBackupList(selectLatest = true)
        }
    }

    private fun restoreLayoutBackup() {
        val backup = selectedBackup
        if (backup == null) {
            Toast.makeText(this, R.string.backup_none_selected, Toast.LENGTH_SHORT).show()
            return
        }

        val files = backup.listFiles()?.filter { it.isFile }.orEmpty()
        if (files.isEmpty()) {
            Toast.makeText(this, R.string.backup_none_selected, Toast.LENGTH_SHORT).show()
            return
        }

        setBusy(true)
        statusText.text = getString(R.string.status_restoring)

        RootShell.run(STOP_LAUNCHER_COMMAND) { stopped, stopOutput ->
            if (!stopped) {
                finishRestore(false, stopOutput)
                return@run
            }

            RootShell.run(REMOVE_OLD_DB_COMMAND) { removed, removeOutput ->
                if (!removed) {
                    finishRestore(false, removeOutput)
                    return@run
                }

                restoreBackupFiles(files, 0) { success, error ->
                    if (!success) {
                        finishRestore(false, error)
                        return@restoreBackupFiles
                    }

                    RootShell.run(FIX_OWNER_COMMAND) { fixed, fixOutput ->
                        finishRestore(fixed, fixOutput)
                    }
                }
            }
        }
    }

    private fun restoreBackupFiles(
        files: List<File>,
        index: Int,
        callback: (Boolean, String) -> Unit
    ) {
        if (index >= files.size) {
            callback(true, "")
            return
        }

        val file = files[index]
        val name = file.name
        val destination = when {
            name.startsWith("launcher") ->
                "/data/data/${HookConfig.TARGET_PACKAGE}/databases/$name"

            name.endsWith(".xml") ->
                "/data/data/${HookConfig.TARGET_PACKAGE}/shared_prefs/$name"

            else -> null
        }

        if (destination == null) {
            restoreBackupFiles(files, index + 1, callback)
            return
        }

        val bytes = runCatching { file.readBytes() }.getOrElse {
            callback(false, "read failed: $name")
            return
        }

        RootShell.runWithInput("cat > '$destination'", bytes) { success, output ->
            if (!success) {
                callback(false, "write failed: $name $output")
            } else {
                restoreBackupFiles(files, index + 1, callback)
            }
        }
    }

    private fun finishRestore(success: Boolean, detail: String) {
        RootShell.run(START_LAUNCHER_COMMAND) { _, _ ->
            if (isFinishing || isDestroyed) return@run

            setBusy(false)
            val message = if (success) {
                getString(R.string.status_restore_success)
            } else {
                getString(
                    R.string.status_failed,
                    detail.take(500).ifBlank { getString(R.string.status_root_unavailable) }
                )
            }
            statusText.text = message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            if (success) refreshBackupList()
        }
    }

    private fun refreshBackupList(selectLatest: Boolean = false) {
        val dirs = backupRoot.listFiles { file ->
            file.isDirectory &&
                BACKUP_NAME_PATTERN.matches(file.name) &&
                file.listFiles { child ->
                    child.isFile && child.name.startsWith("launcher") && child.name.contains(".db")
                }?.isNotEmpty() == true
        }?.sortedByDescending { it.name }.orEmpty()
        backups = dirs

        val labels = if (dirs.isEmpty()) {
            listOf(getString(R.string.backup_empty))
        } else {
            dirs.map { formatBackupLabel(it.name) }
        }

        backupSpinner.adapter = ArrayAdapter(
            this,
            R.layout.item_spinner,
            labels
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }

        backupSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                selectedBackup = dirs.getOrNull(position)
                restoreButton.isEnabled = selectedBackup != null
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedBackup = null
                restoreButton.isEnabled = false
            }
        }

        if (dirs.isNotEmpty()) {
            val index = if (selectLatest) {
                0
            } else {
                backupSpinner.selectedItemPosition.coerceIn(0, dirs.lastIndex)
            }
            backupSpinner.setSelection(index, false)
            selectedBackup = dirs[index]
            restoreButton.isEnabled = true
        } else {
            selectedBackup = null
            restoreButton.isEnabled = false
        }
    }

    private fun formatBackupLabel(name: String): String = runCatching {
        val parser = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val date = parser.parse(name) ?: return@runCatching name
        SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(date)
    }.getOrDefault(name)

    private fun bindActions() {
        appearanceShortcutButton.setOnClickListener { showPage(PAGE_LAUNCHER) }
        systemUiShortcutButton.setOnClickListener { showPage(PAGE_SYSTEMUI) }

        restartButton.setOnClickListener {
            val shapeChanged = settings.iconShape != settings.lastAppliedShape
            val command = if (shapeChanged) CLEAR_CACHE_COMMAND else RESTART_COMMAND
            val progress = if (shapeChanged) {
                getString(R.string.status_clearing_cache)
            } else {
                getString(R.string.status_restarting)
            }

            runRootAction(progress, command) {
                if (shapeChanged) {
                    settings.lastAppliedShape = settings.iconShape
                }
            }
        }
        clearCacheButton.setOnClickListener {
            runRootAction(getString(R.string.status_clearing_cache), CLEAR_CACHE_COMMAND) {
                settings.lastAppliedShape = settings.iconShape
            }
        }
        restartSystemUiButton.setOnClickListener {
            runRootAction(
                getString(R.string.status_restarting_systemui),
                RESTART_SYSTEMUI_COMMAND
            )
        }
    }

    private fun runRootAction(
        progressMessage: String,
        command: String,
        successMessage: String = getString(R.string.status_success),
        onSuccess: (() -> Unit)? = null
    ) {
        setBusy(true)
        statusText.text = progressMessage

        RootShell.run(command) { success, output ->
            if (isFinishing || isDestroyed) return@run

            setBusy(false)
            val message = if (success) {
                onSuccess?.invoke()
                successMessage
            } else {
                val detail = output.take(500).ifBlank { getString(R.string.status_root_unavailable) }
                getString(R.string.status_failed, detail)
            }
            statusText.text = message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun setBusy(busy: Boolean) {
        restartButton.isEnabled = !busy
        clearCacheButton.isEnabled = !busy
        restartSystemUiButton.isEnabled = !busy
        softRebootButton.isEnabled = !busy
        backupButton.isEnabled = !busy
        restoreButton.isEnabled = !busy && selectedBackup != null
        backupSpinner.isEnabled = !busy
        if (::resetStatusOrderButton.isInitialized) {
            resetStatusOrderButton.isEnabled = !busy
        }
    }

    private companion object {
        private const val PAGE_HOME = 0
        private const val PAGE_FEATURES = 1
        private const val PAGE_LOG = 2
        private const val PAGE_MORE = 3
        private const val PAGE_LAUNCHER = 4
        private const val PAGE_SYSTEMUI = 5
        private const val PAGE_FRAMEWORK = 6

        private const val HOME_START =
            "am start -a android.intent.action.MAIN -c android.intent.category.HOME"

        /** Restarts the Zygote processes (= soft reboot) so system_server reloads the module. */
        private const val SOFT_REBOOT_COMMAND = "setprop ctl.restart zygote"

        /** Restart menu commands (run through root). */
        private val DUMP_ERROR_LOG_COMMAND = buildString {
            append("mkdir -p /sdcard/Download; { ")
            append("echo '===== MockingBatwing error log dump ====='; date; ")
            append("logcat -d -b crash -t 300 2>/dev/null; ")
            append("echo '===== LSPosed module log ====='; ")
            append("tail -n 200 /data/adb/lspd/log/modules_* 2>/dev/null; ")
            append("} >> ${HookConfig.ERROR_LOG_PATH}")
        }
        private const val MENU_RESTART_LAUNCHER = 1
        private const val MENU_RESTART_SYSTEMUI = 2
        private const val MENU_SOFT_REBOOT = 3
        private const val MENU_REBOOT_DEVICE = 4
        private const val MENU_CLEAR_CACHE = 5

        private val RESTART_SYSTEMUI_QUICK_COMMAND = "pkill -f ${HookConfig.SYSTEM_UI_PACKAGE}"
        private const val REBOOT_COMMAND = "reboot"
        private val CLEAR_CACHE_QUICK_COMMAND =
            "rm -f /data/data/${HookConfig.TARGET_PACKAGE}/databases/app_icons.db* " +
                "&& am force-stop ${HookConfig.TARGET_PACKAGE}"

        private val BACKUP_NAME_PATTERN = Regex("^\\d{8}_\\d{6}$")
        private val STOP_LAUNCHER_COMMAND = "am force-stop ${HookConfig.TARGET_PACKAGE}"
        private const val START_LAUNCHER_COMMAND = HOME_START

        private val LIST_LAUNCHER_FILES_COMMAND = buildString {
            append("ls -1 ")
            append("/data/data/${HookConfig.TARGET_PACKAGE}/databases/launcher*.db* ")
            append("/data/data/${HookConfig.TARGET_PACKAGE}/shared_prefs/*.xml ")
            append("2>/dev/null")
        }

        private val REMOVE_OLD_DB_COMMAND = buildString {
            append("DB=/data/data/${HookConfig.TARGET_PACKAGE}/databases; ")
            append("rm -f \$DB/launcher*.db \$DB/launcher*.db-wal ")
            append("\$DB/launcher*.db-shm \$DB/launcher*.db-journal")
        }

        private val FIX_OWNER_COMMAND = buildString {
            append("DB=/data/data/${HookConfig.TARGET_PACKAGE}/databases; ")
            append("PREFS=/data/data/${HookConfig.TARGET_PACKAGE}/shared_prefs; ")
            append("owner=\$(stat -c '%u:%g' \$DB); ")
            append("chown \$owner \$DB/launcher*.db* 2>/dev/null; ")
            append("chmod 660 \$DB/launcher*.db* 2>/dev/null; ")
            append("owner2=\$(stat -c '%u:%g' \$PREFS); ")
            append("chown \$owner2 \$PREFS/*.xml 2>/dev/null; ")
            append("chmod 660 \$PREFS/*.xml 2>/dev/null; true")
        }

        private const val DATABASE_DIR =
            "/data/data/${HookConfig.TARGET_PACKAGE}/databases"

        /** The module's own package (applicationId) that stores the prefs. */
        private val MODULE_PACKAGE: String = BuildConfig.APPLICATION_ID

        private val SOURCE_PREFS =
            "/data/user/0/$MODULE_PACKAGE/shared_prefs/${HookConfig.PREFS_NAME}.xml"

        /**
         * LSPosed's New XSharedPreferences copies module prefs to
         * /data/misc/apexdata/<uuid>/prefs/<module>/ and creates the copy with
         * mode 660. The launcher (a different UID) then cannot read it, so the
         * host sees empty settings. Copy our directly-written XML into that
         * location and make it world-readable before restarting the launcher.
         *
         * The location differs between the LSPosed generations, so every known
         * layout is covered: /data/misc/apexdata/<uuid>/prefs (older builds),
         * /data/misc/apexdata/<x>/<uuid>/prefs and /data/misc/<uuid>/prefs
         * (newer builds).
         */
        private val PREFS_TARGET_DIRS = listOf(
            "/data/misc/apexdata/*/prefs",
            "/data/misc/apexdata/*/*/prefs",
            "/data/misc/*/prefs",
            "/data/misc/*/*/prefs"
        )

        private val SYNC_PREFS_COMMAND = buildString {
            append("if [ -f $SOURCE_PREFS ]; then ")
            append("for p in ${PREFS_TARGET_DIRS.joinToString(" ")}; do ")
            append("if [ -d \"\$p\" ]; then ")
            append("d=\"\$p/$MODULE_PACKAGE\"; ")
            append("mkdir -p \"\$d\" 2>/dev/null; ")
            append("cp $SOURCE_PREFS \"\$d/${HookConfig.PREFS_NAME}.xml\" 2>/dev/null && ")
            append("chmod 644 \"\$d/${HookConfig.PREFS_NAME}.xml\"; ")
            append("fi; done; fi; true")
        }

        private val RESTART_COMMAND =
            "$SYNC_PREFS_COMMAND; am force-stop ${HookConfig.TARGET_PACKAGE}; $HOME_START"

        private val RESTART_SYSTEMUI_COMMAND = buildString {
            append("$SYNC_PREFS_COMMAND; ")
            append("pid=\$(pidof ${HookConfig.SYSTEM_UI_PACKAGE}); ")
            append("if [ -n \"\$pid\" ]; then kill \$pid; fi")
        }

        /**
         * Explicit file names instead of relying on shell glob expansion, and a
         * post-delete existence check so a silent rm failure is reported.
         */
        private val CLEAR_CACHE_COMMAND = buildString {
            append("rm -f ")
            append("$DATABASE_DIR/app_icons.db ")
            append("$DATABASE_DIR/app_icons.db-journal ")
            append("$DATABASE_DIR/app_icons.db-wal ")
            append("$DATABASE_DIR/app_icons.db-shm")
            append("; if [ -e $DATABASE_DIR/app_icons.db ]; then ")
            append("echo 'app_icons.db could not be removed'; exit 1; fi")
            append("; $SYNC_PREFS_COMMAND")
            append("; am force-stop ${HookConfig.TARGET_PACKAGE}")
            append("; $HOME_START")
        }
    }
}
