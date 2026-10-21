package com.motolauncher.mod.hooks

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.type.java.IntType
import com.motolauncher.mod.HookConfig
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Shows and sizes Launcher3's Hotseat when the cover-dock option is enabled. */
object CoverDockHook : YukiBaseHooker() {

    private const val TAG = "MotoLauncherMod"
    private const val CLASS_HOTSEAT = "com.android.launcher3.Hotseat"
    private const val CLASS_DEVICE_PROFILE = "com.android.launcher3.DeviceProfile"
    private const val FIELD_HOTSEAT_BAR_SIZE_PX = "hotseatBarSizePx"

    /** Semi-transparent dark gray for the dynamically generated Dock background. */
    private val DOCK_BACKGROUND_COLOR: Int = Color.argb(153, 30, 30, 34)
    private const val DOCK_CORNER_RADIUS_DP = 24f

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
        val scaleX: Float,
        val scaleY: Float,
        val background: Drawable?,
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
    private class CenteredDockGradientDrawable : GradientDrawable() {
        var widthPercent: Int = HookConfig.DEFAULT_COVER_DOCK_WIDTH_PERCENT
            private set
        var heightPercent: Int = HookConfig.DEFAULT_COVER_DOCK_HEIGHT_PERCENT
            private set

        private var contentTopInset: Int = 0
        private var contentBottomInset: Int = 0
        private var baseCornerRadiusPx: Float = 0f

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
            cornerRadiusPx: Float
        ): Boolean {
            val coercedWidth = HookConfig.coerceCoverDockWidthPercent(newWidthPercent)
            val coercedHeight = HookConfig.coerceCoverDockHeightPercent(newHeightPercent)
            val safeTopInset = topInset.coerceAtLeast(0)
            val safeBottomInset = bottomInset.coerceAtLeast(0)

            val changed = widthPercent != coercedWidth ||
                heightPercent != coercedHeight ||
                contentTopInset != safeTopInset ||
                contentBottomInset != safeBottomInset ||
                baseCornerRadiusPx != cornerRadiusPx

            widthPercent = coercedWidth
            heightPercent = coercedHeight
            contentTopInset = safeTopInset
            contentBottomInset = safeBottomInset
            baseCornerRadiusPx = cornerRadiusPx

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

        private fun applyCenteredBounds(left: Int, top: Int, right: Int, bottom: Int) {
            val fullWidth = (right - left).coerceAtLeast(0)
            val horizontalInset = computeHorizontalInset(fullWidth, widthPercent)
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

    /** Tracks the unscaled base `hotseatBarSizePx` per DeviceProfile instance. */
    private val originalHotseatBarSizes = WeakHashMap<Any, Int>()

    /** Tracks the last scaled `hotseatBarSizePx` written by this hook per DeviceProfile. */
    private val lastScaledHotseatBarSizes = WeakHashMap<Any, Int>()

    /** Tracks the `hotseatBarSizePx` that was active when `Hotseat.setInsets` last ran. */
    private val lastAppliedBarSizeForHotseat = WeakHashMap<View, Int>()

    override fun onHook() {
        runCatching { hookVisibility() }
        runCatching { hookHotseatLifecycle() }
        runCatching { hookMeasure() }
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
                                        // Never call setPaddingRelative inside onLayout, as that
                                        // triggers requestLayout() during layout and causes a
                                        // layout storm. Only update the drawable bounds/insets.
                                        applyDockBackgroundAndPadding(
                                            hotseat = hotseat,
                                            heightPercent = heightPercent,
                                            widthPercent = widthPercent,
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
                                    val sizePercent = HookConfig.coverDockSizePercent(prefs)
                                    val original = originalState(hotseat)

                                    val widthSpec = args.getOrNull(0) as? Int
                                    val measuredWidth = widthSpec?.let { spec ->
                                        if (View.MeasureSpec.getMode(spec) != View.MeasureSpec.UNSPECIFIED) {
                                            View.MeasureSpec.getSize(spec)
                                        } else {
                                            null
                                        }
                                    }

                                    applyDockBackgroundAndPadding(
                                        hotseat = hotseat,
                                        heightPercent = heightPercent,
                                        widthPercent = widthPercent,
                                        measuredWidth = measuredWidth,
                                        allowLayoutMutation = true
                                    )
                                    unclipParentHierarchy(hotseat)

                                    val scale = sizePercent / 100f
                                    val targetScaleX = original.scaleX * scale
                                    val targetScaleY = original.scaleY * scale
                                    if (hotseat.scaleX != targetScaleX) {
                                        hotseat.scaleX = targetScaleX
                                    }
                                    if (hotseat.scaleY != targetScaleY) {
                                        hotseat.scaleY = targetScaleY
                                    }
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

    /**
     * Routes touches that fall outside the narrowed Dock background (when
     * `coverDockWidthPercent < 100`) through to the underlying Workspace by
     * returning `false` from `Hotseat.dispatchTouchEvent` and shrinking
     * `Hotseat.getHitRect` horizontally.
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

                                val widthPercent = HookConfig.coverDockWidthPercent(modulePrefs)
                                if (widthPercent >= 100) return@withReentrantGuard

                                val ev = args.getOrNull(0) as? MotionEvent ?: return@withReentrantGuard
                                val viewWidth = hotseat.width
                                if (viewWidth <= 0) return@withReentrantGuard

                                val horizontalInset = computeHorizontalInset(viewWidth, widthPercent)
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

                                val widthPercent = HookConfig.coverDockWidthPercent(modulePrefs)
                                if (widthPercent >= 100) return@withReentrantGuard

                                val outRect = args.getOrNull(0) as? Rect ?: return@withReentrantGuard
                                val fullWidth = (outRect.right - outRect.left).coerceAtLeast(0)
                                val horizontalInset = computeHorizontalInset(fullWidth, widthPercent)
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
    private fun resolveEffectiveState(hotseat: View): ScreenState {
        return try {
            val prefs = modulePrefs
            if (!HookConfig.isCoverDockEnabled(prefs)) {
                return ScreenState.INNER
            }
            resolveScreenState(hotseat)
        } catch (_: Throwable) {
            ScreenState.UNINITIALIZED
        }
    }

    /**
     * 実測値に基づくカバースクリーン（外側画面）判定:
     * - Motorola Razr Fold 実機値:
     *   - カバースクリーン（閉じた状態）: `1080x2520` (`minDim = 1080`, `swDp = 411`), `Hotseat.isBgVisible() = false`
     *   - インナースクリーン（開いた状態）: `2232x2484` (`minDim = 2232`, `swDp = 850`), `Hotseat.isBgVisible() = true`
     * - Motorola Razr Flip (Clamshell):
     *   - カバースクリーン: `1056x1066` / `1272x1080` (`minDim = 1056〜1080`, `swDp < 600`)
     * - Display 未確定時 (`minDim <= 0` または `swDp <= 0`) は `UNINITIALIZED` を返し、
     *   未初期化状態の View への変更や誤った `restoreOriginalState` を完全に防止する。
     */
    private fun resolveScreenState(hotseat: View): ScreenState {
        return try {
            val context = hotseat.context ?: return ScreenState.UNINITIALIZED
            val res = context.resources ?: return ScreenState.UNINITIALIZED
            val config = res.configuration ?: return ScreenState.UNINITIALIZED
            val metrics = res.displayMetrics ?: return ScreenState.UNINITIALIZED

            val swDp = config.smallestScreenWidthDp
            val minDim = minOf(metrics.widthPixels, metrics.heightPixels)
            if (swDp <= 0 || minDim <= 0) {
                return ScreenState.UNINITIALIZED
            }

            if (swDp >= 600 || minDim >= 1600) {
                return ScreenState.INNER
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
     * Dynamically creates and assigns a rounded semi-transparent dark
     * [CenteredDockGradientDrawable] when `hotseat.background` is null or
     * transparent, and updates its horizontal (`widthPercent`) and vertical
     * (`heightPercent`) bounds while preserving the Hotseat View frame and
     * vertical icon placement.
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
        val cornerRadiusPx = DOCK_CORNER_RADIUS_DP * density

        val effectiveWidth = when {
            measuredWidth != null && measuredWidth > 0 -> measuredWidth
            hotseat.width > 0 -> hotseat.width
            hotseat.measuredWidth > 0 -> hotseat.measuredWidth
            else -> metrics.widthPixels
        }
        val horizontalInset = computeHorizontalInset(effectiveWidth, widthPercent)
        val startPadding = (original.paddingStart + horizontalInset).coerceAtLeast(0)
        val endPadding = (original.paddingEnd + horizontalInset).coerceAtLeast(0)

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

        val currentBg = hotseat.background
        val existingGenerated = synchronized(generatedBackgrounds) {
            generatedBackgrounds[hotseat]
        }

        if (currentBg === existingGenerated || isBackgroundUnsetOrTransparent(currentBg)) {
            val isNewDrawable = existingGenerated == null
            val drawable = existingGenerated ?: CenteredDockGradientDrawable().also { created ->
                created.shape = GradientDrawable.RECTANGLE
                created.setColor(DOCK_BACKGROUND_COLOR)
                synchronized(generatedBackgrounds) {
                    generatedBackgrounds[hotseat] = created
                }
            }
            val dimsChanged = drawable.updateDimensions(
                newWidthPercent = widthPercent,
                newHeightPercent = heightPercent,
                topInset = topInset,
                bottomInset = bottomInset,
                cornerRadiusPx = cornerRadiusPx
            )
            var needsInvalidate = isNewDrawable || dimsChanged
            if (hotseat.background !== drawable) {
                hotseat.background = drawable
                needsInvalidate = true
            }
            if (hotseat.width > 0 && hotseat.height > 0) {
                drawable.setBounds(0, 0, hotseat.width, hotseat.height)
            }
            if (hotseat.clipToOutline) {
                hotseat.clipToOutline = false
            }
            if (hotseat.willNotDraw()) {
                hotseat.setWillNotDraw(false)
            }
            if (needsInvalidate) {
                hotseat.invalidate()
            }
        }

        if (allowLayoutMutation) {
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
            val child = hotseat.getChildAt(0)
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

    private fun computeAdjustedMeasureHeight(
        hotseat: View,
        specSize: Int,
        heightPercent: Int,
        targetBarSize: Int?
    ): Int {
        val appliedByInsets = synchronized(lastAppliedBarSizeForHotseat) {
            lastAppliedBarSizeForHotseat[hotseat]
        }

        val adjustedSize = when {
            appliedByInsets != null && targetBarSize != null -> {
                val delta = targetBarSize - appliedByInsets
                if (delta != 0) {
                    synchronized(lastAppliedBarSizeForHotseat) {
                        lastAppliedBarSizeForHotseat[hotseat] = targetBarSize
                    }
                    (specSize + delta).coerceAtLeast(0)
                } else {
                    specSize
                }
            }
            targetBarSize != null && specSize == targetBarSize -> specSize
            else -> (specSize * heightPercent / 100f).roundToInt().coerceAtLeast(0)
        }

        val lp = hotseat.layoutParams
        if (lp != null && lp.height > 0 && lp.height != adjustedSize) {
            lp.height = adjustedSize
        }

        return adjustedSize
    }

    private fun recordAppliedBarSizeForHotseat(hotseat: View, heightPercent: Int) {
        val profile = findDeviceProfile(hotseat)
        val scaled = if (profile != null) {
            applyDeviceProfileBarSize(profile, heightPercent)
        } else {
            null
        }
        if (scaled != null) {
            synchronized(lastAppliedBarSizeForHotseat) {
                lastAppliedBarSizeForHotseat[hotseat] = scaled
            }
        }
    }

    /**
     * Updates `hotseatBarSizePx` on the Hotseat's [DeviceProfile] and any other
     * tracked [DeviceProfile] instances.
     */
    private fun syncDeviceProfiles(hotseat: View, heightPercent: Int): Int? {
        var result: Int? = null
        val activeProfile = findDeviceProfile(hotseat)
        if (activeProfile != null) {
            result = applyDeviceProfileBarSize(activeProfile, heightPercent)
        }

        val profiles = synchronized(originalHotseatBarSizes) {
            originalHotseatBarSizes.keys.toList()
        }
        for (profile in profiles) {
            val scaled = applyDeviceProfileBarSize(profile, heightPercent)
            if (result == null && scaled != null) {
                result = scaled
            }
        }
        return result
    }

    private fun captureOriginalHotseatBarSize(profile: Any): Int? {
        val current = getHotseatBarSizeField(profile) ?: return null
        if (current <= 0) return null
        synchronized(originalHotseatBarSizes) {
            val lastScaled = lastScaledHotseatBarSizes[profile]
            val existingBase = originalHotseatBarSizes[profile]
            if (existingBase == null || (lastScaled != null && current != lastScaled)) {
                originalHotseatBarSizes[profile] = current
                return current
            }
            return existingBase
        }
    }

    private fun applyDeviceProfileBarSize(profile: Any, heightPercent: Int): Int? {
        val baseSize = captureOriginalHotseatBarSize(profile) ?: return null
        val scaledSize = (baseSize * heightPercent / 100f)
            .roundToInt()
            .coerceAtLeast(0)

        synchronized(originalHotseatBarSizes) {
            lastScaledHotseatBarSizes[profile] = scaledSize
        }
        setHotseatBarSizeField(profile, scaledSize)
        return scaledSize
    }

    private fun restoreAllDeviceProfiles() {
        val entries = synchronized(originalHotseatBarSizes) {
            val snapshot = originalHotseatBarSizes.entries.map { it.key to it.value }
            lastScaledHotseatBarSizes.clear()
            snapshot
        }
        for ((profile, originalSize) in entries) {
            runCatching {
                setHotseatBarSizeField(profile, originalSize)
            }
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

    private fun getHotseatBarSizeField(profile: Any): Int? = runCatching {
        findField(profile.javaClass, FIELD_HOTSEAT_BAR_SIZE_PX)?.getInt(profile)
    }.getOrNull()

    private fun setHotseatBarSizeField(profile: Any, value: Int) {
        runCatching {
            findField(profile.javaClass, FIELD_HOTSEAT_BAR_SIZE_PX)?.setInt(profile, value)
        }
    }

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

    private fun hasMethod(type: Class<*>?, name: String): Boolean {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            if (current.declaredMethods.any { it.name == name }) {
                return true
            }
            current = current.superclass
        }
        return false
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
                scaleX = view.scaleX,
                scaleY = view.scaleY,
                background = view.background.takeUnless { it is CenteredDockGradientDrawable },
                willNotDraw = view.willNotDraw(),
                layoutHeight = view.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun restoreOriginalState(
        view: View,
        preserveCurrentPaddingAndHeight: Boolean = false
    ) {
        val original = synchronized(originalStates) { originalStates.remove(view) }
        val generatedBg = synchronized(generatedBackgrounds) { generatedBackgrounds.remove(view) }
        synchronized(lastAppliedBarSizeForHotseat) { lastAppliedBarSizeForHotseat.remove(view) }

        if (original == null) {
            if (view.background is CenteredDockGradientDrawable) {
                view.background = null
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

        if (view.scaleX != original.scaleX) {
            view.scaleX = original.scaleX
        }
        if (view.scaleY != original.scaleY) {
            view.scaleY = original.scaleY
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
