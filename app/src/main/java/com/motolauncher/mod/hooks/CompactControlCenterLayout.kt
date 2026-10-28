package com.motolauncher.mod.hooks

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.media.AudioManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.Condition
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.motolauncher.mod.ErrorLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/** Reuses the real media host/sliders; fixed shortcuts are independent of the QS tile list. */
internal object CompactControlCenterLayout {
    private const val TAG = "MotoControlCenterHooks"
    private const val STATE = "motorolaTweak.compactControlCenter"
    private const val PANEL = "com.android.systemui.qs.QSModernPanel"
    private lateinit var enabled: () -> Boolean
    private lateinit var updateSliders: (ViewGroup, Int) -> Unit
    private var reportedMissingMedia = false

    fun install(loader: ClassLoader, isEnabled: () -> Boolean, sliders: (ViewGroup, Int) -> Unit) {
        enabled = isEnabled
        updateSliders = sliders
        runCatching {
            val clazz = XposedHelpers.findClass(PANEL, loader)
            for (name in listOf("onMeasure", "onLayout", "updateResources", "updateDimens")) {
                XposedBridge.hookAllMethods(clazz, name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching {
                            if (name != "onMeasure") return@runCatching
                            val panel = param.thisObject as? ViewGroup ?: return@runCatching
                            if (!enabled()) {
                                (XposedHelpers.getAdditionalInstanceField(panel, STATE) as? State)?.let { restore(panel, it) }
                                return@runCatching
                            }
                            prepare(panel)?.let { applyDimensions(panel, it) }
                        }.onFailure { recover(param.thisObject, "Preparing compact layout", it) }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            if (!enabled()) return@runCatching
                            val panel = param.thisObject as? ViewGroup ?: return@runCatching
                            val state = prepare(panel) ?: return@runCatching
                            applyDimensions(panel, state)
                            if (name == "onLayout") layout(panel, state)
                        }.onFailure { recover(param.thisObject, "Applying compact layout", it) }
                    }
                })
            }
        }.onFailure { report("Installing compact layout hooks", it) }
        for (name in listOf("androidx.constraintlayout.widget.ConstraintLayout",
            "com.motorola.systemui.prc.media.MediaPanelModernQS")) {
            runCatching {
                val clazz = XposedHelpers.findClass(name, loader)
                XposedBridge.hookAllMethods(clazz, "onLayout", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            if (!enabled()) return@runCatching
                            val view = param.thisObject as? ViewGroup ?: return@runCatching
                            activeMediaHost(view)?.let {
                                fitMediaFrame(it)
                                arrangeMediaContent(it)
                            }
                        }.onFailure { report("Laying out compact media content", it) }
                    }
                })
            }.onFailure { report("Installing compact media layout hook: $name", it) }
        }
    }

    private class State(
        val fixed: ViewGroup,
        val sliders: ViewGroup,
        val top: ViewGroup,
        val scroll: View,
        val media: View,
        val overlay: FrameLayout,
        val shortcuts: FrameLayout,
        val buttons: Map<String, TileButton>,
        val mediaParent: ViewGroup,
        val mediaIndex: Int,
        val mediaParams: ViewGroup.LayoutParams,
        val originalHeights: Map<View, Int>,
        val originalWidths: Map<View, Int>,
        val originalGravities: Map<View, Int>,
        val originalContainerHeight: Int,
        val originalScrollTop: Int
    ) {
        var square = 0
        var gap = 0
        var active = false
        var geometry: List<Int> = emptyList()
    }

    private fun field(view: Any, name: String): Any? = XposedHelpers.getObjectField(view, name)

    private fun recover(target: Any?, message: String, error: Throwable) {
        report(message, error)
        runCatching {
            val panel = target as? ViewGroup ?: return@runCatching
            val state = XposedHelpers.getAdditionalInstanceField(panel, STATE) as? State ?: return@runCatching
            restore(panel, state)
        }.onFailure { report("Restoring native control-center layout", it) }
    }

    fun isActive(panel: Any): Boolean =
        (XposedHelpers.getAdditionalInstanceField(panel, STATE) as? State)?.active == true

    private fun prepare(panel: ViewGroup): State? {
        val supported = XposedHelpers.getIntField(panel, "mOrientation") == 1 ||
            XposedHelpers.getBooleanField(panel, "mIsMotoTableMode")
        val existing = XposedHelpers.getAdditionalInstanceField(panel, STATE) as? State
        if (!supported) {
            existing?.let { restore(panel, it) }
            return null
        }
        if (existing != null) {
            existing.overlay.visibility = View.VISIBLE
            return existing
        }
        val top = field(panel, "mFixedTileAndSeekBarPanel") as? ViewGroup ?: return null
        val fixed = field(panel, "mFixedPanel") as? ViewGroup ?: return null
        val sliders = field(panel, "mSeekBarContainer") as? ViewGroup ?: return null
        val scroll = field(panel, "mScrollView") as? ViewGroup ?: return null
        // Do not move the carousel's individual controls: relocate only its native host.
        val media = (findMediaHost(scroll) ?: findMediaHost(panel)) ?: run {
            if (!reportedMissingMedia) {
                reportedMissingMedia = true
                ErrorLogger.log(TAG, "Compact layout skipped: native media host not found; keeping the original layout")
            }
            return null
        }
        val mediaParent = media.parent as? ViewGroup ?: return null
        val mediaParams = media.layoutParams ?: return null
        val overlay = FrameLayout(panel.context)
        val shortcuts = FrameLayout(panel.context)
        val buttons = listOf("rotation", "silent", "bedtime").associateWith { TileButton(panel.context, it) }
        buttons.values.forEach { shortcuts.addView(it, FrameLayout.LayoutParams(1, 1)) }
        overlay.addView(shortcuts, FrameLayout.LayoutParams(1, 1))
        panel.addView(overlay, ViewGroup.LayoutParams(1, 1))
        return State(
            fixed, sliders, top, scroll, media, overlay, shortcuts, buttons,
            mediaParent, mediaParent.indexOfChild(media), mediaParams,
            listOf(top, fixed, sliders).associateWith { it.layoutParams.height },
            listOf(fixed, sliders).associateWith { it.layoutParams.width },
            listOf(fixed, sliders).mapNotNull { view ->
                (view.layoutParams as? LinearLayout.LayoutParams)?.let { view to it.gravity }
            }.toMap(),
            XposedHelpers.getIntField(panel, "mFixedTileAndSliderContainerHeight"),
            XposedHelpers.getIntField(panel, "mUnfixedTileScrollViewTopMarin")
        ).also {
            XposedHelpers.setAdditionalInstanceField(panel, STATE, it)
        }
    }

    private fun findMediaHost(root: ViewGroup): View? {
        for (index in 0 until root.childCount) {
            val view = root.getChildAt(index)
            val name = if (view.id == View.NO_ID) "" else
                runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
            if (view.javaClass.simpleName == "UniqueObjectHostView" ||
                name in setOf("media_player_container", "media_host", "qs_media_host", "media_carousel",
                    "media_player", "modern_media_container", "modern_media_player_container",
                    "modern_qs_media_container")) {
                return view
            }
            if (view is ViewGroup) findMediaHost(view)?.let { return it }
        }
        return null
    }

    fun currentTiles(host: Any): List<Any> {
        val direct = runCatching { XposedHelpers.callMethod(host, "getTiles") as? Collection<*> }.getOrNull()
        if (direct != null) return direct.filterNotNull()
        val interactor = field(host, "interactor") ?: return emptyList()
        val flow = field(interactor, "currentTiles") ?: return emptyList()
        val models = XposedHelpers.callMethod(flow, "getValue") as? List<*> ?: return emptyList()
        return models.filterNotNull().mapNotNull { field(it, "tile") }
    }

    private fun applyDimensions(panel: ViewGroup, state: State) {
        val width = XposedHelpers.getIntField(panel, "mScreenWidth")
        val margin = XposedHelpers.getIntField(panel, "mUnfixedTileMargin")
        val gapId = panel.resources.getIdentifier("zz_moto_qs_tile_margin_vertical", "dimen", "com.android.systemui")
        val gap = if (gapId != 0) panel.resources.getDimensionPixelSize(gapId) else dp(panel.context, 16)
        // Match the existing slider-swap implementation: the native connectivity
        // column determines the height. Do not replace weighted native widths.
        val square = state.fixed.measuredWidth.takeIf { it > 0 }
            ?: ((width - margin * 4) / 2)
        if (square <= 0) return
        state.square = square
        state.gap = gap
        val total = square * 2 + gap
        if (!state.active) {
            state.buttons.values.forEach { button ->
                runCatching { button.bindControl() }.onFailure { report("Initializing compact shortcut", it) }
            }
            state.active = true
        }
        if (state.media.parent === state.overlay) {
            val lp = state.media.layoutParams as FrameLayout.LayoutParams
            if (lp.width != square || lp.height != square) {
                lp.width = square
                lp.height = square
                state.media.layoutParams = lp
            }
        }
        // This field is also used by native tile/slider dimension calculations.
        // Only the outer row grows to two rows; the native cell size stays square.
        XposedHelpers.setIntField(panel, "mFixedTileAndSliderContainerHeight", square)
        height(state.top, total)
        height(state.fixed, square)
        height(state.sliders, square)
        for (view in listOf(state.fixed, state.sliders)) {
            (view.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                if (lp.gravity != Gravity.TOP) {
                    lp.gravity = Gravity.TOP
                    view.layoutParams = lp
                }
            }
        }
        val topMargin = XposedHelpers.getIntField(panel, "mFixedTileAndSliderContainerTopMargin")
        val scrollTop = topMargin + total + gap
        XposedHelpers.setIntField(panel, "mUnfixedTileScrollViewTopMarin", scrollTop)
        (state.scroll.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            if (it.topMargin != scrollTop) {
                it.topMargin = scrollTop
                state.scroll.layoutParams = it
            }
        }
        val geometry = listOf(square, gap, panel.resources.configuration.densityDpi) +
            (0 until state.sliders.childCount).map { System.identityHashCode(state.sliders.getChildAt(it)) }
        if (state.geometry != geometry) {
            state.geometry = geometry
            updateSliders(panel, square)
        }
    }

    private fun layout(panel: ViewGroup, state: State) {
        val square = state.square
        if (square <= 0) return
        val gap = state.gap
        val total = square * 2 + gap
        // Keep the native slider and connectivity controllers and parents intact.
        val fixedLeft = state.fixed.left
        val sliderLeft = state.sliders.left
        val sliderWidth = state.sliders.measuredWidth
        place(state.fixed, fixedLeft, 0, square, square)
        place(state.sliders, sliderLeft, square + gap, sliderWidth, square)
        state.top.clipChildren = true
        state.top.clipToPadding = true

        val origin = IntArray(2)
        val panelOrigin = IntArray(2)
        state.top.getLocationInWindow(origin)
        panel.getLocationInWindow(panelOrigin)
        val left = origin[0] - panelOrigin[0]
        val top = origin[1] - panelOrigin[1]
        state.overlay.measure(exact(state.top.width), exact(total))
        state.overlay.layout(left, top, left + state.top.width, top + total)
        place(state.shortcuts, fixedLeft, square + gap, square, square)
        val cell = (square - gap) / 2
        place(state.buttons.getValue("rotation"), 0, 0, cell, cell)
        place(state.buttons.getValue("silent"), cell + gap, 0, cell, cell)
        place(state.buttons.getValue("bedtime"), 0, cell + gap, square, cell)
        syncTileBackgrounds(state)

        // Reparent the native host, not just its drawing, so transport controls receive touches.
        // The native slider row can be wider than the connectivity column.
        // Anchor the square to the available right edge instead of clipping it.
        val mediaLeft = sliderLeft.coerceIn(0, (state.overlay.width - square).coerceAtLeast(0))
        moveMediaToOverlay(state, mediaLeft, square)
        XposedHelpers.setAdditionalInstanceField(state.media, "$STATE.mediaActive", true)
        fitMediaFrame(state.media)
        positionMediaActions(state.media)
        arrangeMediaContent(state.media)
    }

    private fun activeMediaHost(view: View): View? {
        var ancestor: View? = view
        while (ancestor != null) {
            if (XposedHelpers.getAdditionalInstanceField(ancestor, "$STATE.mediaActive") == true) return ancestor
            ancestor = ancestor.parent as? View
        }
        return null
    }

    private fun resourceName(view: View): String =
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")

    private data class MediaFrame(
        val paddingLeft: Int, val paddingTop: Int, val paddingRight: Int, val paddingBottom: Int,
        val clip: android.graphics.Rect?, val clipToOutline: Boolean,
        val scaleX: Float, val scaleY: Float, val clipToPadding: Boolean?
    )

    private fun fitMediaFrame(host: View) {
        if (host.width <= 0 || host.height <= 0) return
        val fittingKey = "$STATE.fittingMedia"
        if (XposedHelpers.getAdditionalInstanceField(host, fittingKey) == true) return
        // Native carousel/card measurements can retain the old wide aspect ratio,
        // horizontal insets and animation clip even after the host is made square.
        // Resize only the wrappers leading to the card, not its transport controls.
        fun cardPath(view: View): List<View>? {
            if (view.visibility == View.GONE) return null
            if (resourceName(view) == "root_view") return listOf(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                cardPath(view.getChildAt(i))?.let { return listOf(view) + it }
            }
            return null
        }
        val path = cardPath(host) ?: return
        XposedHelpers.setAdditionalInstanceField(host, fittingKey, true)
        try {
            for (view in path) {
                val key = "$STATE.originalMediaFrame"
                if (XposedHelpers.getAdditionalInstanceField(view, key) == null) {
                    rememberMediaLayout(view)
                    XposedHelpers.setAdditionalInstanceField(view, key, MediaFrame(
                        view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom,
                        view.clipBounds?.let { android.graphics.Rect(it) }, view.clipToOutline,
                        view.scaleX, view.scaleY, (view as? ViewGroup)?.clipToPadding
                    ))
                }
                // Keep the card's own outline/radius, but remove stale wrapper clips.
                view.clipBounds = null
                if (view !== path.last()) view.clipToOutline = false
                // The card keeps horizontal padding for its text/buttons. Its
                // full-bleed album/background must not be clipped to that padding:
                // on the device this removed 21 px from each side of a 443 px card.
                if (view is ViewGroup) view.clipToPadding = false
                view.scaleX = 1f
                view.scaleY = 1f
                if (view !== path.last() &&
                    (view.paddingLeft != 0 || view.paddingTop != 0 || view.paddingRight != 0 || view.paddingBottom != 0)) {
                    view.setPadding(0, 0, 0, 0)
                }
                if (view !== host) {
                    view.translationX = 0f
                    view.translationY = 0f
                    if (view.measuredWidth != host.width || view.measuredHeight != host.height ||
                        view.left != 0 || view.top != 0 || view.width != host.width || view.height != host.height) {
                        place(view, 0, 0, host.width, host.height)
                    }
                }
            }
        } finally {
            XposedHelpers.setAdditionalInstanceField(host, fittingKey, false)
        }
    }

    private fun arrangeMediaContent(view: View) {
        if (view !is ViewGroup || view.width <= 0 || view.height <= 0) return
        if (resourceName(view) == "root_view") {
            val inset = dp(view.context, 12)
            val controls = (0 until view.childCount).map { view.getChildAt(it) }
            val actions = controls.firstOrNull { resourceName(it) == "actions" }
            actions?.let { alignMediaActions(view, it) }
            val device = controls.firstOrNull { resourceName(it) == "media_seamless" }
            device?.let {
                rememberMediaLayout(it)
                val width = it.measuredWidth.coerceAtMost(view.width - inset * 2)
                it.translationX = 0f
                it.translationY = 0f
                it.layout(view.width - inset - width, inset, view.width - inset, inset + it.measuredHeight)
            }
            val title = controls.firstOrNull { resourceName(it) in setOf("icon_songname", "song_name") }
            val artist = controls.firstOrNull { resourceName(it) == "artist" }
            val titleHeight = title?.measuredHeight ?: 0
            val artistHeight = artist?.takeIf { it.visibility != View.GONE }?.measuredHeight ?: 0
            val spacing = dp(view.context, 6)
            val infoHeight = titleHeight + if (artistHeight > 0) spacing + artistHeight else 0
            val safeTop = (device?.bottom ?: inset) + spacing
            val safeBottom = actions?.let { it.bottom + it.translationY.toInt() - it.height - spacing }
                ?: (view.height - inset)
            val infoTop = ((view.height - infoHeight) / 2).coerceIn(safeTop, safeBottom.coerceAtLeast(safeTop))
            fun centerText(child: View, top: Int) {
                rememberMediaLayout(child)
                child.translationX = 0f
                child.translationY = 0f
                child.measure(exact(view.width - inset * 2), exact(child.measuredHeight))
                child.layout(inset, top, view.width - inset, top + child.measuredHeight)
                if (child is TextView) child.gravity = Gravity.CENTER
                if (child is LinearLayout) {
                    child.gravity = Gravity.CENTER
                    for (i in 0 until child.childCount) {
                        (child.getChildAt(i) as? TextView)?.let { text ->
                            rememberMediaLayout(text)
                            text.gravity = Gravity.CENTER
                            if (text.maxLines != 1) text.maxLines = 1
                            if (text.ellipsize != android.text.TextUtils.TruncateAt.END) {
                                text.ellipsize = android.text.TextUtils.TruncateAt.END
                            }
                        }
                    }
                }
            }
            title?.let { centerText(it, infoTop) }
            artist?.let { centerText(it, infoTop + titleHeight + spacing) }
            for (child in controls) {
                if (resourceName(child) in setOf("album_art_container", "touch_ripple_view")) {
                    rememberMediaLayout(child)
                    child.translationX = 0f
                    child.translationY = 0f
                    place(child, 0, 0, view.width, view.height)
                }
            }
        }
        for (index in 0 until view.childCount) arrangeMediaContent(view.getChildAt(index))
    }

    private data class MediaLayout(
        val left: Int, val top: Int, val right: Int, val bottom: Int,
        val x: Float, val y: Float, val gravity: Int?, val maxLines: Int?,
        val ellipsize: android.text.TextUtils.TruncateAt?
    )

    private fun rememberMediaLayout(view: View) {
        val key = "$STATE.originalMediaLayout"
        if (XposedHelpers.getAdditionalInstanceField(view, key) != null) return
        XposedHelpers.setAdditionalInstanceField(view, key, MediaLayout(
            view.left, view.top, view.right, view.bottom, view.translationX, view.translationY,
            when (view) { is TextView -> view.gravity; is LinearLayout -> view.gravity; else -> null },
            (view as? TextView)?.maxLines, (view as? TextView)?.ellipsize
        ))
    }

    private fun syncTileBackgrounds(state: State) {
        fun findTile(view: View): View? {
            if (view.javaClass.name.contains("QSTileView") && !view.javaClass.name.contains("Fixed")) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) findTile(view.getChildAt(i))?.let { return it }
            return null
        }
        val source = findTile(state.scroll) ?: return
        state.buttons.values.forEach { it.syncBackground(source) }
    }

    private fun positionMediaActions(view: View) {
        if (view !is ViewGroup) return
        val id = view.resources.getIdentifier("actions", "id", "com.android.systemui")
        for (index in 0 until view.childCount) {
            val child = view.getChildAt(index)
            if (id != 0 && child.id == id) {
                if (XposedHelpers.getAdditionalInstanceField(child, "$STATE.originalTranslationY") == null) {
                    XposedHelpers.setAdditionalInstanceField(child, "$STATE.originalTranslationY", child.translationY)
                }
                alignMediaActions(view, child)
                val key = "$STATE.actionsListener"
                if (XposedHelpers.getAdditionalInstanceField(view, key) == null) {
                    XposedHelpers.setAdditionalInstanceField(view, key, true)
                    view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        runCatching {
                            var ancestor: View? = view
                            var active = false
                            while (ancestor != null) {
                                if (XposedHelpers.getAdditionalInstanceField(ancestor, "$STATE.mediaActive") == true) {
                                    active = true
                                    break
                                }
                                ancestor = ancestor.parent as? View
                            }
                            if (enabled() && active) alignMediaActions(view, child)
                        }.onFailure { report("Positioning media transport controls", it) }
                    }
                }
            } else positionMediaActions(child)
        }
    }

    private fun alignMediaActions(parent: ViewGroup, actions: View) {
        if (parent.height <= 0 || actions.height <= 0) return
        val bottomInset = dp(parent.context, 24)
        actions.translationY = (parent.height - parent.paddingBottom - bottomInset - actions.bottom).toFloat()
    }

    private fun moveMediaToOverlay(state: State, left: Int, square: Int) {
        state.media.translationX = 0f
        state.media.translationY = 0f
        if (state.media.parent !== state.overlay) {
            val parent = state.media.parent as? ViewGroup ?: return
            val index = parent.indexOfChild(state.media)
            val params = state.media.layoutParams
            runCatching {
                parent.removeView(state.media)
                state.overlay.addView(state.media, FrameLayout.LayoutParams(square, square))
            }.onFailure {
                if (state.media.parent == null) parent.addView(state.media, index, params)
                throw it
            }
        }
        place(state.media, left, 0, square, square)
    }

    private fun restore(panel: ViewGroup, state: State) {
        if (!state.active) return
        XposedHelpers.setAdditionalInstanceField(state.media, "$STATE.mediaActive", false)
        restoreMediaActions(state.media)
        if (state.media.parent === state.overlay) {
            state.overlay.removeView(state.media)
            state.mediaParent.addView(
                state.media, state.mediaIndex.coerceAtMost(state.mediaParent.childCount), state.mediaParams
            )
        }
        state.overlay.visibility = View.GONE
        state.buttons.values.forEach { it.pause() }
        state.originalHeights.forEach { (view, value) -> height(view, value) }
        state.originalWidths.forEach { (view, value) ->
            view.layoutParams = view.layoutParams.apply { width = value }
        }
        state.originalGravities.forEach { (view, value) ->
            (view.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.gravity = value
                view.layoutParams = it
            }
        }
        XposedHelpers.setIntField(panel, "mFixedTileAndSliderContainerHeight", state.originalContainerHeight)
        XposedHelpers.setIntField(panel, "mUnfixedTileScrollViewTopMarin", state.originalScrollTop)
        (state.scroll.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = state.originalScrollTop
            state.scroll.layoutParams = it
        }
        state.active = false
        state.geometry = emptyList()
    }

    private fun restoreMediaActions(view: View) {
        (XposedHelpers.getAdditionalInstanceField(view, "$STATE.originalMediaFrame") as? MediaFrame)?.let {
            view.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom)
            view.clipBounds = it.clip
            view.clipToOutline = it.clipToOutline
            view.scaleX = it.scaleX
            view.scaleY = it.scaleY
            if (view is ViewGroup) it.clipToPadding?.let { value -> view.clipToPadding = value }
            XposedHelpers.removeAdditionalInstanceField(view, "$STATE.originalMediaFrame")
        }
        (XposedHelpers.getAdditionalInstanceField(view, "$STATE.originalMediaLayout") as? MediaLayout)?.let {
            view.translationX = it.x
            view.translationY = it.y
            view.layout(it.left, it.top, it.right, it.bottom)
            if (view is TextView) {
                it.gravity?.let { value -> view.gravity = value }
                it.maxLines?.let { value -> view.maxLines = value }
                view.ellipsize = it.ellipsize
            } else if (view is LinearLayout) it.gravity?.let { value -> view.gravity = value }
            XposedHelpers.removeAdditionalInstanceField(view, "$STATE.originalMediaLayout")
        }
        (XposedHelpers.getAdditionalInstanceField(view, "$STATE.originalTranslationY") as? Float)?.let {
            view.translationY = it
            XposedHelpers.removeAdditionalInstanceField(view, "$STATE.originalTranslationY")
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) restoreMediaActions(view.getChildAt(index))
    }

    /** Called before the native controller builds lower tiles. Remove only successfully bound tiles. */
    fun bindTiles(controller: Any): Set<String> {
        val container = field(controller, "mView") ?: return emptySet()
        val panel = field(container, "mQSModernPanel") as? ViewGroup ?: return emptySet()
        val state = prepare(panel) ?: return emptySet()
        val bound = mutableSetOf<String>()
        for ((spec, button) in state.buttons) {
            runCatching {
                button.bindControl()
                bound.add(spec)
            }.onFailure { report("Binding $spec control-center tile", it) }
        }
        return bound
    }

    private class TileButton(context: Context, private val spec: String) : LinearLayout(context) {
        private val mainHandler = Handler(Looper.getMainLooper())
        private var ringerReceiverRegistered = false
        private var previousRingerMode = AudioManager.RINGER_MODE_NORMAL
        private val ringerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                runCatching { renderSilent() }.onFailure { report("Updating silent switch", it) }
            }
        }
        private var rotationObserverRegistered = false
        private val rotationObserver = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                runCatching { renderRotation() }.onFailure { report("Updating automatic rotation", it) }
            }
        }
        private var bedtimeObserverRegistered = false
        private var bedtimeReceiverRegistered = false
        private val bedtimeObserver = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                runCatching { bindBedtime() }.onFailure { report("Updating bedtime mode", it) }
            }
        }
        private val bedtimeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                runCatching { bindBedtime() }.onFailure { report("Updating bedtime mode", it) }
            }
        }
        private val icon = ImageView(context)
        private val label = TextView(context)
        private val shape = GradientDrawable().apply { cornerRadius = dp(context, 24).toFloat() }
        private var nativeSource: View? = null
        private var reportedStyleFailure = false

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            // Native blur/layer drawables may contain a white mask and depend on
            // their original view. Reuse the palette, not that drawable instance.
            background = RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x26FFFFFF), shape,
                GradientDrawable().apply {
                    cornerRadius = dp(context, 24).toFloat()
                    setColor(Color.WHITE)
                }
            )
            isClickable = true
            isFocusable = true
            addView(icon, LayoutParams(dp(context, 30), dp(context, 30)))
            label.setTextColor(Color.WHITE)
            label.textSize = 14f
            if (spec == "bedtime") addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(context, 10)
            })
            setPadding(dp(context, 12), 0, dp(context, 12), 0)
            shape.setColor(0x40303030)
            isEnabled = false
            setOnClickListener {
                runCatching {
                    if (!enabled()) return@runCatching
                    if (spec == "bedtime") {
                        toggleBedtime()
                    } else if (spec == "rotation") {
                        val resolver = context.contentResolver
                        val autoRotate = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0) != 0
                        if (autoRotate) {
                            // Lock the current orientation, rather than returning to portrait.
                            val rotation = display?.rotation ?: 0
                            check(Settings.System.putInt(resolver, Settings.System.USER_ROTATION, rotation))
                        }
                        check(Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, if (autoRotate) 0 else 1))
                        renderRotation()
                    } else if (spec == "silent") {
                        val manager = context.getSystemService(AudioManager::class.java)
                        val currentMode = XposedHelpers.callMethod(manager, "getRingerModeInternal") as Int
                        val nextMode = if (currentMode == AudioManager.RINGER_MODE_SILENT) {
                            previousRingerMode
                        } else {
                            previousRingerMode = currentMode
                            AudioManager.RINGER_MODE_SILENT
                        }
                        // Use SystemUI's internal ringer API, independently of Zen/bedtime mode.
                        XposedHelpers.callMethod(manager, "setRingerModeInternal", nextMode)
                        renderSilent()
                    }
                }
                    .onFailure { report("Clicking $spec", it) }
            }
        }

        fun bindControl() {
            when (spec) {
                "bedtime" -> bindBedtime()
                "rotation" -> {
                    renderRotation()
                    if (isAttachedToWindow && !rotationObserverRegistered) {
                        context.contentResolver.registerContentObserver(
                            Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, rotationObserver
                        )
                        rotationObserverRegistered = true
                    }
                }
                "silent" -> {
                    if (isAttachedToWindow && !ringerReceiverRegistered) {
                        context.registerReceiver(ringerReceiver, IntentFilter().apply {
                            addAction("android.media.INTERNAL_RINGER_MODE_CHANGED_ACTION")
                            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
                        }, null, mainHandler, Context.RECEIVER_EXPORTED)
                        ringerReceiverRegistered = true
                    }
                    renderSilent()
                }
            }
        }

        private fun renderRotation() {
            isEnabled = true
            isSelected = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) != 0
            alpha = 1f
            setControlLabel("quick_settings_rotation_unlocked_label", "自動回転")
            setControlIcon(listOf("ic_qs_auto_rotate", "ic_qs_rotation_unlocked", "ic_qs_auto_rotate_animation"))
            updateBackground()
        }

        private fun renderSilent() {
            val manager = context.getSystemService(AudioManager::class.java)
            val mode = XposedHelpers.callMethod(manager, "getRingerModeInternal") as Int
            isEnabled = !manager.isVolumeFixed
            isSelected = mode == AudioManager.RINGER_MODE_SILENT
            if (!isSelected) previousRingerMode = mode
            alpha = if (isEnabled) 1f else 0.4f
            label.text = if (resources.configuration.locales[0].language == "ja")
                "サイレントスイッチ" else "Silent switch"
            contentDescription = label.text
            stateDescription = if (isSelected) "ON" else "OFF"
            setControlIcon(if (isSelected) listOf("ic_qs_silent_on", "ic_volume_ringer_mute")
                else listOf("ic_qs_silent_off", "ic_volume_ringer"))
            updateBackground()
        }

        private fun setControlLabel(name: String, fallback: String) {
            val id = resources.getIdentifier(name, "string", "com.android.systemui")
            label.text = if (id != 0) resources.getString(id) else fallback
            contentDescription = label.text
            stateDescription = if (isSelected) "ON" else "OFF"
        }

        private fun setControlIcon(names: List<String>) {
            val id = names.firstNotNullOfOrNull {
                resources.getIdentifier(it, "drawable", "com.android.systemui").takeIf { value -> value != 0 }
            }
            icon.setImageDrawable(if (id != null) context.getDrawable(id)?.mutate() else ShortcutDrawable(spec, isSelected))
        }

        private fun findBedtime(manager: NotificationManager): Map.Entry<String, AutomaticZenRule>? {
            return manager.automaticZenRules.entries.firstOrNull {
                val rule = it.value
                // Match the actual Wellbeing rule, never a localized label or generic DND.
                rule.conditionId?.toString() == "condition://com.google.android.apps.wellbeing.azr/winddown"
            }
        }

        private fun bedtimeActive(manager: NotificationManager, id: String): Boolean {
            val config = XposedHelpers.callMethod(manager, "getZenModeConfig") ?: return false
            val rules = field(config, "automaticRules") as? Map<*, *> ?: return false
            val rule = rules[id] ?: return false
            if (!XposedHelpers.getBooleanField(rule, "enabled")) return false
            // Android 16 stores manual invocation separately from the provider's
            // condition. In particular, an overridden-off rule may still be TRUE.
            return when (XposedHelpers.getIntField(rule, "conditionOverride")) {
                1 -> true // OVERRIDE_ACTIVATE
                2 -> false // OVERRIDE_DEACTIVATE
                else -> (field(rule, "condition") as? Condition)?.state == Condition.STATE_TRUE
            }
        }

        private fun toggleBedtime() {
            val manager = context.getSystemService(NotificationManager::class.java)
            val mode = findBedtime(manager) ?: return
            val active = bedtimeActive(manager, mode.key)
            // Same user-invocation API as the standard Modes panel. Do not change
            // the rule's enabled flag (schedule), policy or configured device effects.
            manager.setAutomaticZenRuleState(mode.key, Condition(
                mode.value.conditionId, "", if (active) Condition.STATE_FALSE else Condition.STATE_TRUE,
                Condition.SOURCE_USER_ACTION
            ))
            post { runCatching { bindBedtime() }.onFailure { report("Refreshing bedtime mode", it) } }
        }

        fun bindBedtime() {
            val manager = context.getSystemService(NotificationManager::class.java)
            val mode = findBedtime(manager)
            isEnabled = mode != null
            isSelected = mode != null && bedtimeActive(manager, mode.key)
            alpha = if (isEnabled) 1f else 0.4f
            label.text = if (resources.configuration.locales[0].language == "ja")
                "おやすみ時間" else "Bedtime"
            contentDescription = if (resources.configuration.locales[0].language == "ja")
                "おやすみ時間モード" else "Bedtime mode"
            stateDescription = if (isSelected) "ON" else "OFF"
            if (icon.drawable == null) icon.setImageDrawable(MoonDrawable())
            updateBackground()
            if (isAttachedToWindow && !bedtimeObserverRegistered) {
                // A different mode may keep global Zen unchanged. Observe config
                // revisions too, so only this bedtime rule determines selection.
                context.contentResolver.registerContentObserver(Settings.Global.getUriFor("zen_mode_config_etag"), false, bedtimeObserver)
                bedtimeObserverRegistered = true
            }
            if (isAttachedToWindow && !bedtimeReceiverRegistered) {
                context.registerReceiver(bedtimeReceiver, IntentFilter().apply {
                    addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED)
                    addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
                    addAction(NotificationManager.ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED)
                }, Context.RECEIVER_EXPORTED)
                bedtimeReceiverRegistered = true
            }
        }

        fun syncBackground(source: View) {
            if (nativeSource !== source) {
                nativeSource = source
                reportedStyleFailure = false
            }
            updateBackground()
        }

        private fun updateBackground() {
            val source = nativeSource
            val palette = source?.let {
                runCatching {
                    val colorName = when {
                        !isEnabled -> "colorUnavailableUnfixed"
                        isSelected -> "colorActiveUnfixed"
                        else -> "colorInactiveUnfixed"
                    }
                    val labelName = when {
                        !isEnabled -> "colorLabelUnavailableUnFixed"
                        isSelected -> "colorLabelActiveUnFixed"
                        else -> "colorLabelInactiveUnFixed"
                    }
                    XposedHelpers.getIntField(it, colorName) to XposedHelpers.getIntField(it, labelName)
                }.onFailure {
                    if (!reportedStyleFailure) {
                        reportedStyleFailure = true
                        report("Reading native control-center tile colors", it)
                    }
                }.getOrNull()
            }
            val color = palette?.first ?: if (isSelected) 0xFF167AFF.toInt() else 0xD0303030.toInt()
            val foreground = palette?.second ?: Color.WHITE
            shape.setColor(color)
            icon.setColorFilter(foreground)
            label.setTextColor(foreground)
        }

        fun pause() = runCatching { stopListening() }
            .onFailure { report("Pausing $spec", it) }

        private fun stopListening() {
            if (ringerReceiverRegistered) {
                context.unregisterReceiver(ringerReceiver)
                ringerReceiverRegistered = false
            }
            if (rotationObserverRegistered) {
                context.contentResolver.unregisterContentObserver(rotationObserver)
                rotationObserverRegistered = false
            }
            if (bedtimeObserverRegistered) {
                context.contentResolver.unregisterContentObserver(bedtimeObserver)
                bedtimeObserverRegistered = false
            }
            if (bedtimeReceiverRegistered) {
                context.unregisterReceiver(bedtimeReceiver)
                bedtimeReceiverRegistered = false
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            runCatching {
                bindControl()
            }.onFailure { report("Attaching $spec", it) }
        }

        override fun onDetachedFromWindow() {
            runCatching {
                stopListening()
            }.onFailure { report("Detaching $spec", it) }
            super.onDetachedFromWindow()
        }
    }

    /** Fallback for ROMs whose native QS drawable resource names differ. */
    private class ShortcutDrawable(private val spec: String, private val selected: Boolean) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 2f
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
            if (spec == "silent") {
                val bell = android.graphics.Path().apply {
                    moveTo(5f, 17f); lineTo(7f, 14f); lineTo(7f, 9f)
                    cubicTo(7f, 2f, 17f, 2f, 17f, 9f)
                    lineTo(17f, 14f); lineTo(19f, 17f); close()
                }
                canvas.drawPath(bell, paint)
                canvas.drawArc(10f, 17f, 14f, 21f, 0f, 180f, false, paint)
                if (selected) canvas.drawLine(3f, 3f, 21f, 21f, paint)
            } else {
                canvas.save()
                canvas.rotate(-35f, 12f, 12f)
                canvas.drawRoundRect(8f, 5f, 16f, 19f, 1.5f, 1.5f, paint)
                canvas.restore()
                canvas.drawArc(2f, 2f, 22f, 22f, 195f, 125f, false, paint)
                canvas.drawArc(2f, 2f, 22f, 22f, 15f, 125f, false, paint)
                canvas.drawLine(20f, 3f, 20f, 7f, paint)
                canvas.drawLine(20f, 7f, 16f, 7f, paint)
                canvas.drawLine(4f, 21f, 4f, 17f, paint)
                canvas.drawLine(4f, 17f, 8f, 17f, paint)
            }
            canvas.restoreToCount(save)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private class MoonDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
            val moon = android.graphics.Path().apply {
                addCircle(12f, 12f, 9f, android.graphics.Path.Direction.CW)
                op(android.graphics.Path().apply {
                    addCircle(16f, 8f, 8f, android.graphics.Path.Direction.CW)
                }, android.graphics.Path.Op.DIFFERENCE)
            }
            canvas.drawPath(moon, paint)
            canvas.restoreToCount(save)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun height(view: View, value: Int) {
        val lp = view.layoutParams ?: return
        if (lp.height != value) {
            lp.height = value
            view.layoutParams = lp
        }
    }

    private fun place(view: View, left: Int, top: Int, width: Int, height: Int) {
        view.measure(exact(width), exact(height))
        view.layout(left, top, left + width, top + height)
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), View.MeasureSpec.EXACTLY)
    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun report(message: String, error: Throwable) = ErrorLogger.log(TAG, message, error)
}
