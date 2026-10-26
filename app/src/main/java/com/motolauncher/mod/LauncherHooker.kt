package com.motolauncher.mod

import android.content.Context
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.xposed.prefs.YukiHookPrefsBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field

/**
 * All hooks are installed inside `loadApp("com.motorola.launcher3")`.
 *
 * Settings are read from the YukiHookAPI prefs bridge each time a hook runs, so
 * the values written by [MainActivity] are used without rebuilding or
 * recompiling the module. Layout / shape changes still need a launcher restart
 * because they are consumed while the launcher builds its views and icons.
 *
 * Every hook and every callback is wrapped in [runCatching], so a missing class
 * or a changed signature only disables that single feature.
 */
object LauncherHooker {

    const val TARGET_PACKAGE: String = HookConfig.TARGET_PACKAGE

    private const val TAG = "MotoLauncherMod"

    private const val CLASS_BUBBLE_TEXT_VIEW = "com.android.launcher3.BubbleTextView"
    private const val CLASS_INVARIANT_DEVICE_PROFILE = "com.android.launcher3.InvariantDeviceProfile"
    private const val CLASS_DEVICE_PROFILE = "com.android.launcher3.DeviceProfile"
    private const val CLASS_BASE_ICON_FACTORY = "com.android.launcher3.icons.BaseIconFactory"
    private const val CLASS_ICON_OPTIONS = "com.android.launcher3.icons.BaseIconFactory\$IconOptions"

    fun install(packageParam: PackageParam, prefs: YukiHookPrefsBridge?) = with(packageParam) {
        hookAppLabels(prefs)
        hookWorkspaceGrid(prefs)
        hookDeviceProfile(prefs)
        hookSquareGridCells(prefs)
        hookIconShape(prefs)
    }

    /**
     * 1. Hide icon labels.
     *
     * The preference is checked in the callback, not at install time, so toggling
     * the switch takes effect on the next label update without a re-hook.
     */
    private fun PackageParam.hookAppLabels(prefs: YukiHookPrefsBridge?) {
        runCatching {
            loadClass(CLASS_BUBBLE_TEXT_VIEW)
                .resolve()
                .firstMethod {
                    name = "setTextVisibility"
                    parameters(Boolean::class)
                }
                .hook {
                    before {
                        runCatching {
                            if (HookConfig.safeGetBoolean(
                                    prefs,
                                    HookConfig.KEY_HIDE_LABELS,
                                    HookConfig.DEFAULT_HIDE_LABELS
                                )
                            ) {
                                args[0] = false
                            }
                        }.onFailure {
                            Log.w(TAG, "Failed to apply hide_labels", it)
                        }
                    }
                }

            Log.i(TAG, "Hooked BubbleTextView.setTextVisibility")
        }.onFailure {
            Log.e(TAG, "Failed to hook BubbleTextView.setTextVisibility", it)
        }
    }

    /**
     * 2. Change the workspace grid and Hotseat count.
     *
     * `InvariantDeviceProfile` is built before `DeviceProfile` / `CellLayout`, so
     * changing the fields right after the constructor lets the launcher
     * recalculate cell sizes for the new grid. Values are 4..10 and are kept
     * independent: the dock count is no longer clamped to the column count.
     */
    private fun PackageParam.hookWorkspaceGrid(prefs: YukiHookPrefsBridge?) {
        runCatching {
            loadClass(CLASS_INVARIANT_DEVICE_PROFILE)
                .resolve()
                .firstConstructor {
                    parameters(Context::class)
                }
                .hook {
                    after {
                        runCatching {
                            val profile = instance

                            val columns = readGridValue(
                                prefs,
                                HookConfig.KEY_GRID_COLUMNS,
                                HookConfig.DEFAULT_GRID_COLUMNS
                            )
                            val rows = readGridValue(
                                prefs,
                                HookConfig.KEY_GRID_ROWS,
                                HookConfig.DEFAULT_GRID_ROWS
                            )
                            val dockIcons = readGridValue(
                                prefs,
                                HookConfig.KEY_DOCK_ICONS,
                                HookConfig.DEFAULT_DOCK_ICONS
                            )

                            setIntField(profile, "numColumns", columns)
                            setIntField(profile, "numRows", rows)
                            setIntField(profile, "numShownHotseatIcons", dockIcons)
                            runCatching { setIntField(profile, "numHotseatIcons", dockIcons) }
                            runCatching {
                                val currentDbIcons = getIntField(profile, "numDatabaseHotseatIcons")
                                if (currentDbIcons < dockIcons) {
                                    setIntField(profile, "numDatabaseHotseatIcons", dockIcons)
                                }
                            }

                            Log.i(
                                TAG,
                                "Grid applied: ${columns}x$rows, dock=$dockIcons " +
                                    "(idp hotseat shown=${getIntField(profile, "numShownHotseatIcons")}, " +
                                    "total=${getIntField(profile, "numHotseatIcons")}, " +
                                    "db=${getIntField(profile, "numDatabaseHotseatIcons")})"
                            )
                        }.onFailure {
                            Log.w(TAG, "Failed to apply grid override", it)
                        }
                    }
                }

            Log.i(TAG, "Hooked InvariantDeviceProfile(Context)")
        }.onFailure {
            Log.e(TAG, "Failed to hook InvariantDeviceProfile(Context)", it)
        }
    }

    /**
     * The actual grid is read from the InvariantDeviceProfile instance passed
     * to DeviceProfile(13 args). That instance can be a copy created before the
     * InvariantDeviceProfile hook ran, so write the grid into `args[1]` in the
     * DeviceProfile constructor's before callback. DeviceProfile then computes
     * cell sizes with the new grid, and the after callback also pins the
     * Hotseat count on the DeviceProfile itself.
     */
    private fun PackageParam.hookDeviceProfile(prefs: YukiHookPrefsBridge?) {
        runCatching {
            loadClass(CLASS_DEVICE_PROFILE)
                .resolve()
                .firstConstructor {
                    parameterCount = 13
                }
                .hook {
                    before {
                        runCatching {
                            if (args.size < 2) return@runCatching
                            val inv = args[1] ?: return@runCatching

                            val columns = readGridValue(
                                prefs,
                                HookConfig.KEY_GRID_COLUMNS,
                                HookConfig.DEFAULT_GRID_COLUMNS
                            )
                            val rows = readGridValue(
                                prefs,
                                HookConfig.KEY_GRID_ROWS,
                                HookConfig.DEFAULT_GRID_ROWS
                            )
                            val dockIcons = readGridValue(
                                prefs,
                                HookConfig.KEY_DOCK_ICONS,
                                HookConfig.DEFAULT_DOCK_ICONS
                            )

                            // The constructor runs for every icon load, so the
                            // log is only written when something really
                            // changes. A log line per icon would flood the
                            // logcat buffer and push every other marker
                            // (including the scope markers) out of it.
                            val currentColumns = getIntField(inv, "numColumns")
                            val currentRows = getIntField(inv, "numRows")
                            if (currentColumns != columns || currentRows != rows) {
                                Log.i(
                                    TAG,
                                    "DeviceProfile before: inv=${currentColumns}x" +
                                        "$currentRows -> ${columns}x$rows, dock=$dockIcons"
                                )
                            }

                            setIntField(inv, "numColumns", columns)
                            setIntField(inv, "numRows", rows)
                            setIntField(inv, "numShownHotseatIcons", dockIcons)
                            runCatching { setIntField(inv, "numHotseatIcons", dockIcons) }
                            runCatching {
                                val currentDbIcons = getIntField(inv, "numDatabaseHotseatIcons")
                                if (currentDbIcons < dockIcons) {
                                    setIntField(inv, "numDatabaseHotseatIcons", dockIcons)
                                }
                            }
                        }.onFailure {
                            Log.w(TAG, "Failed to apply grid before DeviceProfile", it)
                        }
                    }

                    after {
                        runCatching {
                            val profile = instance
                            val dockIcons = readGridValue(
                                prefs,
                                HookConfig.KEY_DOCK_ICONS,
                                HookConfig.DEFAULT_DOCK_ICONS
                            )
                            val previousDock = getIntField(profile, "numShownHotseatIcons")
                            setIntField(profile, "numShownHotseatIcons", dockIcons)

                            // Optimize Hotseat border space and layout padding on Cover Screen (swDp < 600)
                            // when 5 or 6+ icons are displayed, avoiding excessive side margins or negative gaps.
                            val context = args.getOrNull(0) as? Context
                            if (isCoverScreenProfile(profile, context) &&
                                HookConfig.isCoverDockEnabled(prefs)
                            ) {
                                optimizeCoverDeviceProfileHotseat(profile, dockIcons)
                            }

                            if (previousDock != dockIcons) {
                                val inv = findField(profile.javaClass, "inv").get(profile)
                                Log.i(
                                    TAG,
                                    "DeviceProfile after: inv=${inv?.let { getIntField(it, "numColumns") }}x" +
                                        "${inv?.let { getIntField(it, "numRows") }}, " +
                                        "profileDock=${getIntField(profile, "numShownHotseatIcons")}"
                                )
                            }
                        }.onFailure {
                            Log.w(TAG, "Failed to apply DeviceProfile hotseat count", it)
                        }
                    }
                }

            Log.i(TAG, "Hooked DeviceProfile(13 args) for grid and Hotseat")
        }.onFailure {
            Log.e(TAG, "Failed to hook DeviceProfile(13 args)", it)
        }
    }

    /**
     * CellLayout recomputes its dimensions on every measure. Apply the square
     * immediately before its child container measures, after that calculation,
     * so both hit testing and child layout use the same dimensions. Never pin
     * mFixedCellWidth/Height: the next measure must adapt to fold/rotation.
     */
    private fun PackageParam.hookSquareGridCells(prefs: YukiHookPrefsBridge?) {
        fun enabled() = HookConfig.safeGetBoolean(
            prefs, HookConfig.KEY_SQUARE_GRID_CELLS, HookConfig.DEFAULT_SQUARE_GRID_CELLS
        )

        runCatching {
            // Widget span/scale calculations use this terminal overload.
            loadClass(CLASS_DEVICE_PROFILE).resolve().firstMethod {
                name = "getCellSize"
                parameterCount = 4
            }.hook {
                after {
                    runCatching {
                        if (!enabled()) return@runCatching
                        val size = result as? Point ?: return@runCatching
                        val side = minOf(size.x, size.y)
                        if (side > 0) size.set(side, side)
                    }.onFailure { ErrorLogger.log(TAG, "Square grid profile size failed", it) }
                }
            }
        }.onFailure { ErrorLogger.log(TAG, "Could not hook square grid profile size", it) }

        runCatching {
            val cellClass = loadClass("com.android.launcher3.CellLayout")
            val containerClass = loadClass("com.android.launcher3.ShortcutAndWidgetContainer")
            val cellWidth = findField(cellClass, "mCellWidth")
            val cellHeight = findField(cellClass, "mCellHeight")
            val containerWidth = findField(containerClass, "mCellWidth")
            val containerHeight = findField(containerClass, "mCellHeight")
            val containerType = findField(containerClass, "mContainerType")
            val originalHeightKey = "moto_square_grid_original_height"
            val paddingKey = "moto_square_grid_padding"
            val appliedPaddingKey = "moto_square_grid_applied_padding"
            val naturalHeightKey = "moto_square_grid_natural_height"

            cellClass.resolve().firstMethod {
                name = "onMeasure"
                parameters(Int::class, Int::class)
            }.hook {
                before {
                    runCatching {
                        if (getIntField(instance, "mContainerType") != 0) return@runCatching
                        val cell = instance as View
                        val current = Rect(cell.paddingLeft, cell.paddingTop, cell.paddingRight, cell.paddingBottom)
                        val applied = XposedHelpers.getAdditionalInstanceField(cell, appliedPaddingKey) as? Rect
                        val saved = XposedHelpers.getAdditionalInstanceField(cell, paddingKey) as? Rect
                        val base = if (current == applied && saved != null) saved else current
                        val target = Rect(base)
                        if (enabled()) {
                            // Keep visual bounds and drag coordinates together by
                            // changing CellLayout padding, never translating icons.
                            val width = View.MeasureSpec.getSize(args[0] as Int) - base.left - base.right
                            val height = View.MeasureSpec.getSize(args[1] as Int) - base.top - base.bottom
                            val columns = getIntField(cell, "mCountX")
                            val rows = getIntField(cell, "mCountY")
                            val gap = findField(cellClass, "mBorderSpace").get(cell) as Point
                            val callback = findField(cellClass, "mShortcutsAndWidgetLayoutCallback").get(cell)
                            if (columns > 0 && rows > 0 && callback == null) {
                                val naturalWidth = (width - (columns - 1) * gap.x) / columns
                                val naturalHeight = (height - (rows - 1) * gap.y) / rows
                                val side = minOf(naturalWidth, naturalHeight)
                                if (side > 0) {
                                    val extraX = (width - columns * side - (columns - 1) * gap.x).coerceAtLeast(0)
                                    val extraY = (height - rows * side - (rows - 1) * gap.y).coerceAtLeast(0)
                                    target.left += extraX / 2
                                    target.right += extraX - extraX / 2
                                    target.top += extraY / 2
                                    target.bottom += extraY - extraY / 2
                                    XposedHelpers.setAdditionalInstanceField(cell, naturalHeightKey, naturalHeight)
                                }
                            }
                        }
                        XposedHelpers.setAdditionalInstanceField(cell, paddingKey, base)
                        XposedHelpers.setAdditionalInstanceField(cell, appliedPaddingKey, target)
                        if (current != target) cell.setPadding(target.left, target.top, target.right, target.bottom)
                    }.onFailure { ErrorLogger.log(TAG, "Square grid padding failed", it) }
                }
            }

            containerClass.resolve().firstMethod {
                name = "onMeasure"
                parameters(Int::class, Int::class)
            }.hook {
                before {
                    runCatching {
                        if (!enabled() || containerType.getInt(instance) != 0) return@runCatching
                        val container = instance as View
                        val cell = container.parent ?: return@runCatching
                        if (!cellClass.isInstance(cell)) return@runCatching
                        val width = cellWidth.getInt(cell)
                        val height = cellHeight.getInt(cell)
                        val side = minOf(width, height)
                        if (side <= 0) return@runCatching
                        val naturalHeight = XposedHelpers.getAdditionalInstanceField(cell, naturalHeightKey) as? Int
                        XposedHelpers.setAdditionalInstanceField(container, originalHeightKey, naturalHeight ?: height)
                        cellWidth.setInt(cell, side)
                        cellHeight.setInt(cell, side)
                        containerWidth.setInt(container, side)
                        containerHeight.setInt(container, side)
                    }.onFailure { ErrorLogger.log(TAG, "Square grid cell measure failed", it) }
                }
            }

            val iconClass = loadClass(CLASS_BUBBLE_TEXT_VIEW)
            val folderClass = loadClass("com.android.launcher3.folder.FolderIcon")
            val activityField = findField(containerClass, "mActivity")
            containerClass.resolve().firstMethod {
                name = "measureChild"
                parameters(View::class)
            }.hook {
                after {
                    runCatching {
                        if (!enabled() || containerType.getInt(instance) != 0) return@runCatching
                        val child = args[0] as? View ?: return@runCatching
                        if (!iconClass.isInstance(child) && !folderClass.isInstance(child)) return@runCatching
                        val originalHeight = XposedHelpers.getAdditionalInstanceField(
                            instance, originalHeightKey
                        ) as? Int ?: return@runCatching
                        val activity = activityField.get(instance) ?: return@runCatching
                        val profile = XposedHelpers.callMethod(activity, "getDeviceProfile")
                            ?: return@runCatching
                        // Negative padding means stock already centered using the
                        // actual (now square) layout height. Do not subtract twice.
                        if (getIntField(profile, "cellYPaddingPx") < 0) return@runCatching
                        val reduction = (originalHeight - containerHeight.getInt(instance)).coerceAtLeast(0)
                        val top = (child.paddingTop - reduction / 2).coerceAtLeast(0)
                        if (top == child.paddingTop) return@runCatching
                        val width = child.measuredWidth
                        val height = child.measuredHeight
                        child.setPadding(child.paddingLeft, top, child.paddingRight, child.paddingBottom)
                        child.measure(
                            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                        )
                    }.onFailure { ErrorLogger.log(TAG, "Square grid icon padding failed", it) }
                }
            }
        }.onFailure { ErrorLogger.log(TAG, "Could not hook square grid cells", it) }
    }

    private fun isCoverScreenProfile(profile: Any, context: Context?): Boolean = runCatching {
        val isTablet = runCatching { findField(profile.javaClass, "isTablet").getBoolean(profile) }.getOrDefault(false)
        val isTwoPanels = runCatching { findField(profile.javaClass, "isTwoPanels").getBoolean(profile) }.getOrDefault(false)
        if (isTablet || isTwoPanels) return@runCatching false

        val widthPx = runCatching { getIntField(profile, "widthPx") }.getOrDefault(0)
        val availableWidthPx = runCatching { getIntField(profile, "availableWidthPx") }.getOrDefault(0)
        if (widthPx >= 1500 || availableWidthPx >= 1500) return@runCatching false

        val swDp = context?.resources?.configuration?.smallestScreenWidthDp ?: 0
        if (swDp >= 600) return@runCatching false

        widthPx in 1 until 1500 || swDp in 1 until 600
    }.getOrDefault(false)

    private fun optimizeCoverDeviceProfileHotseat(profile: Any, dockIcons: Int) {
        runCatching {
            val currentBorder = runCatching { getIntField(profile, "hotseatBorderSpace") }.getOrDefault(0)
            if (dockIcons >= 5) {
                val maxBorderDp = if (dockIcons >= 6) 6f else 10f
                val density = runCatching {
                    val metrics = findField(profile.javaClass, "mMetrics").get(profile) as? android.util.DisplayMetrics
                    metrics?.density
                }.getOrNull() ?: 2.625f
                val maxBorderPx = (maxBorderDp * density).toInt().coerceAtLeast(0)
                val safeBorder = currentBorder.coerceIn(0, maxBorderPx)
                if (currentBorder != safeBorder) {
                    setIntField(profile, "hotseatBorderSpace", safeBorder)
                }
            } else if (currentBorder < 0) {
                setIntField(profile, "hotseatBorderSpace", 0)
            }
        }
    }

    private fun readGridValue(
        prefs: YukiHookPrefsBridge?,
        key: String,
        defaultValue: Int
    ): Int = HookConfig.coerceGridValue(
        HookConfig.safeGetInt(prefs, key, defaultValue),
        defaultValue
    )

    /**
     * 3. Change the icon shape.
     *
     * Always installs the hook without reading XSharedPreferences at
     * handleLoadPackage time (which may run during Direct Boot / soft reboot).
     * When the preference is SYSTEM at callback time, the launcher's own
     * adaptive icon mask is left untouched.
     */
    private fun PackageParam.hookIconShape(prefs: YukiHookPrefsBridge?) {
        runCatching {
            val iconOptionsClass = loadClass(CLASS_ICON_OPTIONS)

            loadClass(CLASS_BASE_ICON_FACTORY)
                .resolve()
                .firstMethod {
                    name = "createBadgedIconBitmap"
                    parameters(
                        Drawable::class,
                        iconOptionsClass,
                        FloatArray::class,
                        Boolean::class
                    )
                }
                .hook {
                    before {
                        runCatching {
                            val shape = readIconShape(prefs)
                            if (shape == HookConfig.IconShape.SYSTEM) return@runCatching

                            val options = args[1] ?: return@runCatching
                            val mask = buildMaskPath(shape)
                            findField(options.javaClass, "mShape").set(options, mask)
                        }.onFailure {
                            Log.w(TAG, "Failed to inject icon shape", it)
                        }
                    }
                }

            Log.i(TAG, "Hooked BaseIconFactory.createBadgedIconBitmap")
        }.onFailure {
            Log.e(TAG, "Failed to hook BaseIconFactory.createBadgedIconBitmap", it)
        }
    }

    private fun readIconShape(prefs: YukiHookPrefsBridge?): HookConfig.IconShape =
        HookConfig.IconShape.fromKey(
            HookConfig.safeGetString(prefs, HookConfig.KEY_ICON_SHAPE, HookConfig.DEFAULT_ICON_SHAPE)
        )

    /**
     * Builds the icon mask in the 0..100 coordinate space used by
     * `DynamicAdaptiveIconDrawable.updateMaskBoundsInternal()`.
     */
    private fun buildMaskPath(shape: HookConfig.IconShape): Path = when (shape) {
        HookConfig.IconShape.SQUIRCLE -> Path().apply {
            val center = 50f
            val radius = 50f
            val handle = radius * 0.3f

            moveTo(center, center - radius)
            cubicTo(
                center - handle, center - radius,
                center - radius, center - handle,
                center - radius, center
            )
            cubicTo(
                center - radius, center + handle,
                center - handle, center + radius,
                center, center + radius
            )
            cubicTo(
                center + handle, center + radius,
                center + radius, center + handle,
                center + radius, center
            )
            cubicTo(
                center + radius, center - handle,
                center + handle, center - radius,
                center, center - radius
            )
            close()
        }

        HookConfig.IconShape.CIRCLE -> Path().apply {
            addCircle(50f, 50f, 50f, Path.Direction.CW)
        }

        HookConfig.IconShape.SQUARE -> Path().apply {
            addRect(0f, 0f, 100f, 100f, Path.Direction.CW)
        }

        HookConfig.IconShape.DIAMOND -> Path().apply {
            moveTo(50f, 0f)
            lineTo(100f, 50f)
            lineTo(50f, 100f)
            lineTo(0f, 50f)
            close()
        }

        // Never reached: SYSTEM bypasses the hook before a path is requested.
        HookConfig.IconShape.SYSTEM -> Path()
    }

    private fun PackageParam.loadClass(name: String): Class<*> {
        val loader = appClassLoader ?: PackageParam::class.java.classLoader
        return Class.forName(name, false, loader)
    }

    private fun findField(type: Class<*>, name: String): Field {
        var current: Class<*>? = type
        while (current != null) {
            val currentClass = current
            val field = runCatching { currentClass.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                return field.apply { isAccessible = true }
            }
            current = currentClass.superclass
        }
        throw NoSuchFieldException("Field '$name' not found in ${type.name}")
    }

    private fun setIntField(target: Any, name: String, value: Int) {
        findField(target.javaClass, name).setInt(target, value)
    }

    private fun getIntField(target: Any, name: String): Int =
        findField(target.javaClass, name).getInt(target)
}
