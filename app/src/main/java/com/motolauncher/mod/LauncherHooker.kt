package com.motolauncher.mod

import android.content.Context
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.util.Log
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.xposed.prefs.YukiHookPrefsBridge
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
