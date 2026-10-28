package com.motolauncher.mod.hooks

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.type.java.FloatType
import com.highcapable.yukihookapi.hook.type.java.IntType
import com.motolauncher.mod.HookConfig
import com.motolauncher.mod.ErrorLogger
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Shows and sizes Launcher3's Hotseat when the cover-dock option is enabled. */
object CoverDockHook : YukiBaseHooker() {

    private const val TAG = "MotoLauncherMod"
    private const val CLASS_HOTSEAT = "com.android.launcher3.Hotseat"
    private const val CLASS_DEVICE_PROFILE = "com.android.launcher3.DeviceProfile"

    /** Semi-transparent dark gray for the dynamically generated Dock background. */
    private val DOCK_BACKGROUND_COLOR: Int = Color.argb(153, 30, 30, 34)
    private const val DOCK_CORNER_RADIUS_DP = 24f
    private const val ISLAND_CORNER_RADIUS_DP = 28f

    /**
     * Dynamic Floating Island side insets (dp) optimized per icon column count:
     * - <= 4 columns: 18dp
     * - 5 columns: 12dp
     * - >= 6 columns: 8dp
     */
    private const val ISLAND_SIDE_INSET_4_COL_DP = 18f
    private const val ISLAND_SIDE_INSET_5_COL_DP = 12f
    private const val ISLAND_SIDE_INSET_6_COL_DP = 8f

    /**
     * Internal horizontal padding (dp) between the Dock background edge and the
     * CellLayout / ShortcutAndWidgetContainer content area on the Cover Screen.
     * Replaces Moto's oversized stock `hotseatLayoutPadding.left/right` so
     * 5-column and 6-column docks do not suffer from double horizontal padding.
     */
    private const val CONTENT_SIDE_PADDING_4_COL_DP = 8f
    private const val CONTENT_SIDE_PADDING_5_COL_DP = 4f
    private const val CONTENT_SIDE_PADDING_6_COL_DP = 2f

    /**
     * Maximum inter-cell horizontal gap (`mBorderSpace.x`, dp) on the Cover
     * Screen so 5/6 cells retain generous width and even spacing.
     */
    private const val MAX_BORDER_SPACE_4_COL_DP = 10f
    private const val MAX_BORDER_SPACE_5_COL_DP = 4f
    private const val MAX_BORDER_SPACE_6_COL_DP = 2f

    private const val CLASS_SHORTCUT_AND_WIDGET_CONTAINER =
        "com.android.launcher3.ShortcutAndWidgetContainer"

    /**
     * Screen-width threshold (px & dp) separating Cover Screen from Inner Screen.
     * - Cover Screen width: 1080px (411dp) on Razr Fold, 1056..1272px on Razr Flip.
     * - Inner Screen width: 2232px portrait / 2484px landscape (850dp / 946dp).
     */
    private const val INNER_SCREEN_MIN_WIDTH_PX = 1500
    private const val INNER_SCREEN_MIN_WIDTH_DP = 600

    private enum class ScreenState {
        UNINITIALIZED,
        COVER,
        INNER
    }

    private data class OriginalViewState(
        val paddingStart: Int,
        val paddingTop: Int,
        val paddingEnd: Int,
        val paddingBottom: Int,
        val background: Drawable?,
        val clipToOutline: Boolean,
        val willNotDraw: Boolean,
        val layoutHeight: Int
    )

    private data class OriginalClipState(
        val clipChildren: Boolean,
        val clipToPadding: Boolean
    )

    /**
     * Reentrant guard per thread so that View mutations inside a hook callback
     * (`setVisibility`, `setPaddingRelative`, `background = ...`, etc.) can
     * never re-enter our hooks and cause a `StackOverflowError` or layout storm.
     */
    private val reentrantGuard = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }

    private inline fun withReentrantGuard(block: () -> Unit) {
        if (reentrantGuard.get() == true) return
        reentrantGuard.set(true)
        try {
            block()
        } catch (t: Throwable) {
            try {
                Log.w(TAG, "CoverDockHook suppressed error", t)
            } catch (_: Throwable) {
            }
        } finally {
            reentrantGuard.set(false)
        }
    }

    @Volatile
    private var cachedIsBgVisibleMethod: Method? = null

    @Volatile
    private var isBgVisibleMethodResolved: Boolean = false

    /**
     * [GradientDrawable] whose horizontal and vertical bounds are dynamically
     * scaled and centered according to [widthPercent] (50%..100%) and
     * [heightPercent] (50%..200%) without altering the Hotseat View frame or
     * icon placement.
     */
    private class CenteredDockGradientDrawable(hostView: View) : GradientDrawable() {
        private val hostRef = WeakReference(hostView)

        var widthPercent: Int = HookConfig.DEFAULT_COVER_DOCK_WIDTH_PERCENT
            private set
        var heightPercent: Int = HookConfig.DEFAULT_COVER_DOCK_HEIGHT_PERCENT
            private set

        private var contentTopInset: Int = 0
        private var contentBottomInset: Int = 0
        private var baseCornerRadiusPx: Float = 0f
        private var minimumHorizontalInset: Int = 0
        private var frostAppearance: Boolean? = null
        private var borderWidth: Int = 0
        private var liquidAppearance = false
        private var liquidSurface: Drawable? = null
        private var liquidRadius = -1f
        private var liquidNight: Boolean? = null

        fun updateAppearance(frost: Boolean, liquid: Boolean, density: Float): Boolean {
            val strokeWidth = if (frost) density.roundToInt().coerceAtLeast(1) else 0
            if (frostAppearance == frost && borderWidth == strokeWidth && liquidAppearance == liquid) return false
            liquidAppearance = liquid
            if (!liquid) liquidSurface = null
            frostAppearance = frost
            borderWidth = strokeWidth
            // A surface approximation, not backdrop blur. RenderEffect on Hotseat
            // would blur its icons too, and cannot sample the wallpaper behind it.
            if (frost) {
                orientation = Orientation.TOP_BOTTOM
                colors = intArrayOf(Color.argb(150, 235, 240, 248), Color.argb(115, 100, 110, 125))
            } else {
                colors = null
                setColor(DOCK_BACKGROUND_COLOR)
            }
            setStroke(strokeWidth, if (frost) Color.argb(85, 255, 255, 255) else Color.TRANSPARENT)
            invalidateSelf()
            return true
        }

        private var rawLeft: Int = 0
        private var rawTop: Int = 0
        private var rawRight: Int = 0
        private var rawBottom: Int = 0
        private var hasRawBounds: Boolean = false

        fun updateDimensions(
            newWidthPercent: Int,
            newHeightPercent: Int,
            topInset: Int,
            bottomInset: Int,
            cornerRadiusPx: Float,
            sideInsetPx: Int = 0
        ): Boolean {
            val coercedWidth = HookConfig.coerceCoverDockWidthPercent(newWidthPercent)
            val coercedHeight = HookConfig.coerceCoverDockHeightPercent(newHeightPercent)
            val safeTopInset = topInset.coerceAtLeast(0)
            val safeBottomInset = bottomInset.coerceAtLeast(0)

            val changed = widthPercent != coercedWidth ||
                heightPercent != coercedHeight ||
                contentTopInset != safeTopInset ||
                contentBottomInset != safeBottomInset ||
                minimumHorizontalInset != sideInsetPx ||
                baseCornerRadiusPx != cornerRadiusPx

            widthPercent = coercedWidth
            heightPercent = coercedHeight
            contentTopInset = safeTopInset
            contentBottomInset = safeBottomInset
            baseCornerRadiusPx = cornerRadiusPx
            minimumHorizontalInset = sideInsetPx.coerceAtLeast(0)

            if (changed && hasRawBounds) {
                applyCenteredBounds(rawLeft, rawTop, rawRight, rawBottom)
                invalidateSelf()
            }
            return changed
        }

        override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
            val rawChanged = !hasRawBounds ||
                rawLeft != left ||
                rawTop != top ||
                rawRight != right ||
                rawBottom != bottom
            rawLeft = left
            rawTop = top
            rawRight = right
            rawBottom = bottom
            hasRawBounds = true
            if (rawChanged) {
                applyCenteredBounds(left, top, right, bottom)
            }
        }

        override fun getOutline(outline: Outline) {
            val b = bounds
            if (b.isEmpty) {
                outline.setRect(0, 0, 0, 0)
                return
            }
            val top = minOf(rawTop, b.top)
            val bottom = maxOf(rawBottom, b.bottom)
            outline.setRoundRect(b.left, top, b.right, bottom, cornerRadius)
        }

        override fun draw(canvas: Canvas) {
            val fullWidth = rawRight - rawLeft
            if (fullWidth >= INNER_SCREEN_MIN_WIDTH_PX) {
                return
            }
            val host = hostRef.get()
            if (host != null && resolveEffectiveState(host, fullWidth) != ScreenState.COVER) {
                return
            }
            if (liquidAppearance && host != null) {
                runCatching {
                    val night = LauncherGlassSurface.isNight(host)
                    if (liquidSurface == null || liquidRadius != cornerRadius || liquidNight != night) {
                        liquidSurface = LauncherGlassSurface.create(host, cornerRadius, liquid = true)
                        liquidRadius = cornerRadius
                        liquidNight = night
                    }
                    liquidSurface?.let {
                        it.bounds = bounds
                        it.alpha = alpha
                        it.draw(canvas)
                    }
                }.onFailure {
                    ErrorLogger.log(TAG, "draw cover Dock glass", it)
                    super.draw(canvas)
                }
            } else super.draw(canvas)
        }

        private fun applyCenteredBounds(left: Int, top: Int, right: Int, bottom: Int) {
            val fullWidth = (right - left).coerceAtLeast(0)
            if (fullWidth >= INNER_SCREEN_MIN_WIDTH_PX) {
                super.setBounds(0, 0, 0, 0)
                return
            }
            val horizontalInset = maxOf(
                computeHorizontalInset(fullWidth, widthPercent),
                minimumHorizontalInset
            ).coerceAtMost(fullWidth / 2)
            val centeredLeft = (left + horizontalInset).coerceAtMost(right)
            val centeredRight = (right - horizontalInset).coerceAtLeast(centeredLeft)

            val fullHeight = (bottom - top).coerceAtLeast(0)
            val useInsets = contentTopInset + contentBottomInset < fullHeight
            val refTop = if (useInsets) top + contentTopInset else top
            val refBottom = if (useInsets) bottom - contentBottomInset else bottom
            val baseHeight = (refBottom - refTop).coerceAtLeast(0)

            val targetHeight = (baseHeight * heightPercent / 100f)
                .roundToInt()
                .coerceAtLeast(0)
            val centerY = (refTop + refBottom) / 2f
            val centeredTop = (centerY - targetHeight / 2f).roundToInt()
            val centeredBottom = centeredTop + targetHeight

            if (baseCornerRadiusPx > 0f) {
                val maxRadius = (targetHeight / 2f).coerceAtLeast(0f)
                val targetRadius = if (maxRadius > 0f) {
                    baseCornerRadiusPx.coerceAtMost(maxRadius)
                } else {
                    baseCornerRadiusPx
                }
                if (cornerRadius != targetRadius) {
                    cornerRadius = targetRadius
                }
            }

            super.setBounds(centeredLeft, centeredTop, centeredRight, centeredBottom)
        }
    }

    private val originalStates = WeakHashMap<View, OriginalViewState>()
    private val generatedBackgrounds = WeakHashMap<View, CenteredDockGradientDrawable>()
    private val attachedListeners = WeakHashMap<View, View.OnAttachStateChangeListener>()
    private val originalClipStates = WeakHashMap<ViewGroup, OriginalClipState>()
    private val originalBorderSpaces = WeakHashMap<View, Point>()
    private val originalContainerPaddings = WeakHashMap<View, Rect>()
    private val dockScales = WeakHashMap<View, DockScaleState>()

    override fun onHook() {
        runCatching { hookVisibility() }
        runCatching { hookHotseatLifecycle() }
        runCatching { hookMeasure() }
        runCatching { hookScale() }.onFailure { ErrorLogger.log(TAG, "hook Dock scale", it) }
        runCatching { hookTouchRouting() }
    }

    private fun hookVisibility() {
        runCatching {
            findClass(CLASS_HOTSEAT).hook {
                injectMember {
                    method {
                        name = "setVisibility"
                        param(IntType)
                        superClass()
                    }
                    beforeHook {
                        withReentrantGuard {
                            if (!isHotseatInstance(instance)) return@withReentrantGuard
                            val hotseat = instance as? View ?: return@withReentrantGuard
                            if (resolveEffectiveState(hotseat) == ScreenState.COVER) {
                                args[0] = View.VISIBLE
                            }
                        }
                    }
                }
            }

            Log.i(TAG, "Hooked Hotseat.setVisibility")
        }.onFailure {
            Log.w(TAG, "Could not hook Hotseat.setVisibility", it)
        }
    }

    /**
     * Hooks Hotseat initialization, attachment, and inset updates so that:
     * 1. A dynamic rounded GradientDrawable background is added when missing/transparent.
     * 2. Parent containers (DragLayer / FrameLayout) have clipping disabled.
     */
    private fun hookHotseatLifecycle() {
        runCatching {
            val hotseatClass = findClass(CLASS_HOTSEAT)

            hotseatClass.hook {
                runCatching {
                    injectMember {
                        allConstructors()
                        afterHook {
                            withReentrantGuard {
                                val hotseat = instance as? View ?: return@withReentrantGuard
                                // Only register the attach listener in the constructor;
                                // do not mutate padding/background/parents while the View
                                // hierarchy and Workspace are still being constructed.
                                installAttachListener(hotseat)
                            }
                        }
                    }
                }.onFailure {
                    Log.w(TAG, "Could not hook Hotseat constructors", it)
                }

                runCatching {
                    injectMember {
                        method {
                            name = "onAttachedToWindow"
                            emptyParam()
                            superClass()
                        }
                        afterHook {
                            withReentrantGuard {
                                if (!isHotseatInstance(instance)) return@withReentrantGuard
                                val hotseat = instance as? View ?: return@withReentrantGuard
                                onHotseatAttached(hotseat)
                            }
                        }
                    }
                }.onFailure {
                    Log.w(TAG, "Could not hook Hotseat.onAttachedToWindow", it)
                }

                runCatching {
                    injectMember {
                        method {
                            name = "setInsets"
                            param(Rect::class.java)
                            superClass()
                        }
                        afterHook {
                            withReentrantGuard {
                                if (!isHotseatInstance(instance)) return@withReentrantGuard
                                val hotseat = instance as? View ?: return@withReentrantGuard

                                // Hotseat.setInsets has just applied the true stock padding
                                // and layoutParams.height for the current display. If we were
                                // already tracking this Hotseat, refresh the stored stock
                                // baseline so we never clobber Inner Screen insets with stale
                                // Cover Screen values.
                                synchronized(originalStates) {
                                    val existing = originalStates[hotseat]
                                    if (existing != null) {
                                        originalStates[hotseat] = existing.copy(
                                            paddingStart = hotseat.paddingStart,
                                            paddingTop = hotseat.paddingTop,
                                            paddingEnd = hotseat.paddingEnd,
                                            paddingBottom = hotseat.paddingBottom,
                                            layoutHeight = hotseat.layoutParams?.height
                                                ?: existing.layoutHeight
                                        )
                                    }
                                }

                                when (resolveEffectiveState(hotseat)) {
                                    ScreenState.UNINITIALIZED -> return@withReentrantGuard
                                    ScreenState.INNER -> {
                                        restoreOriginalState(
                                            hotseat,
                                            preserveCurrentPaddingAndHeight = true
                                        )
                                        return@withReentrantGuard
                                    }
                                    ScreenState.COVER -> {
                                        val prefs = modulePrefs
                                        val heightPercent = HookConfig.coverDockHeightPercent(prefs)
                                        val widthPercent = HookConfig.coverDockWidthPercent(prefs)
                                        originalState(hotseat)
                                        applyDockBackgroundAndPadding(
                                            hotseat = hotseat,
                                            heightPercent = heightPercent,
                                            widthPercent = widthPercent,
                                            allowLayoutMutation = true
                                        )
                                        unclipParentHierarchy(hotseat)
                                    }
                                }
                            }
                        }
                    }
                }.onFailure {
                    Log.w(TAG, "Could not hook Hotseat.setInsets", it)
                }

                runCatching {
                    injectMember {
                        method {
                            name = "onLayout"
                            paramCount = 5
                            superClass()
                        }
                        afterHook {
                            withReentrantGuard {
                                if (!isHotseatInstance(instance)) return@withReentrantGuard
                                val hotseat = instance as? View ?: return@withReentrantGuard
                                val left = args.getOrNull(1) as? Int
                                val right = args.getOrNull(3) as? Int
                                val layoutWidth = if (left != null && right != null && right > left) {
                                    right - left
                                } else {
                                    hotseat.width
                                }
                                when (resolveEffectiveState(hotseat, layoutWidth)) {
                                    ScreenState.UNINITIALIZED -> return@withReentrantGuard
                                    ScreenState.INNER -> {
                                        restoreOriginalState(
                                            hotseat,
                                            preserveCurrentPaddingAndHeight = true
                                        )
                                        return@withReentrantGuard
                                    }
                                    ScreenState.COVER -> {
                                        val prefs = modulePrefs
                                        val heightPercent = HookConfig.coverDockHeightPercent(prefs)
                                        val widthPercent = HookConfig.coverDockWidthPercent(prefs)
                                        // Never call setPaddingRelative inside onLayout, as that
                                        // triggers requestLayout() during layout and causes a
                                        // layout storm. Only update the drawable bounds/insets.
                                        applyDockBackgroundAndPadding(
                                            hotseat = hotseat,
                                            heightPercent = heightPercent,
                                            widthPercent = widthPercent,
                                            measuredWidth = layoutWidth,
                                            allowLayoutMutation = false
                                        )
                                        unclipParentHierarchy(hotseat)
                                    }
                                }
                            }
                        }
                    }
                }.onFailure {
                    Log.w(TAG, "Could not hook Hotseat.onLayout", it)
                }

                runCatching {
                    injectMember {
                        method {
                            name = "draw"
                            param(Canvas::class.java)
                            superClass()
                        }
                        beforeHook {
                            withReentrantGuard {
                                if (!isHotseatInstance(instance)) return@withReentrantGuard
                                val hotseat = instance as? View ?: return@withReentrantGuard
                                if (resolveEffectiveState(hotseat, hotseat.width) == ScreenState.INNER) {
                                    restoreOriginalState(
                                        hotseat,
                                        preserveCurrentPaddingAndHeight = true
                                    )
                                }
                            }
                        }
                    }
                }.onFailure {
                    Log.w(TAG, "Could not hook Hotseat.draw", it)
                }

                runCatching {
                    injectMember {
                        method {
                            name = "onConfigurationChanged"
                            param(Configuration::class.java)
                            superClass()
                        }
                        afterHook {
                            withReentrantGuard {
                                if (!isHotseatInstance(instance)) return@withReentrantGuard
                                val hotseat = instance as? View ?: return@withReentrantGuard
                                when (resolveEffectiveState(hotseat)) {
                                    ScreenState.UNINITIALIZED -> return@withReentrantGuard
                                    ScreenState.INNER -> {
                                        restoreOriginalState(hotseat)
                                        return@withReentrantGuard
                                    }
                                    ScreenState.COVER -> {
                                        val prefs = modulePrefs
                                        val heightPercent = HookConfig.coverDockHeightPercent(prefs)
                                        val widthPercent = HookConfig.coverDockWidthPercent(prefs)
                                        applyDockBackgroundAndPadding(
                                            hotseat = hotseat,
                                            heightPercent = heightPercent,
                                            widthPercent = widthPercent,
                                            allowLayoutMutation = true
                                        )
                                        unclipParentHierarchy(hotseat)
                                    }
                                }
                            }
                        }
                    }
                }.onFailure {
                    Log.w(TAG, "Could not hook Hotseat.onConfigurationChanged", it)
                }

                listOf("resetLayout", "layoutBgView").forEach { methodName ->
                    val allowLayoutMutation = methodName != "layoutBgView"
                    runCatching {
                        injectMember {
                            method {
                                name = methodName
                                paramCount(0..2)
                            }.all()
                            afterHook {
                                withReentrantGuard {
                                    if (!isHotseatInstance(instance)) return@withReentrantGuard
                                    val hotseat = instance as? View ?: return@withReentrantGuard
                                    when (resolveEffectiveState(hotseat)) {
                                        ScreenState.UNINITIALIZED -> return@withReentrantGuard
                                        ScreenState.INNER -> {
                                            restoreOriginalState(
                                                hotseat,
                                                preserveCurrentPaddingAndHeight = !allowLayoutMutation
                                            )
                                            return@withReentrantGuard
                                        }
                                        ScreenState.COVER -> {
                                            val prefs = modulePrefs
                                            val heightPercent =
                                                HookConfig.coverDockHeightPercent(prefs)
                                            val widthPercent =
                                                HookConfig.coverDockWidthPercent(prefs)
                                            applyDockBackgroundAndPadding(
                                                hotseat = hotseat,
                                                heightPercent = heightPercent,
                                                widthPercent = widthPercent,
                                                allowLayoutMutation = allowLayoutMutation
                                            )
                                            unclipParentHierarchy(hotseat)
                                        }
                                    }
                                }
                            }
                        }
                    }.onFailure {
                        Log.w(TAG, "Could not hook Hotseat.$methodName", it)
                    }
                }
            }

            Log.i(TAG, "Hooked Hotseat lifecycle (init / onAttachedToWindow / setInsets / onLayout)")
        }.onFailure {
            Log.w(TAG, "Could not hook Hotseat lifecycle", it)
        }
    }

    private fun hookMeasure() {
        runCatching {
            findClass(CLASS_HOTSEAT).hook {
                injectMember {
                    method {
                        name = "onMeasure"
                        param(IntType, IntType)
                        superClass()
                    }
                    beforeHook {
                        withReentrantGuard {
                            if (!isHotseatInstance(instance)) return@withReentrantGuard
                            val hotseat = instance as? View ?: return@withReentrantGuard
                            val widthSpec = args.getOrNull(0) as? Int
                            val measuredWidth = widthSpec?.let { spec ->
                                if (View.MeasureSpec.getMode(spec) != View.MeasureSpec.UNSPECIFIED) {
                                    View.MeasureSpec.getSize(spec)
                                } else {
                                    null
                                }
                            }
                            when (resolveEffectiveState(hotseat, measuredWidth)) {
                                ScreenState.UNINITIALIZED -> return@withReentrantGuard
                                ScreenState.INNER -> {
                                    restoreOriginalState(hotseat)
                                    return@withReentrantGuard
                                }
                                ScreenState.COVER -> {
                                    val prefs = modulePrefs
                                    val heightPercent = HookConfig.coverDockHeightPercent(prefs)
                                    val widthPercent = HookConfig.coverDockWidthPercent(prefs)
                                    applyDockBackgroundAndPadding(
                                        hotseat = hotseat,
                                        heightPercent = heightPercent,
                                        widthPercent = widthPercent,
                                        measuredWidth = measuredWidth,
                                        allowLayoutMutation = true
                                    )
                                    unclipParentHierarchy(hotseat)
                                }
                            }
                        }
                    }
                }
            }

            Log.i(TAG, "Hooked Hotseat.onMeasure")
        }.onFailure {
            Log.w(TAG, "Could not hook Hotseat.onMeasure", it)
        }
    }

    /** Apply the user multiplier to stock animation frames, not a captured frame. */
    private fun hookScale() {
        listOf("setScaleX", "setScaleY").forEach { methodName ->
            runCatching {
                findClass(CLASS_HOTSEAT).hook {
                    injectMember {
                        method {
                            name = methodName
                            param(FloatType)
                            superClass()
                        }
                        beforeHook {
                            runCatching {
                                if (reentrantGuard.get() == true || dockScales.isEmpty()) return@runCatching
                                val hotseat = instance as? View ?: return@runCatching
                                val state = dockScales[hotseat] ?: return@runCatching
                                if (!HookConfig.isCoverDockEnabled(modulePrefs)) return@runCatching
                                val stockScale = args[0] as? Float ?: return@runCatching
                                args[0] = if (methodName == "setScaleX") {
                                    state.onStockScaleX(stockScale)
                                } else {
                                    state.onStockScaleY(stockScale)
                                }
                            }.onFailure { ErrorLogger.log(TAG, "apply Dock animation scale", it) }
                        }
                    }
                }
            }.onFailure { ErrorLogger.log(TAG, "hook Hotseat.$methodName", it) }
        }
    }

    /** Called under the mutation guard; default size never takes ownership of scale. */
    private fun updateDockScale(hotseat: View, factor: Float) {
        val existing = dockScales[hotseat]
        if (existing == null && factor == 1f) return
        val state = existing ?: DockScaleState(hotseat.scaleX, hotseat.scaleY).also {
            dockScales[hotseat] = it
        }
        state.update(hotseat.scaleX, hotseat.scaleY, factor)
        if (hotseat.scaleX != state.appliedScaleX) hotseat.scaleX = state.appliedScaleX
        if (hotseat.scaleY != state.appliedScaleY) hotseat.scaleY = state.appliedScaleY
        if (factor == 1f) dockScales.remove(hotseat)
    }

    /**
     * Routes touches that fall outside the narrowed Dock background (when
     * `coverDockWidthPercent < 100` or Floating Island inset is active) through
     * to the underlying Workspace by returning `false` from
     * `Hotseat.dispatchTouchEvent` and shrinking `Hotseat.getHitRect`
     * horizontally.
     */
    private fun hookTouchRouting() {
        runCatching {
            findClass(CLASS_HOTSEAT).hook {
                runCatching {
                    injectMember {
                        method {
                            name = "dispatchTouchEvent"
                            param(MotionEvent::class.java)
                            superClass()
                        }
                        beforeHook {
                            withReentrantGuard {
                                if (!isHotseatInstance(instance)) return@withReentrantGuard
                                val hotseat = instance as? View ?: return@withReentrantGuard
                                if (resolveEffectiveState(hotseat) != ScreenState.COVER) {
                                    return@withReentrantGuard
                                }

                                val viewWidth = hotseat.width
                                if (viewWidth <= 0) return@withReentrantGuard
                                val horizontalInset = computeEffectiveHorizontalInset(hotseat, viewWidth)
                                if (horizontalInset <= 0) return@withReentrantGuard

                                val ev = args.getOrNull(0) as? MotionEvent ?: return@withReentrantGuard
                                val leftBound = horizontalInset.toFloat()
                                val rightBound = (viewWidth - horizontalInset).toFloat()
                                if (ev.x < leftBound || ev.x > rightBound) {
                                    result = false
                                }
                            }
                        }
                    }
                }.onFailure {
                    Log.w(TAG, "Could not hook Hotseat.dispatchTouchEvent", it)
                }

                runCatching {
                    injectMember {
                        method {
                            name = "getHitRect"
                            param(Rect::class.java)
                            superClass()
                        }
                        afterHook {
                            withReentrantGuard {
                                if (!isHotseatInstance(instance)) return@withReentrantGuard
                                val hotseat = instance as? View ?: return@withReentrantGuard
                                if (resolveEffectiveState(hotseat) != ScreenState.COVER) {
                                    return@withReentrantGuard
                                }

                                val outRect = args.getOrNull(0) as? Rect ?: return@withReentrantGuard
                                val fullWidth = (outRect.right - outRect.left).coerceAtLeast(0)
                                val horizontalInset = computeEffectiveHorizontalInset(hotseat, fullWidth)
                                if (horizontalInset > 0 && outRect.left + horizontalInset < outRect.right - horizontalInset) {
                                    outRect.left += horizontalInset
                                    outRect.right -= horizontalInset
                                }
                            }
                        }
                    }
                }.onFailure {
                    Log.w(TAG, "Could not hook Hotseat.getHitRect", it)
                }
            }

            Log.i(TAG, "Hooked Hotseat touch routing for dock width")
        }.onFailure {
            Log.w(TAG, "Could not hook Hotseat touch routing", it)
        }
    }

    private fun installAttachListener(hotseat: View) {
        synchronized(attachedListeners) {
            if (attachedListeners.containsKey(hotseat)) return
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    withReentrantGuard { onHotseatAttached(v) }
                }

                override fun onViewDetachedFromWindow(v: View) = Unit
            }
            attachedListeners[hotseat] = listener
            hotseat.addOnAttachStateChangeListener(listener)
        }
    }

    private fun onHotseatAttached(hotseat: View) {
        when (resolveEffectiveState(hotseat)) {
            ScreenState.UNINITIALIZED -> return
            ScreenState.INNER -> {
                restoreOriginalState(hotseat)
                return
            }
            ScreenState.COVER -> {
                val prefs = modulePrefs
                val heightPercent = HookConfig.coverDockHeightPercent(prefs)
                val widthPercent = HookConfig.coverDockWidthPercent(prefs)
                originalState(hotseat)
                applyDockBackgroundAndPadding(
                    hotseat = hotseat,
                    heightPercent = heightPercent,
                    widthPercent = widthPercent,
                    allowLayoutMutation = true
                )
                unclipParentHierarchy(hotseat)
            }
        }
    }

    /**
     * Resolves the effective state for [hotseat]:
     * - [ScreenState.UNINITIALIZED] when Display/Resources/Context are not ready yet
     *   (e.g. right after soft reboot or during early View inflation).
     * - [ScreenState.INNER] when the cover-dock toggle is disabled OR [hotseat] is
     *   on the main inner screen.
     * - [ScreenState.COVER] when the cover-dock toggle is enabled AND [hotseat] is
     *   confirmed to be on the outer cover screen.
     */
    private fun resolveEffectiveState(hotseat: View, candidateWidthPx: Int? = null): ScreenState {
        return try {
            val prefs = modulePrefs
            if (!HookConfig.isCoverDockEnabled(prefs)) {
                return ScreenState.INNER
            }
            resolveScreenState(hotseat, candidateWidthPx)
        } catch (_: Throwable) {
            ScreenState.UNINITIALIZED
        }
    }

    /**
     * 画面幅（px / dp）に基づくカバースクリーン（外側画面）判定（displayId 非依存）:
     * - Motorola Razr Fold 実機値:
     *   - カバースクリーン（閉じた状態）: 幅 `1080px` (`1080x2520`, `swDp = 411`), `Hotseat.isBgVisible() = false`
     *   - インナースクリーン（開いた状態）: 幅 `2232px` / `2484px` (`2232x2484`, `swDp = 850`), `Hotseat.isBgVisible() = true`
     * - Motorola Razr Flip (Clamshell):
     *   - カバースクリーン: 幅 `1056〜1272px` (`swDp < 600`)
     * - View 幅・WindowMetrics 幅・Display 幅・DeviceProfile 幅・Resources 幅のいずれかが
     *   `1500px` 以上（または `600dp` 以上）の場合は即座に `INNER` と判定し描画しない。
     */
    private fun resolveScreenState(hotseat: View, candidateWidthPx: Int? = null): ScreenState {
        return try {
            val context = hotseat.context ?: return ScreenState.UNINITIALIZED

            // 1. Check View / Layout / Measure widths first (updated immediately during resize).
            val parentWidth = (hotseat.parent as? View)?.width ?: 0
            val rootWidth = hotseat.rootView?.width ?: 0
            val viewWidths = intArrayOf(
                candidateWidthPx ?: 0,
                hotseat.width,
                hotseat.measuredWidth,
                parentWidth,
                rootWidth
            )
            if (viewWidths.any { it >= INNER_SCREEN_MIN_WIDTH_PX }) {
                return ScreenState.INNER
            }

            // 2. Check WindowManager.currentWindowMetrics & Display size (no displayId used).
            val wm = context.getSystemService(WindowManager::class.java)
                ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            val wmBounds = runCatching { wm?.currentWindowMetrics?.bounds }.getOrNull()
            val wmWidth = wmBounds?.width() ?: 0
            val wmHeight = wmBounds?.height() ?: 0
            if (wmWidth >= INNER_SCREEN_MIN_WIDTH_PX ||
                (wmWidth > 0 && wmHeight > 0 && minOf(wmWidth, wmHeight) >= INNER_SCREEN_MIN_WIDTH_PX)
            ) {
                return ScreenState.INNER
            }

            @Suppress("DEPRECATION")
            val display = runCatching { hotseat.display ?: wm?.defaultDisplay }.getOrNull()
            var displayWidth = 0
            if (display != null) {
                val pt = Point()
                @Suppress("DEPRECATION")
                runCatching { display.getRealSize(pt) }.onFailure {
                    @Suppress("DEPRECATION")
                    runCatching { display.getSize(pt) }
                }
                displayWidth = pt.x
                if (pt.x >= INNER_SCREEN_MIN_WIDTH_PX ||
                    (pt.x > 0 && pt.y > 0 && minOf(pt.x, pt.y) >= INNER_SCREEN_MIN_WIDTH_PX)
                ) {
                    return ScreenState.INNER
                }
            }

            // 3. Check DeviceProfile widthPx / isTablet / isTwoPanels.
            val profile = findDeviceProfile(hotseat)
            var profileWidth = 0
            if (profile != null) {
                profileWidth = getIntField(profile, "widthPx") ?: 0
                val profileAvailWidth = getIntField(profile, "availableWidthPx") ?: 0
                val profileHeight = getIntField(profile, "heightPx") ?: 0
                val isTablet = getBooleanField(profile, "isTablet") == true
                val isTwoPanels = getBooleanField(profile, "isTwoPanels") == true
                if (isTablet ||
                    isTwoPanels ||
                    profileWidth >= INNER_SCREEN_MIN_WIDTH_PX ||
                    profileAvailWidth >= INNER_SCREEN_MIN_WIDTH_PX ||
                    (profileWidth > 0 && profileHeight > 0 && minOf(profileWidth, profileHeight) >= INNER_SCREEN_MIN_WIDTH_PX)
                ) {
                    return ScreenState.INNER
                }
            }

            // 4. Check Resources configuration & displayMetrics width.
            val res = context.resources ?: return ScreenState.UNINITIALIZED
            val config = res.configuration ?: return ScreenState.UNINITIALIZED
            val metrics = res.displayMetrics ?: return ScreenState.UNINITIALIZED

            val swDp = config.smallestScreenWidthDp
            val screenWidthDp = config.screenWidthDp
            val resWidth = metrics.widthPixels
            val minDim = minOf(metrics.widthPixels, metrics.heightPixels)
            if (swDp >= INNER_SCREEN_MIN_WIDTH_DP ||
                screenWidthDp >= INNER_SCREEN_MIN_WIDTH_DP ||
                resWidth >= INNER_SCREEN_MIN_WIDTH_PX ||
                minDim >= INNER_SCREEN_MIN_WIDTH_PX
            ) {
                return ScreenState.INNER
            }

            val hasValidCoverWidth = viewWidths.any { it in 1 until INNER_SCREEN_MIN_WIDTH_PX } ||
                wmWidth in 1 until INNER_SCREEN_MIN_WIDTH_PX ||
                displayWidth in 1 until INNER_SCREEN_MIN_WIDTH_PX ||
                profileWidth in 1 until INNER_SCREEN_MIN_WIDTH_PX ||
                (resWidth in 1 until INNER_SCREEN_MIN_WIDTH_PX && swDp in 1 until INNER_SCREEN_MIN_WIDTH_DP)
            if (!hasValidCoverWidth) {
                return ScreenState.UNINITIALIZED
            }

            // Hotseat#isBgVisible() が true の場合は Launcher3 標準の Dock 背景 (mBgView) が
            // 有効なメイン（インナー）画面であるため、二重描画を防ぐため INNER とする。
            val stockBgVisible = invokeIsBgVisible(hotseat)
            if (stockBgVisible == true) {
                return ScreenState.INNER
            }
            // If isBgVisible() returned null (e.g. Workspace not yet bound on Launcher)
            // and the view is not attached to a window yet, wait until it is attached.
            if (stockBgVisible == null && !hotseat.isAttachedToWindow) {
                return ScreenState.UNINITIALIZED
            }

            ScreenState.COVER
        } catch (_: Throwable) {
            ScreenState.UNINITIALIZED
        }
    }

    /** Physical display classification, independent of the cover-Dock enable switch. */
    internal fun isMainDisplay(hotseat: View): Boolean =
        resolveScreenState(hotseat) == ScreenState.INNER

    private fun invokeIsBgVisible(hotseat: View): Boolean? {
        return try {
            var method = cachedIsBgVisibleMethod
            if (method == null && !isBgVisibleMethodResolved) {
                var curr: Class<*>? = hotseat.javaClass
                while (curr != null && curr != View::class.java) {
                    val found = try {
                        curr.getDeclaredMethod("isBgVisible").apply { isAccessible = true }
                    } catch (_: Throwable) {
                        null
                    }
                    if (found != null) {
                        method = found
                        cachedIsBgVisibleMethod = found
                        break
                    }
                    curr = curr.superclass
                }
                isBgVisibleMethodResolved = true
            }
            method?.invoke(hotseat) as? Boolean
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Dynamically creates and assigns a rounded [CenteredDockGradientDrawable]
     * on `hotseat.background` (only when confirmed on the Cover Screen),
     * applying icon-count-aware Floating Island side insets, 28dp rounded corners,
     * `clipToOutline = true`, and Frosted translucent surface + border stroke.
     *
     * Also eliminates duplicate internal CellLayout / ShortcutAndWidgetContainer
     * horizontal padding and optimizes inter-cell horizontal gap (`mBorderSpace.x`)
     * so 5-column and 6-column docks fit naturally without excessive margins.
     *
     * When [allowLayoutMutation] is `false` (i.e. inside `onLayout` /
     * `layoutBgView`), `setPaddingRelative` is skipped so `requestLayout()` is
     * never triggered during a layout pass.
     */
    private fun applyDockBackgroundAndPadding(
        hotseat: View,
        heightPercent: Int,
        widthPercent: Int,
        measuredWidth: Int? = null,
        allowLayoutMutation: Boolean = true
    ) {
        val res = hotseat.resources ?: return
        val metrics = res.displayMetrics ?: return
        val original = originalState(hotseat)
        val density = metrics.density.coerceAtLeast(1f)
        val floatingIsland = HookConfig.isCoverDockFloatingIslandEnabled(modulePrefs)
        val frost = HookConfig.isCoverDockFrostEnabled(modulePrefs)
        val liquid = HookConfig.isCoverDockLiquidGlassEnabled(modulePrefs)
        val cornerRadiusPx = (if (floatingIsland) ISLAND_CORNER_RADIUS_DP else DOCK_CORNER_RADIUS_DP) * density

        val effectiveWidth = when {
            measuredWidth != null && measuredWidth > 0 -> measuredWidth
            hotseat.width > 0 -> hotseat.width
            hotseat.measuredWidth > 0 -> hotseat.measuredWidth
            else -> metrics.widthPixels
        }
        if (effectiveWidth >= INNER_SCREEN_MIN_WIDTH_PX) {
            restoreOriginalState(hotseat, preserveCurrentPaddingAndHeight = !allowLayoutMutation)
            return
        }

        val iconCount = resolveDockIconCount(hotseat)
        val sideInsetPx = if (floatingIsland) {
            (resolveIslandSideInsetDp(iconCount) * density).roundToInt()
        } else {
            0
        }
        val horizontalInset = maxOf(
            computeHorizontalInset(effectiveWidth, widthPercent),
            sideInsetPx
        ).coerceAtMost((effectiveWidth / 2).coerceAtLeast(0))

        // Replace Moto's oversized stock horizontal padding (`hotseatLayoutPadding.left/right`)
        // with a slim, column-aware inner padding relative to the dock/island edge so 5/6-column
        // icons do not suffer from double horizontal padding.
        val contentSidePaddingPx = (resolveContentSidePaddingDp(iconCount) * density)
            .roundToInt()
            .coerceAtLeast(0)
        val startPadding = (horizontalInset + contentSidePaddingPx).coerceAtLeast(0)
        val endPadding = (horizontalInset + contentSidePaddingPx).coerceAtLeast(0)

        // Keep vertical padding at the original values so icon alignment and
        // Hotseat View height are never altered.
        val topPadding = original.paddingTop
        val bottomPadding = original.paddingBottom

        val (topInset, bottomInset) = resolveVerticalContentInsets(
            hotseat = hotseat,
            fallbackTop = topPadding,
            fallbackBottom = bottomPadding
        )

        if (hotseat.visibility != View.VISIBLE) {
            hotseat.visibility = View.VISIBLE
        }

        val existingGenerated = synchronized(generatedBackgrounds) {
            generatedBackgrounds[hotseat]
        }

        if (hotseat.background === existingGenerated || isBackgroundUnsetOrTransparent(hotseat.background)) {
            val isNewDrawable = existingGenerated == null
            val drawable = existingGenerated ?: CenteredDockGradientDrawable(hotseat).also { created ->
                created.shape = GradientDrawable.RECTANGLE
                created.setColor(DOCK_BACKGROUND_COLOR)
                synchronized(generatedBackgrounds) {
                    generatedBackgrounds[hotseat] = created
                }
            }
            val appearanceChanged = drawable.updateAppearance(frost, liquid, density)
            val dimsChanged = drawable.updateDimensions(
                newWidthPercent = widthPercent,
                newHeightPercent = heightPercent,
                topInset = topInset,
                bottomInset = bottomInset,
                cornerRadiusPx = cornerRadiusPx,
                sideInsetPx = sideInsetPx
            )
            var needsInvalidate = isNewDrawable || appearanceChanged || dimsChanged
            if (hotseat.background !== drawable) {
                hotseat.background = drawable
                needsInvalidate = true
            }
            if (hotseat.width > 0 && hotseat.height > 0) {
                drawable.setBounds(0, 0, hotseat.width, hotseat.height)
            }
            val targetClipToOutline = floatingIsland
            if (hotseat.clipToOutline != targetClipToOutline) {
                hotseat.clipToOutline = targetClipToOutline
                needsInvalidate = true
            }
            if (targetClipToOutline) {
                hotseat.invalidateOutline()
            }
            if (hotseat.willNotDraw()) {
                hotseat.setWillNotDraw(false)
            }
            if (needsInvalidate) {
                hotseat.invalidate()
            }
        }

        if (allowLayoutMutation) {
            updateDockScale(hotseat, HookConfig.coverDockSizePercent(modulePrefs) / 100f)
            optimizeCellLayoutInternalSpacing(hotseat, iconCount, density)
            if (hotseat.paddingStart != startPadding ||
                hotseat.paddingTop != topPadding ||
                hotseat.paddingEnd != endPadding ||
                hotseat.paddingBottom != bottomPadding
            ) {
                hotseat.setPaddingRelative(
                    startPadding,
                    topPadding,
                    endPadding,
                    bottomPadding
                )
            }
        }
    }

    /**
     * Resolves the active Hotseat icon column count on the Cover Screen,
     * falling back to the user's configured `dock_icons` preference.
     */
    private fun resolveDockIconCount(hotseat: View): Int {
        val prefsCount = HookConfig.coerceGridValue(
            HookConfig.safeGetInt(
                modulePrefs,
                HookConfig.KEY_DOCK_ICONS,
                HookConfig.DEFAULT_DOCK_ICONS
            ),
            HookConfig.DEFAULT_DOCK_ICONS
        )
        val countX = getIntField(hotseat, "mCountX") ?: 0
        val childCount = (findShortcutAndWidgetContainer(hotseat) as? ViewGroup)?.childCount ?: 0
        return maxOf(prefsCount, countX, childCount).coerceIn(
            HookConfig.MIN_GRID_VALUE,
            HookConfig.MAX_GRID_VALUE
        )
    }

    private fun resolveIslandSideInsetDp(iconCount: Int): Float = when {
        iconCount >= 6 -> ISLAND_SIDE_INSET_6_COL_DP
        iconCount == 5 -> ISLAND_SIDE_INSET_5_COL_DP
        else -> ISLAND_SIDE_INSET_4_COL_DP
    }

    private fun resolveContentSidePaddingDp(iconCount: Int): Float = when {
        iconCount >= 6 -> CONTENT_SIDE_PADDING_6_COL_DP
        iconCount == 5 -> CONTENT_SIDE_PADDING_5_COL_DP
        else -> CONTENT_SIDE_PADDING_4_COL_DP
    }

    private fun resolveMaxBorderSpaceDp(iconCount: Int): Float = when {
        iconCount >= 6 -> MAX_BORDER_SPACE_6_COL_DP
        iconCount == 5 -> MAX_BORDER_SPACE_5_COL_DP
        else -> MAX_BORDER_SPACE_4_COL_DP
    }

    private fun findShortcutAndWidgetContainer(hotseat: View): View? {
        runCatching {
            val field = findField(hotseat.javaClass, "mShortcutsAndWidgets")
            val container = field?.get(hotseat) as? View
            if (container != null) return container
        }
        if (hotseat is ViewGroup) {
            for (i in 0 until hotseat.childCount) {
                val child = hotseat.getChildAt(i)
                if (child.javaClass.name == CLASS_SHORTCUT_AND_WIDGET_CONTAINER) {
                    return child
                }
            }
        }
        return null
    }

    /**
     * Removes redundant horizontal padding on `ShortcutAndWidgetContainer` and
     * clamps `CellLayout.mBorderSpace.x` (and `DeviceProfile.hotseatBorderSpace`)
     * on the Cover Screen so 5/6-column cells are evenly sized without negative
     * or oversized inter-cell gaps.
     */
    private fun optimizeCellLayoutInternalSpacing(
        hotseat: View,
        iconCount: Int,
        density: Float
    ) {
        val maxBorderPx = (resolveMaxBorderSpaceDp(iconCount) * density)
            .roundToInt()
            .coerceAtLeast(0)

        runCatching {
            val borderField = findField(hotseat.javaClass, "mBorderSpace")
            val borderPoint = borderField?.get(hotseat) as? Point
            if (borderPoint != null) {
                synchronized(originalBorderSpaces) {
                    if (!originalBorderSpaces.containsKey(hotseat)) {
                        originalBorderSpaces[hotseat] = Point(borderPoint.x, borderPoint.y)
                    }
                }
                val targetX = borderPoint.x.coerceIn(0, maxBorderPx)
                val targetY = borderPoint.y.coerceAtLeast(0)
                if (borderPoint.x != targetX || borderPoint.y != targetY) {
                    borderPoint.set(targetX, targetY)
                }
            }
        }

        runCatching {
            val profile = findDeviceProfile(hotseat)
            if (profile != null) {
                val currentSpace = getIntField(profile, "hotseatBorderSpace")
                if (currentSpace != null) {
                    val safeSpace = currentSpace.coerceIn(0, maxBorderPx)
                    if (currentSpace != safeSpace) {
                        findField(profile.javaClass, "hotseatBorderSpace")?.setInt(profile, safeSpace)
                    }
                }
            }
        }

        runCatching {
            val container = findShortcutAndWidgetContainer(hotseat) ?: return@runCatching
            synchronized(originalContainerPaddings) {
                if (!originalContainerPaddings.containsKey(container)) {
                    originalContainerPaddings[container] = Rect(
                        container.paddingStart,
                        container.paddingTop,
                        container.paddingEnd,
                        container.paddingBottom
                    )
                }
            }
            if (container.paddingStart != 0 || container.paddingEnd != 0) {
                container.setPaddingRelative(0, container.paddingTop, 0, container.paddingBottom)
            }
        }
    }

    private fun computeEffectiveHorizontalInset(hotseat: View, totalWidth: Int): Int {
        if (totalWidth <= 0) return 0
        val prefs = modulePrefs
        val widthPercent = HookConfig.coverDockWidthPercent(prefs)
        val baseInset = computeHorizontalInset(totalWidth, widthPercent)
        if (!HookConfig.isCoverDockFloatingIslandEnabled(prefs)) {
            return baseInset
        }
        val density = hotseat.resources?.displayMetrics?.density?.coerceAtLeast(1f) ?: 2.625f
        val iconCount = resolveDockIconCount(hotseat)
        val sideInsetPx = (resolveIslandSideInsetDp(iconCount) * density).roundToInt()
        return maxOf(baseInset, sideInsetPx).coerceAtMost(totalWidth / 2)
    }

    /**
     * Resolves the vertical top/bottom insets around the icon container inside
     * [hotseat] so the background plate scales symmetrically around the icons.
     */
    private fun resolveVerticalContentInsets(
        hotseat: View,
        fallbackTop: Int,
        fallbackBottom: Int
    ): Pair<Int, Int> {
        val viewHeight = hotseat.height
        if (viewHeight > 0 && hotseat is ViewGroup && hotseat.childCount > 0) {
            // Index 0 is Moto's mBgView (or our decorative plate), not icons.
            val child = (0 until hotseat.childCount)
                .map { hotseat.getChildAt(it) }
                .firstOrNull { it.javaClass.name == "com.android.launcher3.ShortcutAndWidgetContainer" }
            if (child != null && child.height > 0) {
                val childTop = child.top.coerceAtLeast(0)
                val childBottomInset = (viewHeight - child.bottom).coerceAtLeast(0)
                if (childTop + childBottomInset < viewHeight) {
                    return childTop to childBottomInset
                }
            }
        }
        return fallbackTop.coerceAtLeast(0) to fallbackBottom.coerceAtLeast(0)
    }

    private fun computeHorizontalInset(totalWidth: Int, widthPercent: Int): Int {
        if (totalWidth <= 0) return 0
        val coerced = HookConfig.coerceCoverDockWidthPercent(widthPercent)
        if (coerced >= 100) return 0
        val targetWidth = (totalWidth * coerced / 100f).roundToInt().coerceIn(0, totalWidth)
        return ((totalWidth - targetWidth) / 2f).roundToInt().coerceAtLeast(0)
    }

    private fun isBackgroundUnsetOrTransparent(drawable: Drawable?): Boolean {
        if (drawable == null) return true
        if (drawable.alpha == 0) return true
        if (drawable is ColorDrawable && Color.alpha(drawable.color) == 0) return true
        @Suppress("DEPRECATION")
        if (drawable.opacity == PixelFormat.TRANSPARENT) return true
        return false
    }

    /**
     * Disables child and padding clipping on Hotseat and its parent hierarchy
     * (including DragLayer / FrameLayout) so touch events in the expanded dock
     * area are never discarded by parent bounds checks.
     */
    private fun unclipParentHierarchy(hotseat: View) {
        if (hotseat is ViewGroup) {
            disableClipping(hotseat)
            for (i in 0 until hotseat.childCount) {
                val child = hotseat.getChildAt(i)
                if (child is ViewGroup) {
                    disableClipping(child)
                }
            }
        }

        var parent = hotseat.parent
        while (parent is ViewGroup) {
            disableClipping(parent)
            parent = parent.parent
        }
    }

    private fun disableClipping(viewGroup: ViewGroup) {
        synchronized(originalClipStates) {
            if (!originalClipStates.containsKey(viewGroup)) {
                originalClipStates[viewGroup] = OriginalClipState(
                    clipChildren = viewGroup.clipChildren,
                    clipToPadding = viewGroup.clipToPadding
                )
            }
        }
        if (viewGroup.clipChildren) {
            viewGroup.clipChildren = false
        }
        if (viewGroup.clipToPadding) {
            viewGroup.clipToPadding = false
        }
    }

    private fun findDeviceProfile(hotseat: View): Any? {
        // 1. Check fields on Hotseat (e.g., mActivity) that provide getDeviceProfile().
        runCatching {
            var current: Class<*>? = hotseat.javaClass
            while (current != null && current != View::class.java) {
                for (field in current.declaredFields) {
                    field.isAccessible = true
                    val value = field.get(hotseat) ?: continue
                    if (value.javaClass.name == CLASS_DEVICE_PROFILE) {
                        return value
                    }
                    val profile = invokeGetDeviceProfile(value)
                    if (profile != null) return profile
                }
                current = current.superclass
            }
        }

        // 2. Walk the Context chain for ActivityContext#getDeviceProfile().
        var ctx: Context? = hotseat.context
        while (ctx != null) {
            val profile = invokeGetDeviceProfile(ctx)
            if (profile != null) return profile
            ctx = (ctx as? ContextWrapper)?.baseContext
        }

        return null
    }

    private fun invokeGetDeviceProfile(target: Any): Any? = runCatching {
        var current: Class<*>? = target.javaClass
        while (current != null && current != Any::class.java) {
            val method = current.declaredMethods.firstOrNull {
                it.name == "getDeviceProfile" && it.parameterCount == 0
            }
            if (method != null) {
                method.isAccessible = true
                return@runCatching method.invoke(target)
            }
            val field = current.declaredFields.firstOrNull {
                it.name == "mDeviceProfile" || it.type.name == CLASS_DEVICE_PROFILE
            }
            if (field != null) {
                field.isAccessible = true
                return@runCatching field.get(target)
            }
            current = current.superclass
        }
        null
    }.getOrNull()

    private fun getIntField(target: Any, name: String): Int? = runCatching {
        findField(target.javaClass, name)?.getInt(target)
    }.getOrNull()

    private fun getBooleanField(target: Any, name: String): Boolean? = runCatching {
        findField(target.javaClass, name)?.getBoolean(target)
    }.getOrNull()

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            val field = runCatching { current.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return field
            }
            current = current.superclass
        }
        return null
    }

    private fun isHotseatInstance(target: Any?): Boolean {
        if (target == null) return false
        var current: Class<*>? = target.javaClass
        while (current != null && current != Any::class.java) {
            if (current.name == CLASS_HOTSEAT) return true
            current = current.superclass
        }
        return false
    }

    private fun originalState(view: View): OriginalViewState = synchronized(originalStates) {
        originalStates.getOrPut(view) {
            OriginalViewState(
                paddingStart = view.paddingStart,
                paddingTop = view.paddingTop,
                paddingEnd = view.paddingEnd,
                paddingBottom = view.paddingBottom,
                background = view.background.takeUnless { it is CenteredDockGradientDrawable },
                clipToOutline = view.clipToOutline,
                willNotDraw = view.willNotDraw(),
                layoutHeight = view.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun restoreOriginalState(
        view: View,
        preserveCurrentPaddingAndHeight: Boolean = false
    ) {
        // Remove only our multiplier. In particular, never replay a saved scale
        // every draw on the inner screen or after the cover-Dock option is off.
        updateDockScale(view, 1f)
        val original = synchronized(originalStates) {
            if (preserveCurrentPaddingAndHeight) {
                originalStates[view]
            } else {
                originalStates.remove(view)
            }
        }
        val generatedBg = synchronized(generatedBackgrounds) { generatedBackgrounds.remove(view) }

        if (original == null) {
            if (view.background is CenteredDockGradientDrawable) {
                view.background = null
                if (view.clipToOutline) {
                    view.clipToOutline = false
                }
                view.invalidate()
            }
            return
        }

        if (!preserveCurrentPaddingAndHeight) {
            if (view.paddingStart != original.paddingStart ||
                view.paddingTop != original.paddingTop ||
                view.paddingEnd != original.paddingEnd ||
                view.paddingBottom != original.paddingBottom
            ) {
                view.setPaddingRelative(
                    original.paddingStart,
                    original.paddingTop,
                    original.paddingEnd,
                    original.paddingBottom
                )
            }
            val lp = view.layoutParams
            if (lp != null && original.layoutHeight > 0 && lp.height != original.layoutHeight) {
                lp.height = original.layoutHeight
            }
        }

        if ((generatedBg != null && view.background === generatedBg) ||
            view.background is CenteredDockGradientDrawable
        ) {
            view.background = original.background
            if (view.willNotDraw() != original.willNotDraw) {
                view.setWillNotDraw(original.willNotDraw)
            }
            view.invalidate()
        }
        if (view.clipToOutline != original.clipToOutline) {
            view.clipToOutline = original.clipToOutline
        }

        if (!preserveCurrentPaddingAndHeight) {
            runCatching {
                val origBorder = synchronized(originalBorderSpaces) {
                    originalBorderSpaces.remove(view)
                }
                if (origBorder != null) {
                    val borderPoint = findField(view.javaClass, "mBorderSpace")?.get(view) as? Point
                    if (borderPoint != null && (borderPoint.x != origBorder.x || borderPoint.y != origBorder.y)) {
                        borderPoint.set(origBorder.x, origBorder.y)
                    }
                }
            }
            runCatching {
                val container = findShortcutAndWidgetContainer(view)
                if (container != null) {
                    val origPad = synchronized(originalContainerPaddings) {
                        originalContainerPaddings.remove(container)
                    }
                    if (origPad != null && (container.paddingStart != origPad.left || container.paddingEnd != origPad.right)) {
                        container.setPaddingRelative(
                            origPad.left,
                            origPad.top,
                            origPad.right,
                            origPad.bottom
                        )
                    }
                }
            }
        }

        val clipEntries = synchronized(originalClipStates) {
            if (originalClipStates.isEmpty()) {
                emptyList()
            } else {
                val snapshot = originalClipStates.entries.map { it.key to it.value }
                originalClipStates.clear()
                snapshot
            }
        }
        for ((group, clipState) in clipEntries) {
            runCatching {
                if (group.clipChildren != clipState.clipChildren) {
                    group.clipChildren = clipState.clipChildren
                }
                if (group.clipToPadding != clipState.clipToPadding) {
                    group.clipToPadding = clipState.clipToPadding
                }
            }
        }
    }
}
