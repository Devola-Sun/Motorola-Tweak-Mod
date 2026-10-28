package com.motolauncher.mod.hooks

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import java.util.WeakHashMap
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/** Restyles real controls in place; never creates a media session or changes host placement. */
internal object LockMediaCardStyle {
    private val states = WeakHashMap<ViewGroup, State>()
    private val owners = WeakHashMap<View, WeakReference<ViewGroup>>()

    fun owner(view: View): ViewGroup? = owners[view]?.get()
    fun isApplied(root: ViewGroup): Boolean = states.containsKey(root)
    private val whiteTint = ColorStateList.valueOf(Color.WHITE)
    private val lightProgressTrack = ColorStateList.valueOf(SystemUiGlassPalette.progressTrackColor(true, true))
    private val coverOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, 6f * view.resources.displayMetrics.density)
        }
    }

    private class State(root: ViewGroup) {
        val background = root.background
        val tint = root.backgroundTintList
        val foreground = root.foreground
        val padding = Rect(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        var surface: Drawable? = null
        var liquid = false
        var blur = false
        var keyguard = false
        var night = false
        var seekDrawable: Drawable? = null
        var seekThumb: Drawable? = null
        var seekColor: Int? = null
        val children = WeakHashMap<View, Snapshot>()
    }

    private class Snapshot(view: View) {
        val bounds = Rect(view.left, view.top, view.right, view.bottom)
        val x = view.translationX
        val y = view.translationY
        val alpha = view.alpha
        val scaleX = view.scaleX
        val scaleY = view.scaleY
        val background = view.background
        val tint = view.backgroundTintList
        val foreground = view.foreground
        val padding = Rect(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
        val outline = view.outlineProvider
        val clip = view.clipToOutline
        val clipBounds = view.clipBounds?.let(::Rect)
        val clipChildren = (view as? ViewGroup)?.clipChildren
        val clipToPadding = (view as? ViewGroup)?.clipToPadding
        val textColors = (view as? TextView)?.textColors
        val textSize = (view as? TextView)?.textSize
        val maxLines = (view as? TextView)?.maxLines
        val ellipsize = (view as? TextView)?.ellipsize
        val gravity = (view as? TextView)?.gravity
        val imageTint = (view as? ImageView)?.imageTintList
        val scaleType = (view as? ImageView)?.scaleType
        val progressDrawable = (view as? SeekBar)?.progressDrawable
        val thumb = (view as? SeekBar)?.thumb
        val progressTint = (view as? SeekBar)?.progressTintList
        val progressBackgroundTint = (view as? SeekBar)?.progressBackgroundTintList
        val thumbTint = (view as? SeekBar)?.thumbTintList

        fun restore(view: View) {
            view.background = background
            view.backgroundTintList = tint
            view.foreground = foreground
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            view.outlineProvider = outline
            view.clipToOutline = clip
            view.clipBounds = clipBounds
            if (view is ViewGroup) clipChildren?.let { view.clipChildren = it }
            if (view is ViewGroup) clipToPadding?.let { view.clipToPadding = it }
            view.alpha = alpha
            view.translationX = x
            view.translationY = y
            view.scaleX = scaleX
            view.scaleY = scaleY
            view.measure(exact(bounds.width()), exact(bounds.height()))
            view.layout(bounds.left, bounds.top, bounds.right, bounds.bottom)
            if (view is TextView) {
                textColors?.let(view::setTextColor)
                textSize?.let { view.setTextSize(TypedValue.COMPLEX_UNIT_PX, it) }
                maxLines?.let { view.maxLines = it }
                view.ellipsize = ellipsize
                gravity?.let { view.gravity = it }
            }
            if (view is ImageView) {
                view.imageTintList = imageTint
                scaleType?.let { view.scaleType = it }
            }
            if (view is SeekBar) {
                view.progressDrawable = progressDrawable
                view.thumb = thumb
                view.progressTintList = progressTint
                view.progressBackgroundTintList = progressBackgroundTint
                view.thumbTintList = thumbTint
            }
        }
    }

    /** Missing/unsupported layouts are left untouched, rather than hiding working controls. */
    fun apply(root: ViewGroup, liquid: Boolean, blurAvailable: Boolean,
        forceKeyguardAppearance: Boolean = false, report: (Throwable) -> Unit): Boolean {
        val children = descendants(root).associateBy(::name)
        val album = children["album_art"] as? ImageView ?: return false
        val title = children["header_title"] as? TextView ?: return false
        val artist = children["header_artist"] as? TextView ?: return false
        val progress = children["media_progress_bar"] as? SeekBar ?: return false
        val play = children["actionPlayPause"] ?: return false
        // These are direct children in the AOSP TransitionLayout. Do not guess vendor layouts.
        if (listOf(album, title, artist, progress, play).any { it.parent !== root }) return false
        val d = root.resources.displayMetrics.density
        fun dp(value: Float) = (value * d).roundToInt()
        if (root.width <= 0 || root.height <= 0) {
            return false
        }
        val state = states.getOrPut(root) { State(root) }
        // Expanded island popups share the lock-screen material/radius, even when
        // opened over a light home screen. The actual host/lock state stays native.
        val keyguard = forceKeyguardAppearance || LockNotificationLayout.isKeyguard(root)
        val night = forceKeyguardAppearance || root.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val contentColor = SystemUiGlassPalette.primaryContentColor(night, liquid)
        val secondaryColor = SystemUiGlassPalette.secondaryContentColor(night, liquid)
        val trackColor = SystemUiGlassPalette.progressTrackColor(night, liquid)
        val contentTint = whiteTint
        val trackTint = lightProgressTrack
        if (state.surface == null || state.liquid != liquid || state.blur != blurAvailable ||
            state.keyguard != keyguard || state.night != night) {
            state.surface = LiquidGlassSurface.create(root, LiquidGlassSurface.cardRadius(root, keyguard), blurAvailable,
                keyguard = keyguard, night = night, liquid = liquid, report = report)
            state.liquid = liquid
            state.blur = blurAvailable
            state.keyguard = keyguard
            state.night = night
        }
        fun remember(view: View) {
            state.children.getOrPut(view) { Snapshot(view) }
            if (owners[view]?.get() !== root) owners[view] = WeakReference(root)
        }
        fun place(view: View, left: Int, top: Int, width: Int, height: Int,
            measure: Boolean = true, clearClip: Boolean = true) {
            remember(view)
            view.translationX = 0f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            // Native TransitionViewState can leave a clip from the original bounds
            // even after layout() has put the seek bar/buttons back in place.
            if (clearClip && view.clipBounds != null) view.clipBounds = null
            // Compare the measured size, not the laid-out size. TransitionLayout can
            // update the latter while scrubbing without changing the measurement.
            if (measure && (view.measuredWidth != width || view.measuredHeight != height)) {
                view.measure(exact(width), exact(height))
            }
            // Calling layout on an unchanged LinearLayout re-enters its children
            // whenever native media binding requests layout, producing a draw loop.
            if (view.left != left || view.top != top || view.right != left + width ||
                view.bottom != top + height) {
                view.layout(left, top, left + width, top + height)
            }
        }
        fun text(view: TextView, size: Float, color: Int, right: Boolean = false) {
            remember(view)
            if (view.currentTextColor != color) view.setTextColor(color)
            val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, root.resources.displayMetrics)
            if (view.textSize != px) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
            if (view.maxLines != 1) view.maxLines = 1
            if (view.ellipsize != TextUtils.TruncateAt.END) view.ellipsize = TextUtils.TruncateAt.END
            val gravity = Gravity.CENTER_VERTICAL or if (right) Gravity.END else Gravity.START
            if (view.gravity != gravity) view.gravity = gravity
        }

        if (root.background !== state.surface) {
            root.background = state.surface
            root.setPadding(state.padding.left, state.padding.top, state.padding.right, state.padding.bottom)
        }
        if (root.backgroundTintList != null) root.backgroundTintList = null
        if (root.foreground != null) root.foreground = null
        // Native playback effects are full-card overlays, not part of the wallpaper.
        // They can paint opaque black even after the root background has been replaced.
        for (id in listOf("touch_ripple_view", "turbulence_noise_view", "loading_effect_view",
            "media_background", "media_bg", "media_player_background", "album_art_background")) {
            children[id]?.let { effect ->
                remember(effect)
                if (effect.alpha != 0f) effect.alpha = 0f
            }
        }
        val inset = dp(12f)
        // Keep the layout active during collapsed/expanded native transitions.
        val compactHeight = root.height < dp(152f)
        val cover = dp(if (compactHeight) 36f else 44f)
        val infoLeft = inset + cover + dp(12f)
        val outputSize = dp(48f)
        val infoWidth = root.width - infoLeft - inset
        place(album, inset, inset, cover, cover)
        if (album.scaleType != ImageView.ScaleType.CENTER_CROP) album.scaleType = ImageView.ScaleType.CENTER_CROP
        if (album.imageTintList != null) album.imageTintList = null
        if (album.outlineProvider !== coverOutline) album.outlineProvider = coverOutline
        if (!album.clipToOutline) album.clipToOutline = true
        place(title, infoLeft, inset, infoWidth, dp(22f))
        text(title, 14f, contentColor)
        place(artist, infoLeft, inset + dp(22f), infoWidth, dp(18f))
        text(artist, 12f, secondaryColor)

        // The reference uses one full-width timeline, with times beside the track.
        val progressTop = maxOf(inset + cover, root.height - dp(88f))
        val timeWidth = dp(36f)
        val trackLeft = inset + timeWidth
        place(progress, trackLeft, progressTop, root.width - trackLeft - inset - timeWidth, dp(28f))
        if (progress.paddingLeft != 0 || progress.paddingRight != 0) progress.setPadding(0, 0, 0, 0)
        if (state.seekDrawable == null || state.seekColor != contentColor) {
            fun line(color: Int) = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(2f).toFloat()
                setColor(color)
                setSize(dp(1f), dp(3f))
            }
            state.seekDrawable = LayerDrawable(arrayOf(line(trackColor),
                ClipDrawable(line(contentColor), Gravity.LEFT, ClipDrawable.HORIZONTAL))).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
                for (index in 0..1) {
                    setLayerHeight(index, dp(3f))
                    setLayerGravity(index, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL)
                }
            }
            state.seekThumb = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(contentColor)
                setSize(dp(5f), dp(5f))
            }
            state.seekColor = contentColor
        }
        if (progress.progressDrawable !== state.seekDrawable) progress.progressDrawable = state.seekDrawable
        if (progress.thumb !== state.seekThumb) progress.thumb = state.seekThumb
        if (progress.progressTintList !== contentTint) progress.progressTintList = contentTint
        if (progress.progressBackgroundTintList !== trackTint) progress.progressBackgroundTintList = trackTint
        if (progress.thumbTintList !== contentTint) progress.thumbTintList = contentTint
        for ((id, right) in listOf("media_elapsed_time" to false, "media_total_time" to true,
            "media_scrubbing_elapsed_time" to false, "media_scrubbing_total_time" to true)) {
            val time = children[id] as? TextView ?: continue
            if (time.parent !== root) continue
            place(time, if (right) root.width - inset - timeWidth else inset,
                progressTop, timeWidth, dp(28f))
            text(time, 9f, secondaryColor, right)
        }

        val actionSize = dp(48f)
        val actionTop = root.height - actionSize - dp(8f)
        val actionNames = listOf("actionPrev", "actionPlayPause", "actionNext", "action0", "action1", "action2", "action3", "action4")
        // Scrubbing fades/hides previous/next actions. Their native alpha/visibility
        // must not change the slot count and move the remaining playback controls.
        val actions = actionNames.mapNotNull { children[it] }.filter { it.parent === root }.distinct()
        val semanticActions = actions.filter { name(it) in setOf("actionPrev", "actionPlayPause", "actionNext") }
        val customActions = actions.filter { name(it).startsWith("action") &&
            name(it) !in setOf("actionPrev", "actionPlayPause", "actionNext") &&
            (it.visibility != View.GONE || (it as? ImageView)?.drawable != null) }
        // Reserve the trailing slot for the real output picker. Do not invent a
        // favourite action if the media session does not offer one.
        val actionSlots = MediaControlGeometry.referenceActions(root.width, inset, actionSize,
            semanticActions.map(::name), customActions.map(::name))
        actions.forEach { action ->
            val slot = actionSlots[name(action)] ?: return@forEach
            place(action, slot.left, actionTop + (actionSize - slot.size) / 2, slot.size, slot.size)
            if (action.background != null) action.background = null
            val padding = dp(if (name(action) == "actionPlayPause") 8f else 12f)
            if (action.paddingLeft != padding || action.paddingTop != padding ||
                action.paddingRight != padding || action.paddingBottom != padding) {
                action.setPadding(padding, padding, padding, padding)
            }
            if (action is ImageView) {
                if (action.imageTintList !== contentTint) action.imageTintList = contentTint
                if (action.scaleType != ImageView.ScaleType.FIT_CENTER) action.scaleType = ImageView.ScaleType.FIT_CENTER
            }
        }
        (children["icon"] as? ImageView)?.takeIf { it.parent === root }?.let {
            // App badge stays with the cover, leaving a full header touch target for output.
            place(it, inset + cover - dp(18f), inset + cover - dp(18f), dp(18f), dp(18f))
        }
        children["media_seamless"]?.takeIf { it.parent === root }?.let { output ->
            val slot = MediaControlGeometry.referenceOutput(root.width, inset, outputSize, customActions.size)
            val size = slot.size
            // Change real hit bounds as well as pixels. Do not remeasure the native
            // text-bearing LinearLayout: that can create a scrubbing layout loop.
            place(output, slot.left, actionTop + (actionSize - size) / 2, size, size,
                measure = false, clearClip = false)
            val clip = Rect(0, 0, size, size)
            if (output.clipBounds != clip) output.clipBounds = clip
            val outputLayers = listOf(output) + if (output is ViewGroup) descendants(output) else emptyList()
            outputLayers.forEach { layer ->
                remember(layer)
                if (layer.background != null) layer.background = null
                if (layer.backgroundTintList != null) layer.backgroundTintList = null
                if (layer.foreground != null) layer.foreground = null
                if (layer is TextView && layer.alpha != 0f) layer.alpha = 0f
            }
            // Preserve the native click listener and accessibility description.
            (children["media_seamless_image"] as? ImageView)?.let { image ->
                val parents = mutableListOf<ViewGroup>()
                var parent = image.parent as? ViewGroup
                while (parent != null && parent !== output) {
                    parents.add(parent)
                    parent = parent.parent as? ViewGroup
                }
                // Lay out outside-in: an outer LinearLayout otherwise immediately
                // overwrites the inner bounds we just corrected. Native pill padding
                // and outline clipping must not crop the new square icon target.
                (listOf(output) + parents.asReversed() + image).forEach { layer ->
                    remember(layer)
                    if (layer.paddingLeft != 0 || layer.paddingTop != 0 ||
                        layer.paddingRight != 0 || layer.paddingBottom != 0) layer.setPadding(0, 0, 0, 0)
                    if (layer.clipToOutline) layer.clipToOutline = false
                    if (layer !== output && layer.clipBounds != null) layer.clipBounds = null
                    if (layer is ViewGroup && layer.clipToPadding) layer.clipToPadding = false
                }
                parents.asReversed().forEach { place(it, 0, 0, size, size, measure = false) }
                // ImageView needs a matching measured size and FIT_CENTER; retaining
                // the pill's matrix/measurement can cut off wide cast/device icons.
                if (image.scaleType != ImageView.ScaleType.FIT_CENTER) image.scaleType = ImageView.ScaleType.FIT_CENTER
                val imageSize = minOf(dp(24f), size)
                place(image, (size - imageSize) / 2, (size - imageSize) / 2, imageSize, imageSize)
                if (image.imageTintList !== contentTint) image.imageTintList = contentTint
            }
        }
        return true
    }

    fun restore(root: ViewGroup) {
        val state = states.remove(root) ?: return
        state.children.forEach { (view, snapshot) ->
            owners.remove(view)
            snapshot.restore(view)
        }
        if (root.background === state.surface) {
            root.background = state.background
            root.backgroundTintList = state.tint
            root.setPadding(state.padding.left, state.padding.top, state.padding.right, state.padding.bottom)
        }
        if (root.foreground == null) root.foreground = state.foreground
        // Recalculate native TransitionLayout constraints before the next surface is drawn.
        root.requestLayout()
    }

    private fun descendants(root: ViewGroup): List<View> = buildList {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            add(child)
            if (child is ViewGroup) addAll(descendants(child))
        }
    }

    private fun name(view: View): String =
        if (view.id <= 0 || (view.id ushr 24) == 0) "" else view.resources.getResourceEntryName(view.id)

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), View.MeasureSpec.EXACTLY)
}
