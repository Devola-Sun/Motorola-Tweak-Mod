package com.motolauncher.mod.hooks

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.type.java.IntType
import com.motolauncher.mod.HookConfig
import java.lang.reflect.Field
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
    private const val DEFAULT_VERTICAL_PADDING_DP = 8f

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
        ) {
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
        }

        override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
            rawLeft = left
            rawTop = top
            rawRight = right
            rawBottom = bottom
            hasRawBounds = true
            applyCenteredBounds(left, top, right, bottom)
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
                cornerRadius = if (maxRadius > 0f) {
                    baseCornerRadiusPx.coerceAtMost(maxRadius)
                } else {
                    baseCornerRadiusPx
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
        hookVisibility()
        hookHotseatLifecycle()
        hookMeasure()
        hookTouchRouting()
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
                        runCatching {
                            if (!isHotseatInstance(instance)) return@runCatching
                            val hotseat = instance as? View ?: return@runCatching
                            if (isCoverDockActive(hotseat)) {
                                args[0] = View.VISIBLE
                            }
                        }.onFailure {
                            Log.w(TAG, "Failed to apply cover dock visibility", it)
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
     * 3. DeviceProfile.hotseatBarSizePx is kept synchronized with the height slider.
     */
    private fun hookHotseatLifecycle() {
        runCatching {
            val hotseatClass = findClass(CLASS_HOTSEAT)

            hotseatClass.hook {
                runCatching {
                    injectMember {
                        allConstructors()
                        afterHook {
                            runCatching {
                                val hotseat = instance as? View ?: return@runCatching
                                installAttachListener(hotseat)
                                if (!isCoverDockActive(hotseat)) {
                                    restoreOriginalState(hotseat)
                                    return@runCatching
                                }

                                val heightPercent = HookConfig.coverDockHeightPercent(modulePrefs)
                                val widthPercent = HookConfig.coverDockWidthPercent(modulePrefs)
                                originalState(hotseat)
                                applyDockBackgroundAndPadding(hotseat, heightPercent, widthPercent)
                                unclipParentHierarchy(hotseat)
                            }.onFailure {
                                Log.w(TAG, "Failed to initialize cover dock in constructor", it)
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
                            runCatching {
                                if (!isHotseatInstance(instance)) return@runCatching
                                val hotseat = instance as? View ?: return@runCatching
                                onHotseatAttached(hotseat)
                            }.onFailure {
                                Log.w(TAG, "Failed in Hotseat.onAttachedToWindow", it)
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
                            runCatching {
                                if (!isHotseatInstance(instance)) return@runCatching
                                val hotseat = instance as? View ?: return@runCatching
                                if (!isCoverDockActive(hotseat)) {
                                    restoreOriginalState(hotseat)
                                    return@runCatching
                                }

                                synchronized(originalStates) {
                                    val existing = originalStates[hotseat]
                                    if (existing != null) {
                                        originalStates[hotseat] = existing.copy(
                                            paddingTop = hotseat.paddingTop,
                                            paddingBottom = hotseat.paddingBottom
                                        )
                                    }
                                }

                                val heightPercent = HookConfig.coverDockHeightPercent(modulePrefs)
                                val widthPercent = HookConfig.coverDockWidthPercent(modulePrefs)
                                applyDockBackgroundAndPadding(hotseat, heightPercent, widthPercent)
                                unclipParentHierarchy(hotseat)
                            }.onFailure {
                                Log.w(TAG, "Failed after Hotseat.setInsets", it)
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
                            runCatching {
                                if (!isHotseatInstance(instance)) return@runCatching
                                val hotseat = instance as? View ?: return@runCatching
                                if (!isCoverDockActive(hotseat)) {
                                    restoreOriginalState(hotseat)
                                    return@runCatching
                                }

                                val heightPercent = HookConfig.coverDockHeightPercent(modulePrefs)
                                val widthPercent = HookConfig.coverDockWidthPercent(modulePrefs)
                                applyDockBackgroundAndPadding(hotseat, heightPercent, widthPercent)
                                unclipParentHierarchy(hotseat)
                            }.onFailure {
                                Log.w(TAG, "Failed after Hotseat.onLayout", it)
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
                            runCatching {
                                if (!isHotseatInstance(instance)) return@runCatching
                                val hotseat = instance as? View ?: return@runCatching
                                if (!isCoverDockActive(hotseat)) {
                                    restoreOriginalState(hotseat)
                                    return@runCatching
                                }

                                val heightPercent = HookConfig.coverDockHeightPercent(modulePrefs)
                                val widthPercent = HookConfig.coverDockWidthPercent(modulePrefs)
                                applyDockBackgroundAndPadding(hotseat, heightPercent, widthPercent)
                                unclipParentHierarchy(hotseat)
                            }.onFailure {
                                Log.w(TAG, "Failed in Hotseat.onConfigurationChanged", it)
                            }
                        }
                    }
                }.onFailure {
                    Log.w(TAG, "Could not hook Hotseat.onConfigurationChanged", it)
                }

                listOf("resetLayout", "layoutBgView").forEach { methodName ->
                    runCatching {
                        injectMember {
                            method {
                                name = methodName
                                paramCount(0..2)
                            }.all()
                            afterHook {
                                runCatching {
                                    if (!isHotseatInstance(instance)) return@runCatching
                                    val hotseat = instance as? View ?: return@runCatching
                                    if (!isCoverDockActive(hotseat)) {
                                        restoreOriginalState(hotseat)
                                        return@runCatching
                                    }

                                    val heightPercent =
                                        HookConfig.coverDockHeightPercent(modulePrefs)
                                    val widthPercent =
                                        HookConfig.coverDockWidthPercent(modulePrefs)
                                    applyDockBackgroundAndPadding(
                                        hotseat,
                                        heightPercent,
                                        widthPercent
                                    )
                                    unclipParentHierarchy(hotseat)
                                }.onFailure {
                                    Log.w(TAG, "Failed in Hotseat.$methodName", it)
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
                        runCatching {
                            if (!isHotseatInstance(instance)) return@runCatching
                            val hotseat = instance as? View ?: return@runCatching
                            if (!isCoverDockActive(hotseat)) {
                                restoreOriginalState(hotseat)
                                return@runCatching
                            }

                            val heightPercent = HookConfig.coverDockHeightPercent(modulePrefs)
                            val widthPercent = HookConfig.coverDockWidthPercent(modulePrefs)
                            val sizePercent = HookConfig.coverDockSizePercent(modulePrefs)
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
                                measuredWidth = measuredWidth
                            )
                            unclipParentHierarchy(hotseat)

                            val scale = sizePercent / 100f
                            hotseat.scaleX = original.scaleX * scale
                            hotseat.scaleY = original.scaleY * scale
                        }.onFailure {
                            Log.w(TAG, "Failed to apply cover dock size", it)
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
                            runCatching {
                                if (!isHotseatInstance(instance)) return@runCatching
                                val hotseat = instance as? View ?: return@runCatching
                                if (!isCoverDockActive(hotseat)) {
                                    return@runCatching
                                }

                                val widthPercent = HookConfig.coverDockWidthPercent(modulePrefs)
                                if (widthPercent >= 100) return@runCatching

                                val ev = args.getOrNull(0) as? MotionEvent ?: return@runCatching
                                val viewWidth = hotseat.width
                                if (viewWidth <= 0) return@runCatching

                                val horizontalInset = computeHorizontalInset(viewWidth, widthPercent)
                                val leftBound = horizontalInset.toFloat()
                                val rightBound = (viewWidth - horizontalInset).toFloat()
                                if (ev.x < leftBound || ev.x > rightBound) {
                                    result = false
                                }
                            }.onFailure {
                                Log.w(TAG, "Failed in Hotseat.dispatchTouchEvent", it)
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
                            runCatching {
                                if (!isHotseatInstance(instance)) return@runCatching
                                val hotseat = instance as? View ?: return@runCatching
                                if (!isCoverDockActive(hotseat)) {
                                    return@runCatching
                                }

                                val widthPercent = HookConfig.coverDockWidthPercent(modulePrefs)
                                if (widthPercent >= 100) return@runCatching

                                val outRect = args.getOrNull(0) as? Rect ?: return@runCatching
                                val fullWidth = (outRect.right - outRect.left).coerceAtLeast(0)
                                val horizontalInset = computeHorizontalInset(fullWidth, widthPercent)
                                if (horizontalInset > 0 && outRect.left + horizontalInset < outRect.right - horizontalInset) {
                                    outRect.left += horizontalInset
                                    outRect.right -= horizontalInset
                                }
                            }.onFailure {
                                Log.w(TAG, "Failed in Hotseat.getHitRect", it)
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

    /**
     * Hooks `com.android.launcher3.DeviceProfile` so `hotseatBarSizePx` and
     * `getHotseatBarSize()` reflect the configured cover-dock height (px).
     */
    private fun hookDeviceProfile() {
        runCatching {
            val deviceProfileHookClass = findClass(CLASS_DEVICE_PROFILE)
            val deviceProfileClass = runCatching {
                Class.forName(
                    CLASS_DEVICE_PROFILE,
                    false,
                    appClassLoader ?: CoverDockHook::class.java.classLoader
                )
            }.getOrNull()

            deviceProfileHookClass.hook {
                runCatching {
                    injectMember {
                        allConstructors()
                        afterHook {
                            runCatching {
                                val profile = instance
                                captureOriginalHotseatBarSize(profile)
                                if (!HookConfig.isCoverDockEnabled(modulePrefs)) {
                                    return@runCatching
                                }
                                val heightPercent = HookConfig.coverDockHeightPercent(modulePrefs)
                                applyDeviceProfileBarSize(profile, heightPercent)
                            }.onFailure {
                                Log.w(TAG, "Failed to update DeviceProfile in constructor", it)
                            }
                        }
                    }
                }.onFailure {
                    Log.w(TAG, "Could not hook DeviceProfile constructors", it)
                }

                listOf("getHotseatBarSize", "getHotseatBarSizePx").forEach { methodName ->
                    if (hasMethod(deviceProfileClass, methodName)) {
                        runCatching {
                            injectMember {
                                method {
                                    name = methodName
                                    paramCount(0..4)
                                }.all()
                                afterHook {
                                    runCatching {
                                        if (!HookConfig.isCoverDockEnabled(modulePrefs)) {
                                            return@runCatching
                                        }
                                        val heightPercent =
                                            HookConfig.coverDockHeightPercent(modulePrefs)
                                        val scaled =
                                            applyDeviceProfileBarSize(instance, heightPercent)
                                        val currentResult = result as? Int
                                        if (scaled != null) {
                                            result = scaled
                                        } else if (currentResult != null) {
                                            result = (currentResult * heightPercent / 100f)
                                                .roundToInt()
                                                .coerceAtLeast(0)
                                        }
                                    }.onFailure {
                                        Log.w(TAG, "Failed in DeviceProfile.$methodName", it)
                                    }
                                }
                            }
                        }.onFailure {
                            Log.w(TAG, "Could not hook DeviceProfile.$methodName", it)
                        }
                    }
                }

                listOf(
                    "updateSizes",
                    "updateHotseatSizes",
                    "updateHotseatIconSize",
                    "updateIsSeascape"
                ).forEach { methodName ->
                    if (hasMethod(deviceProfileClass, methodName)) {
                        runCatching {
                            injectMember {
                                method {
                                    name = methodName
                                    paramCount(0..6)
                                }.all()
                                afterHook {
                                    runCatching {
                                        if (!HookConfig.isCoverDockEnabled(modulePrefs)) {
                                            return@runCatching
                                        }
                                        val heightPercent =
                                            HookConfig.coverDockHeightPercent(modulePrefs)
                                        applyDeviceProfileBarSize(instance, heightPercent)
                                    }.onFailure {
                                        Log.w(TAG, "Failed in DeviceProfile.$methodName", it)
                                    }
                                }
                            }
                        }.onFailure {
                            Log.w(TAG, "Could not hook DeviceProfile.$methodName", it)
                        }
                    }
                }
            }

            Log.i(TAG, "Hooked DeviceProfile for cover dock touch area")
        }.onFailure {
            Log.w(TAG, "Could not hook DeviceProfile", it)
        }
    }

    private fun installAttachListener(hotseat: View) {
        synchronized(attachedListeners) {
            if (attachedListeners.containsKey(hotseat)) return
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    runCatching { onHotseatAttached(v) }
                }

                override fun onViewDetachedFromWindow(v: View) = Unit
            }
            attachedListeners[hotseat] = listener
            hotseat.addOnAttachStateChangeListener(listener)
        }
    }

    private fun onHotseatAttached(hotseat: View) {
        if (!isCoverDockActive(hotseat)) {
            restoreOriginalState(hotseat)
            return
        }

        val heightPercent = HookConfig.coverDockHeightPercent(modulePrefs)
        val widthPercent = HookConfig.coverDockWidthPercent(modulePrefs)
        originalState(hotseat)
        applyDockBackgroundAndPadding(hotseat, heightPercent, widthPercent)
        unclipParentHierarchy(hotseat)
    }

    /**
     * True only when the cover-dock setting is enabled AND [hotseat] is
     * currently displayed on the outer cover screen.
     */
    private fun isCoverDockActive(hotseat: View): Boolean {
        if (!HookConfig.isCoverDockEnabled(modulePrefs)) return false
        return isCoverScreen(hotseat)
    }

    /**
     * 実測値に基づくカバースクリーン（外側画面）判定:
     * - Motorola Razr Fold 実機値:
     *   - カバースクリーン（閉じた状態）: `1080x2520` (`minDim = 1080`, `swDp = 411`), `Hotseat.isBgVisible() = false`
     *   - インナースクリーン（開いた状態）: `2232x2484` (`minDim = 2232`, `swDp = 850`), `Hotseat.isBgVisible() = true`
     * - Motorola Razr Flip (Clamshell):
     *   - カバースクリーン: `1056x1066` / `1272x1080` (`minDim = 1056〜1080`, `swDp < 600`)
     * - いずれも「短辺 `minDim < 1600`（かつ `smallestScreenWidthDp < 600`）かつ標準の `isBgVisible()` が false」のとき外側カバースクリーン。
     */
    private fun isCoverScreen(hotseat: View): Boolean {
        return runCatching {
            // 1. Hotseat#isBgVisible() が true の場合は Launcher3 標準の Dock 背景 (mBgView) が
            // 有効なメイン（インナー）画面であるため、二重描画を防ぐため false とする。
            val stockBgVisible = runCatching {
                val method = hotseat.javaClass.getDeclaredMethod("isBgVisible")
                method.isAccessible = true
                method.invoke(hotseat) as? Boolean
            }.getOrNull()
            if (stockBgVisible == true) {
                return false
            }

            val context = hotseat.context ?: return false
            isCoverScreen(context)
        }.getOrDefault(false)
    }

    private fun isCoverScreen(context: Context): Boolean {
        return runCatching {
            val config = context.resources.configuration
            if (config.smallestScreenWidthDp >= 600) {
                return false
            }
            val metrics = context.resources.displayMetrics
            val minDim = minOf(metrics.widthPixels, metrics.heightPixels)
            // サイズ未確定（0）の初期段階では誤って restoreOriginalState しないよう true 扱い
            if (minDim <= 0) {
                return true
            }
            minDim < 1600
        }.getOrDefault(false)
    }

    /**
     * Dynamically creates and assigns a rounded semi-transparent dark
     * [CenteredDockGradientDrawable] when `hotseat.background` is null or
     * transparent, and updates its horizontal (`widthPercent`) and vertical
     * (`heightPercent`) bounds while preserving the Hotseat View frame and
     * vertical icon placement.
     */
    private fun applyDockBackgroundAndPadding(
        hotseat: View,
        heightPercent: Int,
        widthPercent: Int,
        measuredWidth: Int? = null
    ) {
        val original = originalState(hotseat)
        val density = hotseat.resources.displayMetrics.density.coerceAtLeast(1f)
        val cornerRadiusPx = DOCK_CORNER_RADIUS_DP * density

        val effectiveWidth = when {
            measuredWidth != null && measuredWidth > 0 -> measuredWidth
            hotseat.width > 0 -> hotseat.width
            hotseat.measuredWidth > 0 -> hotseat.measuredWidth
            else -> hotseat.resources.displayMetrics.widthPixels
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
            val drawable = existingGenerated ?: CenteredDockGradientDrawable().also { created ->
                synchronized(generatedBackgrounds) {
                    generatedBackgrounds[hotseat] = created
                }
            }
            drawable.shape = GradientDrawable.RECTANGLE
            drawable.setColor(DOCK_BACKGROUND_COLOR)
            drawable.updateDimensions(
                newWidthPercent = widthPercent,
                newHeightPercent = heightPercent,
                topInset = topInset,
                bottomInset = bottomInset,
                cornerRadiusPx = cornerRadiusPx
            )
            if (hotseat.background !== drawable) {
                hotseat.background = drawable
            }
            if (hotseat.width > 0 && hotseat.height > 0) {
                drawable.setBounds(0, 0, hotseat.width, hotseat.height)
            }
            hotseat.clipToOutline = false
            hotseat.setWillNotDraw(false)
            hotseat.invalidate()
        }

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

    private fun restoreOriginalState(view: View) {
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
        view.scaleX = original.scaleX
        view.scaleY = original.scaleY

        if ((generatedBg != null && view.background === generatedBg) ||
            view.background is CenteredDockGradientDrawable
        ) {
            view.background = original.background
            view.setWillNotDraw(original.willNotDraw)
            view.invalidate()
        }

        val lp = view.layoutParams
        if (lp != null && original.layoutHeight > 0) {
            lp.height = original.layoutHeight
        }

        val clipEntries = synchronized(originalClipStates) {
            val snapshot = originalClipStates.entries.map { it.key to it.value }
            originalClipStates.clear()
            snapshot
        }
        for ((group, clipState) in clipEntries) {
            runCatching {
                group.clipChildren = clipState.clipChildren
                group.clipToPadding = clipState.clipToPadding
            }
        }
    }
}
