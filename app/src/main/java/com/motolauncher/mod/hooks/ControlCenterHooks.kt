package com.motolauncher.mod.hooks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap

object ControlCenterHooks : YukiBaseHooker() {

    private const val TAG = "MotoControlCenterHooks"
    private const val TARGET_PACKAGE = "com.android.systemui"

    // Class names identified from SystemUI.apk
    private const val CLASS_QS_MODERN_PANEL = "com.android.systemui.qs.QSModernPanel"
    private const val CLASS_QS_MODERN_PANEL_CONTAINER = "com.android.systemui.qs.QSModernPanelContainer"
    private const val CLASS_QS_MODERN_PANEL_CONTAINER_CONTROLLER = "com.android.systemui.qs.QSModernPanelContainerController"
    private const val CLASS_QS_MODERN_FIXED_PANEL = "com.android.systemui.qs.QSModernFixedPanel"
    private const val CLASS_MODERN_FIXED_TILE_LAYOUT = "com.android.systemui.qs.QSModernFixedPanel\$ModernFixedTileLayout"
    private const val CLASS_QS_TILE_VIEW_MODERN_FIXED_IMPL = "com.android.systemui.qs.tileimpl.QSTileViewModernFixedImpl"
    private const val CLASS_QS_MODERN_FIXED_PANEL_CONTROLLER = "com.android.systemui.qs.QSModernFixedPanelController"

    // Resource ID names identified from SystemUI.apk
    private const val ID_SEEKBAR_CONTAINER = "modern_seekbar_container"
    private const val ID_VOLUME_SLIDER = "volume_slider_container"
    private const val ID_BRIGHTNESS_SLIDER = "brightness_slider"

    // Map to keep track of standalone AirplaneModeButtonViews per layout without leaks
    private val airplaneButtons = WeakHashMap<View, AirplaneModeButtonView>()

    override fun onHook() {
        val classLoader = appClassLoader ?: return

        // 1. スライダー左右反転 (Brightness & Volume Swap: 左: 明るさ, 右: 音量 + 高さ同期)
        hookSliderSwap(classLoader)

        // 2. iOS 15 風 2×2 通信カード (2x2 グリッド + 機内モードボタン追加)
        hookConnectivityCard(classLoader)
    }

    // =========================================================================================
    // FEATURE 1: VOLUME & BRIGHTNESS SLIDERS SWAP + HEIGHT ALIGNMENT + MEDIA PUSH-DOWN
    // =========================================================================================

    private fun hookSliderSwap(classLoader: ClassLoader) {
        hookQSModernPanel(classLoader)
        hookQSModernPanelContainer(classLoader)
        hookQSModernPanelContainerController(classLoader)
    }

    private fun hookQSModernPanel(classLoader: ClassLoader) {
        try {
            val qsModernPanelClass = XposedHelpers.findClass(CLASS_QS_MODERN_PANEL, classLoader)

            // Hook setBrightnessViewMargin: Swap to left (marginStart = 0, marginEnd = mSeekbarMargin)
            XposedHelpers.findAndHookMethod(
                qsModernPanelClass,
                "setBrightnessViewMargin",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!modulePrefs.getBoolean("cc_swap_sliders", true)) return
                        val panel = param.thisObject ?: return
                        fixBrightnessMargin(panel)
                        reorderSliders(panel)
                    }
                }
            )

            // Hook setVolumeSliderContainerMargin: Swap to right (marginStart = mSeekbarMargin, marginEnd = 0)
            XposedHelpers.findAndHookMethod(
                qsModernPanelClass,
                "setVolumeSliderContainerMargin",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!modulePrefs.getBoolean("cc_swap_sliders", true)) return
                        val panel = param.thisObject ?: return
                        fixVolumeMargin(panel)
                        reorderSliders(panel)
                    }
                }
            )

            // Hook updateDimens:
            XposedHelpers.findAndHookMethod(
                qsModernPanelClass,
                "updateDimens",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!modulePrefs.getBoolean("cc_swap_sliders", true)) return
                        val panel = param.thisObject ?: return
                        updateSquareDimensAndScrollMargin(panel)
                        enforceSquareAndSliderHeights(panel)
                    }
                }
            )

            // Hook updateResources
            XposedHelpers.findAndHookMethod(
                qsModernPanelClass,
                "updateResources",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!modulePrefs.getBoolean("cc_swap_sliders", true)) return
                        val panel = param.thisObject ?: return
                        reorderAndFixMargins(panel)
                        updateSquareDimensAndScrollMargin(panel)
                        enforceSquareAndSliderHeights(panel)
                    }
                }
            )

            // Hook onMeasure
            XposedHelpers.findAndHookMethod(
                qsModernPanelClass,
                "onMeasure",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!modulePrefs.getBoolean("cc_swap_sliders", true)) return
                        val panel = param.thisObject ?: return
                        updateSquareDimensAndScrollMargin(panel)
                        enforceSquareAndSliderHeights(panel)
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!modulePrefs.getBoolean("cc_swap_sliders", true)) return
                        val panel = param.thisObject ?: return
                        enforceSquareAndSliderHeights(panel)
                    }
                }
            )

            // Hook onLayout
            XposedHelpers.findAndHookMethod(
                qsModernPanelClass,
                "onLayout",
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!modulePrefs.getBoolean("cc_swap_sliders", true)) return
                        val panel = param.thisObject as? ViewGroup ?: return
                        ensureMediaPlayerBelowSliders(panel)
                    }
                }
            )

            // Hook onFinishInflate
            XposedHelpers.findAndHookMethod(
                qsModernPanelClass,
                "onFinishInflate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!modulePrefs.getBoolean("cc_swap_sliders", true)) return
                        val panel = param.thisObject as? ViewGroup ?: return
                        val seekBarContainer = getSeekBarContainer(panel)

                        seekBarContainer?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            if (!modulePrefs.getBoolean("cc_swap_sliders", true)) return@addOnLayoutChangeListener
                            seekBarContainer.post {
                                reorderAndFixMargins(panel)
                                enforceSquareAndSliderHeights(panel)
                            }
                        }

                        val fixedPanel = XposedHelpers.getObjectField(panel, "mFixedPanel") as? View
                        fixedPanel?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            if (!modulePrefs.getBoolean("cc_swap_sliders", true)) return@addOnLayoutChangeListener
                            fixedPanel.post {
                                enforceSquareAndSliderHeights(panel)
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Failed to hook QSModernPanel: $t")
        }
    }

    private fun hookQSModernPanelContainer(classLoader: ClassLoader) {
        try {
            val containerClass = XposedHelpers.findClass(CLASS_QS_MODERN_PANEL_CONTAINER, classLoader)

            XposedHelpers.findAndHookMethod(
                containerClass,
                "setBrightnessView",
                View::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!modulePrefs.getBoolean("cc_swap_sliders", true)) return
                        val container = param.thisObject ?: return
                        val modernPanel = XposedHelpers.getObjectField(container, "mQSModernPanel") ?: return
                        reorderAndFixMargins(modernPanel)
                        enforceSquareAndSliderHeights(modernPanel)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Failed to hook QSModernPanelContainer: $t")
        }
    }

    private fun hookQSModernPanelContainerController(classLoader: ClassLoader) {
        try {
            val controllerClass = XposedHelpers.findClass(CLASS_QS_MODERN_PANEL_CONTAINER_CONTROLLER, classLoader)

            XposedHelpers.findAndHookMethod(
                controllerClass,
                "maybeReinflateSlider",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!modulePrefs.getBoolean("cc_swap_sliders", true)) return
                        val controller = param.thisObject ?: return
                        val view = XposedHelpers.getObjectField(controller, "mView") ?: return
                        val modernPanel = XposedHelpers.getObjectField(view, "mQSModernPanel") ?: return
                        reorderAndFixMargins(modernPanel)
                        enforceSquareAndSliderHeights(modernPanel)
                    }
                }
            )

            // 下部 QS タイル設定フック: 固定通信カードおよび機内モードの重複を除外
            XposedHelpers.findAndHookMethod(
                controllerClass,
                "setTiles",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!modulePrefs.getBoolean("cc_ios_connectivity", true)) return
                        try {
                            val controller = param.thisObject ?: return
                            val host = XposedHelpers.getObjectField(controller, "mHost") ?: return
                            val interactor = XposedHelpers.getObjectField(host, "interactor") ?: return
                            val currentTiles = (XposedHelpers.callMethod(interactor, "getCurrentQSTiles") as? List<*>) ?: return

                            if (currentTiles.isEmpty()) return

                            val wifiTile = currentTiles.firstOrNull { isTileSpec(it, "internet", "wifi") } ?: currentTiles.getOrNull(0)
                            val cellTile = currentTiles.firstOrNull { isTileSpec(it, "cell", "mobile", "cellular", "data") } ?: currentTiles.getOrNull(1)
                            val btTile = currentTiles.firstOrNull { isTileSpec(it, "bt", "bluetooth") } ?: currentTiles.getOrNull(2)
                            val fixedSet = setOfNotNull(wifiTile, cellTile, btTile)

                            val lowerTiles = ArrayList<Any>()
                            for (tile in currentTiles) {
                                if (tile != null && !fixedSet.contains(tile) && !isTileSpec(tile, "airplane")) {
                                    lowerTiles.add(tile)
                                }
                            }

                            XposedHelpers.callMethod(controller, "setTiles", lowerTiles, false)
                            param.result = null
                        } catch (t: Throwable) {
                            XposedBridge.log("[$TAG] QSModernPanelContainerController.setTiles hook error: $t")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Failed to hook QSModernPanelContainerController: $t")
        }
    }

    private fun updateSquareDimensAndScrollMargin(panel: Any) {
        try {
            val panelView = panel as? View ?: return
            val orientation = try { XposedHelpers.getIntField(panel, "mOrientation") } catch (_: Throwable) { 1 }
            val isTable = try { XposedHelpers.getBooleanField(panel, "mIsMotoTableMode") } catch (_: Throwable) { false }
            if (orientation != 1 && !isTable) return

            val screenWidth = try { XposedHelpers.getIntField(panel, "mScreenWidth") } catch (_: Throwable) { 0 }
            val margin = try { XposedHelpers.getIntField(panel, "mUnfixedTileMargin") } catch (_: Throwable) { 0 }
            if (screenWidth <= 0) return

            val squareSize = (screenWidth - (margin * 4)) / 2
            if (squareSize <= 0) return

            XposedHelpers.setIntField(panel, "mFixedTileAndSliderContainerHeight", squareSize)

            val res = panelView.context.resources
            val topMargin = try {
                XposedHelpers.getIntField(panel, "mFixedTileAndSliderContainerTopMargin")
            } catch (_: Throwable) {
                val topTableId = res.getIdentifier("zz_moto_modern_media_tile_panel_topMargin_table_mode", "dimen", TARGET_PACKAGE)
                if (topTableId != 0) res.getDimensionPixelSize(topTableId) else 0
            }

            val vertMarginId = res.getIdentifier("zz_moto_qs_tile_margin_vertical", "dimen", TARGET_PACKAGE)
            val verticalMargin = if (vertMarginId != 0) res.getDimensionPixelSize(vertMarginId) else dpToPx(res, 16f)

            val expectedScrollTop = topMargin + squareSize + verticalMargin
            XposedHelpers.setIntField(panel, "mUnfixedTileScrollViewTopMarin", expectedScrollTop)

            val scrollView = XposedHelpers.getObjectField(panel, "mScrollView") as? View
            val scrollLp = scrollView?.layoutParams as? ViewGroup.MarginLayoutParams
            if (scrollLp != null && scrollLp.topMargin != expectedScrollTop) {
                scrollLp.topMargin = expectedScrollTop
                scrollView.layoutParams = scrollLp
            }
        } catch (t: Throwable) {
            // Silently ignore layout exceptions during measurement
        }
    }

    private fun enforceSquareAndSliderHeights(panel: Any) {
        try {
            val panelView = panel as? ViewGroup ?: return
            val fixedTileAndSeekBarPanel = XposedHelpers.getObjectField(panel, "mFixedTileAndSeekBarPanel") as? ViewGroup ?: return
            val fixedPanel = XposedHelpers.getObjectField(panel, "mFixedPanel") as? ViewGroup ?: return
            val seekBarContainer = XposedHelpers.getObjectField(panel, "mSeekBarContainer") as? ViewGroup ?: return

            val orientation = try { XposedHelpers.getIntField(panel, "mOrientation") } catch (_: Throwable) { 1 }
            val isTable = try { XposedHelpers.getBooleanField(panel, "mIsMotoTableMode") } catch (_: Throwable) { false }
            if (orientation != 1 && !isTable) return

            val screenWidth = try { XposedHelpers.getIntField(panel, "mScreenWidth") } catch (_: Throwable) { 0 }
            val margin = try { XposedHelpers.getIntField(panel, "mUnfixedTileMargin") } catch (_: Throwable) { 0 }

            var squareSize = fixedPanel.width
            if (squareSize <= 0) {
                squareSize = fixedPanel.measuredWidth
            }
            if (squareSize <= 0 && screenWidth > 0) {
                squareSize = (screenWidth - (margin * 4)) / 2
            }
            if (squareSize <= 0) return

            // 1. Enforce height on mFixedTileAndSeekBarPanel
            val containerLp = fixedTileAndSeekBarPanel.layoutParams
            if (containerLp != null && containerLp.height != squareSize) {
                containerLp.height = squareSize
                fixedTileAndSeekBarPanel.layoutParams = containerLp
            }

            // 2. Enforce square height on mFixedPanel
            val fixedLp = fixedPanel.layoutParams
            if (fixedLp != null && fixedLp.height != squareSize) {
                fixedLp.height = squareSize
                fixedPanel.layoutParams = fixedLp
            }

            // 3. Enforce matching height on mSeekBarContainer
            val seekLp = seekBarContainer.layoutParams
            if (seekLp != null && seekLp.height != squareSize) {
                seekLp.height = squareSize
                seekBarContainer.layoutParams = seekLp
            }

            // 4. Clip children
            fixedTileAndSeekBarPanel.clipChildren = true
            fixedTileAndSeekBarPanel.clipToPadding = true
            seekBarContainer.clipChildren = true
            seekBarContainer.clipToPadding = true

            // 5. Update panel fields
            XposedHelpers.setIntField(panel, "mFixedTileAndSliderContainerHeight", squareSize)

            // 6. Enforce lower scroll view top margin
            val res = panelView.context.resources
            val topMargin = try {
                XposedHelpers.getIntField(panel, "mFixedTileAndSliderContainerTopMargin")
            } catch (_: Throwable) {
                (fixedTileAndSeekBarPanel.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
            }
            val vertMarginId = res.getIdentifier("zz_moto_qs_tile_margin_vertical", "dimen", TARGET_PACKAGE)
            val verticalMargin = if (vertMarginId != 0) res.getDimensionPixelSize(vertMarginId) else dpToPx(res, 16f)
            val expectedScrollTop = topMargin + squareSize + verticalMargin
            XposedHelpers.setIntField(panel, "mUnfixedTileScrollViewTopMarin", expectedScrollTop)

            val scrollView = XposedHelpers.getObjectField(panel, "mScrollView") as? View
            val scrollLp = scrollView?.layoutParams as? ViewGroup.MarginLayoutParams
            if (scrollLp != null && scrollLp.topMargin != expectedScrollTop) {
                scrollLp.topMargin = expectedScrollTop
                scrollView.layoutParams = scrollLp
            }

            // 7. Update SeekBars, redraw drawables to squareSize, and position bottom icons
            updateSlidersAndBottomIcons(panel, seekBarContainer, squareSize)
        } catch (_: Throwable) {}
    }

    private fun updateSlidersAndBottomIcons(panel: Any, seekBarContainer: ViewGroup, targetHeight: Int) {
        try {
            val context = seekBarContainer.context
            val res = context.resources
            val sliderId = res.getIdentifier("slider", "id", TARGET_PACKAGE)
            val sliderVolumeId = res.getIdentifier("slider_volume", "id", TARGET_PACKAGE)
            val autoBtnId = res.getIdentifier("brightness_auto_btn", "id", TARGET_PACKAGE)
            val desktopAutoBtnId = res.getIdentifier("desktop_brightness_auto_btn", "id", TARGET_PACKAGE)
            val volumeTypeId = res.getIdentifier("volume_type", "id", TARGET_PACKAGE)
            val seekbarMargin = getSeekbarMargin(panel)
            val cornerRadiusRatio = try { XposedHelpers.getFloatField(panel, "mCornerRadiusRatio") } catch (_: Throwable) { 0.32f }

            for (i in 0 until seekBarContainer.childCount) {
                val child = seekBarContainer.getChildAt(i)
                if (child is ViewGroup) {
                    child.clipChildren = true
                    child.clipToPadding = true

                    val childLp = child.layoutParams
                    if (childLp != null && childLp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                        childLp.height = ViewGroup.LayoutParams.MATCH_PARENT
                        child.layoutParams = childLp
                    }

                    val seekbarWidth = if (child.width > 0) {
                        child.width
                    } else if (targetHeight > 0) {
                        (targetHeight - seekbarMargin) / 2
                    } else {
                        dpToPx(res, 70f)
                    }

                    try {
                        XposedHelpers.callMethod(
                            child,
                            "updateDrawableWidthAndRadius",
                            targetHeight,
                            seekbarWidth * cornerRadiusRatio,
                            seekbarWidth
                        )
                    } catch (_: Throwable) {}

                    val seekBar = (if (sliderId != 0) child.findViewById<View>(sliderId) else null)
                        ?: (if (sliderVolumeId != 0) child.findViewById<View>(sliderVolumeId) else null)
                        ?: findSeekBarRecursive(child)

                    if (seekBar != null) {
                        val sbLp = seekBar.layoutParams
                        if (sbLp != null && sbLp.width != targetHeight) {
                            sbLp.width = targetHeight
                            seekBar.layoutParams = sbLp
                            seekBar.requestLayout()
                        }
                    }

                    val iconViews = listOfNotNull(
                        if (autoBtnId != 0) child.findViewById<View>(autoBtnId) else null,
                        if (desktopAutoBtnId != 0) child.findViewById<View>(desktopAutoBtnId) else null,
                        if (volumeTypeId != 0) child.findViewById<View>(volumeTypeId) else null
                    )

                    val targetBottomMargin = dpToPx(res, 14f)
                    for (iconView in iconViews) {
                        val iconLp = iconView.layoutParams as? FrameLayout.LayoutParams
                        if (iconLp != null) {
                            var changed = false
                            if (iconLp.gravity != (Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM)) {
                                iconLp.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                                changed = true
                            }
                            if (iconLp.topMargin != 0) {
                                iconLp.topMargin = 0
                                changed = true
                            }
                            if (iconLp.bottomMargin != targetBottomMargin) {
                                iconLp.bottomMargin = targetBottomMargin
                                changed = true
                            }
                            if (changed) {
                                iconView.layoutParams = iconLp
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun ensureMediaPlayerBelowSliders(panel: ViewGroup) {
        try {
            val fixedTileAndSeekBarPanel = XposedHelpers.getObjectField(panel, "mFixedTileAndSeekBarPanel") as? View ?: return
            val scrollView = XposedHelpers.getObjectField(panel, "mScrollView") as? View ?: return
            val res = panel.context.resources
            val vertMarginId = res.getIdentifier("zz_moto_qs_tile_margin_vertical", "dimen", TARGET_PACKAGE)
            val verticalMargin = if (vertMarginId != 0) res.getDimensionPixelSize(vertMarginId) else dpToPx(res, 16f)

            val panelBottom = fixedTileAndSeekBarPanel.bottom
            if (panelBottom > 0) {
                val targetTop = panelBottom + verticalMargin
                if (scrollView.top < targetTop) {
                    scrollView.layout(scrollView.left, targetTop, scrollView.right, targetTop + scrollView.measuredHeight)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun findSeekBarRecursive(vg: ViewGroup): View? {
        for (i in 0 until vg.childCount) {
            val c = vg.getChildAt(i)
            if (c.javaClass.name.contains("SeekBar", ignoreCase = true) || c.rotation == 270f) {
                return c
            }
            if (c is ViewGroup) {
                val found = findSeekBarRecursive(c)
                if (found != null) return found
            }
        }
        return null
    }

    private fun reorderSliders(panel: Any) {
        try {
            val seekBarContainer = getSeekBarContainer(panel) ?: return
            val brightnessView = getBrightnessView(panel) ?: return
            val volumeView = getVolumeView(panel) ?: return

            val brightnessIndex = seekBarContainer.indexOfChild(brightnessView)
            val volumeIndex = seekBarContainer.indexOfChild(volumeView)

            if (brightnessIndex != -1 && volumeIndex != -1 && brightnessIndex > volumeIndex) {
                seekBarContainer.removeView(brightnessView)
                seekBarContainer.addView(brightnessView, volumeIndex)
            }
        } catch (_: Throwable) {}
    }

    private fun fixBrightnessMargin(panel: Any) {
        try {
            val brightnessView = getBrightnessView(panel) ?: return
            val seekbarMargin = getSeekbarMargin(panel)
            val lp = brightnessView.layoutParams as? LinearLayout.LayoutParams ?: return

            if (lp.marginStart != 0 || lp.marginEnd != seekbarMargin) {
                lp.marginStart = 0
                lp.marginEnd = seekbarMargin
                brightnessView.layoutParams = lp
            }
        } catch (_: Throwable) {}
    }

    private fun fixVolumeMargin(panel: Any) {
        try {
            val volumeView = getVolumeView(panel) ?: return
            val seekbarMargin = getSeekbarMargin(panel)
            val lp = volumeView.layoutParams as? LinearLayout.LayoutParams ?: return

            if (lp.marginStart != seekbarMargin || lp.marginEnd != 0) {
                lp.marginStart = seekbarMargin
                lp.marginEnd = 0
                volumeView.layoutParams = lp
            }
        } catch (_: Throwable) {}
    }

    private fun reorderAndFixMargins(panel: Any) {
        reorderSliders(panel)
        fixBrightnessMargin(panel)
        fixVolumeMargin(panel)
    }

    private fun getSeekBarContainer(panel: Any): ViewGroup? {
        return try {
            XposedHelpers.getObjectField(panel, "mSeekBarContainer") as? ViewGroup
        } catch (_: Throwable) {
            (panel as? ViewGroup)?.let { vg ->
                val id = vg.context.resources.getIdentifier(ID_SEEKBAR_CONTAINER, "id", TARGET_PACKAGE)
                if (id != 0) vg.findViewById(id) else null
            }
        }
    }

    private fun getBrightnessView(panel: Any): View? {
        return try {
            XposedHelpers.getObjectField(panel, "mBrightnessView") as? View
        } catch (_: Throwable) {
            (panel as? ViewGroup)?.let { vg ->
                val id = vg.context.resources.getIdentifier(ID_BRIGHTNESS_SLIDER, "id", TARGET_PACKAGE)
                if (id != 0) vg.findViewById(id) else null
            }
        }
    }

    private fun getVolumeView(panel: Any): View? {
        return try {
            XposedHelpers.getObjectField(panel, "mVolumeSliderContainer") as? View
        } catch (_: Throwable) {
            (panel as? ViewGroup)?.let { vg ->
                val id = vg.context.resources.getIdentifier(ID_VOLUME_SLIDER, "id", TARGET_PACKAGE)
                if (id != 0) vg.findViewById(id) else null
            }
        }
    }

    private fun getSeekbarMargin(panel: Any): Int {
        return try {
            XposedHelpers.getIntField(panel, "mSeekbarMargin")
        } catch (_: Throwable) {
            (panel as? View)?.let { v ->
                val id = v.context.resources.getIdentifier("zz_moto_qs_tile_margin_horizontal", "dimen", TARGET_PACKAGE)
                if (id != 0) v.context.resources.getDimensionPixelSize(id) else 16
            } ?: 16
        }
    }

    // =========================================================================================
    // FEATURE 2: CONNECTIVITY CARD -> iOS 15 CONTROL CENTER 2x2 SQUARE WITH AIRPLANE MODE
    // =========================================================================================

    private fun hookConnectivityCard(classLoader: ClassLoader) {
        hookQSModernFixedPanel(classLoader)
        hookModernFixedTileLayout(classLoader)
        hookQSTileViewModernFixed(classLoader)
        hookQSModernFixedPanelController(classLoader)
    }

    private fun hookQSModernFixedPanel(classLoader: ClassLoader) {
        try {
            val panelClass = XposedHelpers.findClass(CLASS_QS_MODERN_FIXED_PANEL, classLoader)
            XposedBridge.hookAllConstructors(panelClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!modulePrefs.getBoolean("cc_ios_connectivity", true)) return
                    try {
                        val panel = param.thisObject as? View ?: return
                        XposedHelpers.setIntField(panel, "mMaxTiles", 3)

                        val p = dpToPx(panel.context.resources, 8f)
                        panel.setPadding(p, p, p, p)
                        panel.setPaddingRelative(p, p, p, p)
                    } catch (_: Throwable) {}
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Failed to hook QSModernFixedPanel: $t")
        }
    }

    private fun hookModernFixedTileLayout(classLoader: ClassLoader) {
        try {
            val layoutClass = XposedHelpers.findClass(CLASS_MODERN_FIXED_TILE_LAYOUT, classLoader)

            fun enforceGridDimensions(layout: Any) {
                try {
                    XposedHelpers.setIntField(layout, "mMaxColumns", 2)
                    XposedHelpers.setIntField(layout, "mResourceColumns", 2)
                    XposedHelpers.setIntField(layout, "mColumns", 2)
                    XposedHelpers.setIntField(layout, "mMaxAllowedRows", 2)
                    XposedHelpers.setIntField(layout, "mRows", 2)
                    XposedHelpers.setIntField(layout, "mCellMarginHorizontal", 0)
                    XposedHelpers.setIntField(layout, "mCellMarginVertical", 0)
                } catch (_: Throwable) {}
            }

            XposedBridge.hookAllConstructors(layoutClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!modulePrefs.getBoolean("cc_ios_connectivity", true)) return
                    val layout = param.thisObject ?: return
                    enforceGridDimensions(layout)
                    (layout as? View)?.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                        if (!modulePrefs.getBoolean("cc_ios_connectivity", true)) return@addOnLayoutChangeListener
                        val vg = v as? ViewGroup ?: return@addOnLayoutChangeListener
                        layoutAirplaneButton(vg)
                    }
                }
            })

            XposedHelpers.findAndHookMethod(
                layoutClass,
                "updateResources",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!modulePrefs.getBoolean("cc_ios_connectivity", true)) return
                        val layout = param.thisObject ?: return
                        enforceGridDimensions(layout)
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                layoutClass,
                "onMeasure",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!modulePrefs.getBoolean("cc_ios_connectivity", true)) return
                        val layout = param.thisObject ?: return
                        enforceGridDimensions(layout)
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!modulePrefs.getBoolean("cc_ios_connectivity", true)) return
                        val layout = param.thisObject as? ViewGroup ?: return
                        val airplaneBtn = getOrCreateAirplaneButton(layout)
                        airplaneBtn.visibility = View.VISIBLE
                        airplaneBtn.alpha = 1f

                        val cellWidth = XposedHelpers.getIntField(layout, "mCellWidth")
                        val cellHeight = XposedHelpers.getIntField(layout, "mMaxCellHeight").let {
                            if (it > 0) it else XposedHelpers.getIntField(layout, "mCellHeight")
                        }
                        if (cellWidth > 0 && cellHeight > 0) {
                            airplaneBtn.measure(
                                View.MeasureSpec.makeMeasureSpec(cellWidth, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(cellHeight, View.MeasureSpec.EXACTLY)
                            )
                            layoutAirplaneButton(layout)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Failed to hook ModernFixedTileLayout: $t")
        }
    }

    private fun getOrCreateAirplaneButton(layout: ViewGroup): AirplaneModeButtonView {
        var btn = airplaneButtons[layout]
        if (btn == null) {
            btn = AirplaneModeButtonView(layout.context)
            airplaneButtons[layout] = btn
        }
        if (btn.parent !== layout) {
            (btn.parent as? ViewGroup)?.removeView(btn)
            layout.addView(btn)
        }
        return btn
    }

    private fun layoutAirplaneButton(layout: ViewGroup) {
        try {
            val airplaneBtn = getOrCreateAirplaneButton(layout)
            airplaneBtn.visibility = View.VISIBLE
            airplaneBtn.alpha = 1f

            val records = XposedHelpers.getObjectField(layout, "mRecords") as? List<*>
            val record1View = (records?.getOrNull(1)?.let { XposedHelpers.getObjectField(it, "tileView") }) as? View
            val record2View = (records?.getOrNull(2)?.let { XposedHelpers.getObjectField(it, "tileView") }) as? View

            val left: Int
            val right: Int
            val top: Int
            val bottom: Int

            if (record1View != null && record2View != null && record1View.width > 0 && record2View.height > 0) {
                left = record1View.left
                right = record1View.right
                top = record2View.top
                bottom = record2View.bottom
            } else {
                val cellWidth = XposedHelpers.getIntField(layout, "mCellWidth")
                val cellHeight = XposedHelpers.getIntField(layout, "mMaxCellHeight").let {
                    if (it > 0) it else XposedHelpers.getIntField(layout, "mCellHeight")
                }
                val cellMarginH = XposedHelpers.getIntField(layout, "mCellMarginHorizontal")
                val cellMarginV = XposedHelpers.getIntField(layout, "mCellMarginVertical")
                val sidePadding = XposedHelpers.getIntField(layout, "mSidePadding")
                val paddingStart = layout.paddingStart + sidePadding
                val isRtl = layout.layoutDirection == View.LAYOUT_DIRECTION_RTL
                val col = if (isRtl) 0 else 1
                val row = 1
                left = paddingStart + col * (cellWidth + cellMarginH)
                top = layout.paddingTop + row * (cellHeight + cellMarginV)
                right = left + cellWidth
                bottom = top + cellHeight
            }

            if (right > left && bottom > top) {
                airplaneBtn.layout(left, top, right, bottom)
            }
        } catch (_: Throwable) {}
    }

    private fun hookQSTileViewModernFixed(classLoader: ClassLoader) {
        try {
            val tileViewClass = XposedHelpers.findClass(CLASS_QS_TILE_VIEW_MODERN_FIXED_IMPL, classLoader)

            XposedHelpers.findAndHookMethod(
                tileViewClass,
                "createAndAddLabels",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!modulePrefs.getBoolean("cc_ios_connectivity", true)) return
                        val tileView = param.thisObject as? View ?: return
                        styleModernFixedTileView(tileView)
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                tileViewClass,
                "updateLayout$2",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!modulePrefs.getBoolean("cc_ios_connectivity", true)) return
                        val tileView = param.thisObject as? View ?: return
                        styleModernFixedTileView(tileView)
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                tileViewClass,
                "updateResources",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!modulePrefs.getBoolean("cc_ios_connectivity", true)) return
                        val tileView = param.thisObject as? View ?: return
                        styleModernFixedTileView(tileView)
                    }
                }
            )

            XposedBridge.hookAllMethods(
                tileViewClass,
                "handleStateChanged",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!modulePrefs.getBoolean("cc_ios_connectivity", true)) return
                        val tileView = param.thisObject as? View ?: return
                        styleModernFixedTileView(tileView)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Failed to hook QSTileViewModernFixedImpl: $t")
        }
    }

    private fun styleModernFixedTileView(tileView: View) {
        try {
            val context = tileView.context
            val res = context.resources

            // 1. Hide labels and side arrow
            val labelContainer = XposedHelpers.getObjectField(tileView, "labelContainer") as? View
            labelContainer?.visibility = View.GONE

            val sideView = XposedHelpers.getObjectField(tileView, "sideView") as? View
            sideView?.visibility = View.GONE

            // 2. Center contents within quadrant cell & strip relative padding
            if (tileView is LinearLayout) {
                tileView.gravity = Gravity.CENTER
                tileView.setPaddingRelative(0, 0, 0, 0)
            }

            // 3. Format enlarged circular background button (64dp circle) & centered icon (32dp)
            val circleSize = dpToPx(res, 64f)
            val iconSize = dpToPx(res, 32f)

            val mBg = XposedHelpers.getObjectField(tileView, "mBg") as? ImageView
            if (mBg != null) {
                val bgLp = FrameLayout.LayoutParams(circleSize, circleSize, Gravity.CENTER)
                mBg.layoutParams = bgLp
                mBg.isDuplicateParentStateEnabled = true
            }

            val icon = XposedHelpers.getObjectField(tileView, "icon") as? View
            if (icon != null) {
                val iconLp = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
                icon.layoutParams = iconLp
            }

            val iconFrame = XposedHelpers.getObjectField(tileView, "iconFrame") as? FrameLayout
            if (iconFrame != null) {
                iconFrame.layoutParams = LinearLayout.LayoutParams(circleSize, circleSize)
                iconFrame.isDuplicateParentStateEnabled = true
            }

            // 4. Ensure background drawable corner radius is circular
            val backgroundBase = XposedHelpers.getObjectField(tileView, "backgroundBaseDrawable") as? GradientDrawable
            backgroundBase?.cornerRadius = circleSize / 2f
        } catch (_: Throwable) {}
    }

    private fun hookQSModernFixedPanelController(classLoader: ClassLoader) {
        try {
            val controllerClass = XposedHelpers.findClass(CLASS_QS_MODERN_FIXED_PANEL_CONTROLLER, classLoader)

            XposedHelpers.findAndHookMethod(
                controllerClass,
                "setTiles",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!modulePrefs.getBoolean("cc_ios_connectivity", true)) return
                        try {
                            val controller = param.thisObject ?: return

                            val panel = XposedHelpers.getObjectField(controller, "mView")
                            if (panel != null) {
                                XposedHelpers.setIntField(panel, "mMaxTiles", 3)
                            }

                            val host = XposedHelpers.getObjectField(controller, "mHost") ?: return
                            val interactor = XposedHelpers.getObjectField(host, "interactor") ?: return
                            val currentTiles = (XposedHelpers.callMethod(interactor, "getCurrentQSTiles") as? List<*>)
                                ?: emptyList<Any>()

                            if (currentTiles.isEmpty()) return

                            // 1. Wi-Fi (Top-Left)
                            val wifiTile = currentTiles.firstOrNull { isTileSpec(it, "internet", "wifi") }
                                ?: currentTiles.getOrNull(0)

                            // 2. Mobile Data (Top-Right)
                            val cellTile = currentTiles.firstOrNull { isTileSpec(it, "cell", "mobile", "cellular", "data") }
                                ?: currentTiles.getOrNull(1)

                            // 3. Bluetooth (Bottom-Left)
                            val btTile = currentTiles.firstOrNull { isTileSpec(it, "bt", "bluetooth") }
                                ?: currentTiles.getOrNull(2)

                            val selectedTiles = listOfNotNull(wifiTile, cellTile, btTile)

                            XposedHelpers.callMethod(controller, "setTiles", selectedTiles, true)
                            param.result = null
                        } catch (t: Throwable) {
                            XposedBridge.log("[$TAG] Error in QSModernFixedPanelController.setTiles hook: $t")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Failed to hook QSModernFixedPanelController: $t")
        }
    }

    // =========================================================================================
    // STANDALONE AIRPLANE MODE BUTTON (100% RELIABLE, INDEPENDENT OF QS TILES PIPELINE)
    // =========================================================================================

    class AirplaneModeButtonView(context: Context) : FrameLayout(context) {
        private val circleSize = dpToPx(context.resources, 64f)
        private val iconSize = dpToPx(context.resources, 32f)

        private val circleContainer = FrameLayout(context)
        private val bgImageView = ImageView(context)
        private val iconImageView = ImageView(context)
        private val bgShape = GradientDrawable().apply {
            cornerRadius = circleSize / 2f
        }

        private var colorActive = 0xFF0084FF.toInt()
        private var colorInactive = 0x26FFFFFF.toInt()

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                post { updateState() }
            }
        }

        private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                post { updateState() }
            }
        }

        private var isReceiverRegistered = false
        private var isObserverRegistered = false

        init {
            isClickable = true
            isFocusable = true

            val rippleColor = ColorStateList.valueOf(0x33FFFFFF)
            val rippleDrawable = RippleDrawable(rippleColor, bgShape, bgShape)
            bgImageView.setImageDrawable(rippleDrawable)
            bgImageView.isDuplicateParentStateEnabled = true

            iconImageView.setImageDrawable(AirplaneDrawable())
            iconImageView.scaleType = ImageView.ScaleType.FIT_CENTER
            iconImageView.setColorFilter(Color.WHITE)
            iconImageView.isDuplicateParentStateEnabled = true

            circleContainer.isDuplicateParentStateEnabled = true
            circleContainer.addView(bgImageView, LayoutParams(circleSize, circleSize, Gravity.CENTER))
            circleContainer.addView(iconImageView, LayoutParams(iconSize, iconSize, Gravity.CENTER))

            addView(circleContainer, LayoutParams(circleSize, circleSize, Gravity.CENTER))

            setOnClickListener {
                toggleAirplaneMode()
            }

            setOnLongClickListener {
                openAirplaneSettings()
                true
            }

            updateState()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (!isReceiverRegistered) {
                try {
                    context.registerReceiver(receiver, IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED))
                    isReceiverRegistered = true
                } catch (_: Throwable) {}
            }
            if (!isObserverRegistered) {
                try {
                    context.contentResolver.registerContentObserver(
                        Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON),
                        false,
                        observer
                    )
                    isObserverRegistered = true
                } catch (_: Throwable) {}
            }
            updateColorsFromSibling()
            updateState()
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            if (isReceiverRegistered) {
                try {
                    context.unregisterReceiver(receiver)
                } catch (_: Throwable) {}
                isReceiverRegistered = false
            }
            if (isObserverRegistered) {
                try {
                    context.contentResolver.unregisterContentObserver(observer)
                } catch (_: Throwable) {}
                isObserverRegistered = false
            }
        }

        private fun updateColorsFromSibling() {
            try {
                val parentVg = parent as? ViewGroup ?: return
                for (i in 0 until parentVg.childCount) {
                    val child = parentVg.getChildAt(i)
                    if (child.javaClass.name.contains("QSTileViewModernFixed", ignoreCase = true)) {
                        val active = XposedHelpers.getIntField(child, "colorActiveFixed")
                        val inactive = XposedHelpers.getIntField(child, "colorInactiveFixed")
                        if (active != 0) colorActive = active
                        if (inactive != 0) colorInactive = inactive
                        break
                    }
                }
            } catch (_: Throwable) {}
        }

        fun updateState() {
            try {
                val isOn = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
                updateColorsFromSibling()
                if (isOn) {
                    bgShape.setColor(colorActive)
                    iconImageView.setColorFilter(if (isColorBright(colorActive)) Color.BLACK else Color.WHITE)
                } else {
                    bgShape.setColor(colorInactive)
                    iconImageView.setColorFilter(Color.WHITE)
                }
                invalidate()
            } catch (_: Throwable) {}
        }

        private fun isColorBright(color: Int): Boolean {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            return (0.299 * r + 0.587 * g + 0.114 * b) > 186
        }

        private fun toggleAirplaneMode() {
            try {
                val cr = context.contentResolver
                val cur = Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
                val next = !cur
                Settings.Global.putInt(cr, Settings.Global.AIRPLANE_MODE_ON, if (next) 1 else 0)
                val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).apply {
                    putExtra("state", next)
                }
                context.sendBroadcast(intent)
            } catch (_: Throwable) {}
            updateState()
        }

        private fun openAirplaneSettings() {
            try {
                val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Throwable) {}
        }
    }

    /**
     * Pure Vector path-based airplane icon Drawable.
     */
    class AirplaneDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val path = Path()

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return

            path.reset()
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            val scale = Math.min(w, h) / 24f
            val dx = b.left + (w - 24f * scale) / 2f
            val dy = b.top + (h - 24f * scale) / 2f

            canvas.save()
            canvas.translate(dx, dy)
            canvas.scale(scale, scale)

            path.moveTo(21f, 16f)
            path.lineTo(21f, 14f)
            path.lineTo(13f, 9f)
            path.lineTo(13f, 3.5f)
            path.cubicTo(13f, 2.67f, 12.33f, 2f, 11.5f, 2f)
            path.cubicTo(10.67f, 2f, 10f, 2.67f, 10f, 3.5f)
            path.lineTo(10f, 9f)
            path.lineTo(2f, 14f)
            path.lineTo(2f, 16f)
            path.lineTo(10f, 13.5f)
            path.lineTo(10f, 19f)
            path.lineTo(8f, 20.5f)
            path.lineTo(8f, 22f)
            path.lineTo(11.5f, 21f)
            path.lineTo(15f, 22f)
            path.lineTo(15f, 20.5f)
            path.lineTo(13f, 19f)
            path.lineTo(13f, 13.5f)
            path.close()

            canvas.drawPath(path, paint)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = 24
        override fun getIntrinsicHeight(): Int = 24
    }

    private fun isTileSpec(tile: Any?, vararg candidateSpecs: String): Boolean {
        if (tile == null) return false
        val spec = safeTileSpec(tile)
        if (spec.isEmpty()) return false
        return candidateSpecs.any { candidate ->
            spec == candidate || spec.startsWith(candidate)
        }
    }

    private fun safeTileSpec(tile: Any?): String {
        if (tile == null) return ""
        return try {
            (XposedHelpers.callMethod(tile, "getTileSpec") as? String)?.lowercase() ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun dpToPx(res: android.content.res.Resources, dp: Float): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, res.displayMetrics).toInt()
    }
}
