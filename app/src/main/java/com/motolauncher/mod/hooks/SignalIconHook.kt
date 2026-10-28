package com.motolauncher.mod.hooks

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.LevelListDrawable
import android.view.View
import android.widget.ImageView
import com.motolauncher.mod.BuildConfig
import com.motolauncher.mod.R
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.current
import com.highcapable.yukihookapi.hook.type.android.CanvasClass
import com.highcapable.yukihookapi.hook.type.android.DrawableClass
import com.highcapable.yukihookapi.hook.type.android.ImageViewClass
import com.highcapable.yukihookapi.hook.type.java.IntType

object SignalIconHook : YukiBaseHooker() {

    @Volatile
    private var currentWifiLevel: Int = 3

    @Volatile
    private var currentCellularLevel: Int = 4

    private val isReplacingDrawable = ThreadLocal<Boolean>()
    private var moduleContextCache: Context? = null
    private val isEnableIosIcons: Boolean
        get() = modulePrefs.getBoolean("sb_ios_icons", modulePrefs.getBoolean("pref_ios_icons", false))

    override fun onHook() {
        hookSignalDrawable()
        hookCellularSeparatedSignal()
        hookWifiViewModel()
        hookIconViewBinder()
        hookImageView()
    }

    /**
     * SignalDrawable の描画処理およびレベル変更処理をフック
     */
    private fun hookSignalDrawable() {
        findClass("com.android.settingslib.graph.SignalDrawable").hook {
            // コンストラクタで LevelListDrawable をラップ
            injectMember {
                allConstructors()
                afterHook {
                    if (!isEnableIosIcons) return@afterHook
                    val sd = instance as? DrawableWrapper ?: return@afterHook
                    val ctx = args.firstOrNull { it is Context } as? Context
                        ?: (sd.callback as? View)?.context
                        ?: return@afterHook
                    val levelList = getCellularLevelListDrawable(ctx)
                    if (levelList != null) {
                        sd.drawable = levelList
                    }
                }
            }

            // onLevelChange のフック（レベル変更検知）
            injectMember {
                method {
                    name = "onLevelChange"
                    param(IntType)
                }
                beforeHook {
                    val raw = args(0).int()
                    val level = (raw and 0xFF).coerceIn(0, 4)
                    currentCellularLevel = level
                }
            }

            // setLevel のフック（確実なレベル更新検知）
            injectMember {
                method {
                    name = "setLevel"
                    param(IntType)
                }
                beforeHook {
                    val raw = args(0).int()
                    val level = (raw and 0xFF).coerceIn(0, 4)
                    currentCellularLevel = level
                }
            }

            // draw のフック（iOS風 4本バーセルラーアイコンを描画）
            injectMember {
                method {
                    name = "draw"
                    param(CanvasClass)
                }
                beforeHook {
                    if (!isEnableIosIcons) return@beforeHook

                    val d = instance as? Drawable ?: return@beforeHook
                    val canvas = args(0).any() as? Canvas ?: return@beforeHook
                    if (d.bounds.isEmpty) return@beforeHook

                    // d.level (Drawable.getLevel()) から直接正確なレベルを取得
                    val rawLevel = d.level
                    val level = if (rawLevel != 0) (rawLevel and 0xFF).coerceIn(0, 4) else currentCellularLevel

                    val paint = runCatching {
                        d.current().field { name = "mForegroundPaint" }.any() as? Paint
                    }.getOrNull()
                    val tintColor = paint?.color ?: Color.WHITE

                    val wrapper = d as? DrawableWrapper
                    val inner = wrapper?.drawable
                    if (inner != null) {
                        inner.bounds = d.bounds
                        inner.level = level
                        val mutated = inner.mutate()
                        mutated.setTint(tintColor)
                        paint?.colorFilter?.let { mutated.colorFilter = it }
                        mutated.draw(canvas)
                    } else {
                        val ctx = (d.callback as? View)?.context ?: return@beforeHook
                        val iosDrawable = getCellularDrawable(ctx, level) ?: return@beforeHook
                        iosDrawable.bounds = d.bounds
                        val mutated = iosDrawable.mutate()
                        mutated.setTint(tintColor)
                        paint?.colorFilter?.let { mutated.colorFilter = it }
                        iosDrawable.draw(canvas)
                    }

                    // 本来のSignalDrawable描画をスキップ
                    resultNull()
                }
            }
        }
    }

    /**
     * Motorola独自仕様の isShowSeparatedSignal() をフックし、SignalDrawable経由の描画へ一本化
     * さらに toSignalDrawableState の呼び出しから最新レベルを追跡
     */
    private fun hookCellularSeparatedSignal() {
        findClass("com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel\$Cellular").hook {
            injectMember {
                method {
                    name = "isShowSeparatedSignal"
                    emptyParam()
                }
                beforeHook {
                    if (isEnableIosIcons) {
                        resultFalse()
                    }
                }
            }

            injectMember {
                method {
                    name = "toSignalDrawableState"
                }
                afterHook {
                    val inst = instance ?: return@afterHook
                    val lvl = runCatching {
                        inst.current().field { name = "level" }.int()
                    }.getOrNull()
                    if (lvl != null) {
                        val clamped = lvl.coerceIn(0, 4)
                        currentCellularLevel = clamped
                    }
                }
            }
        }
    }

    /**
     * WifiViewModel の最新 Wi-Fi レベル (0..4) を追跡
     */
    private fun hookWifiViewModel() {
        findClass("com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewModel\$wifiIcon\$1").hook {
            injectMember {
                method {
                    name = "invokeSuspend"
                }
                afterHook {
                    val inst = instance ?: return@afterHook
                    val model = runCatching {
                        inst.current().field { name = "L\$0" }.any()
                    }.getOrNull() ?: return@afterHook

                    if (model.javaClass.name.endsWith("Active")) {
                        val lvl = runCatching {
                            model.current().field { name = "level" }.int()
                        }.getOrNull()
                        if (lvl != null) {
                            currentWifiLevel = lvl.coerceIn(0, 4)
                        }
                    }
                }
            }
        }
    }

    /**
     * IconViewBinder.bind で wifi_signal にアイコンがバインドされた直後に iOS風扇型Wi-Fi に差し替え
     */
    private fun hookIconViewBinder() {
        findClass("com.android.systemui.common.ui.binder.IconViewBinder").hook {
            injectMember {
                method {
                    name = "bind"
                    param("com.android.systemui.common.shared.model.Icon", ImageViewClass)
                }
                afterHook {
                    if (!isEnableIosIcons) return@afterHook

                    val iv = args(1).any() as? ImageView ?: return@afterHook
                    val entryName = runCatching { iv.resources.getResourceEntryName(iv.id) }.getOrNull()
                    if (entryName == "wifi_signal") {
                        val icon = args(0).any()
                        var level = when (currentWifiLevel) {
                            4, 3 -> 3
                            2 -> 2
                            1 -> 1
                            else -> 0
                        }

                        if (icon != null) {
                            val resId = runCatching {
                                icon.current().field { name = "res" }.int()
                            }.getOrNull()
                            if (resId != null && resId != 0) {
                                val resName = runCatching { iv.resources.getResourceEntryName(resId) }.getOrNull() ?: ""
                                val parsed = parseWifiLevel(resName)
                                if (parsed >= 0) {
                                    level = parsed
                                }
                            }
                        }

                        val iosWifi = getWifiDrawable(iv.context, level)
                        if (iosWifi != null) {
                            safeSetDrawable(iv, iosWifi)
                        }
                    }
                }
            }
        }
    }

    /**
     * ImageView への直接設定に対するインターセプト
     */
    private fun hookImageView() {
        ImageViewClass.hook {
            // setImageLevel のフック（SystemUIが直接 setImageLevel を呼んだ場合の追従）
            injectMember {
                method {
                    name = "setImageLevel"
                    param(IntType)
                }
                beforeHook {
                    val iv = instance as? ImageView ?: return@beforeHook
                    val entryName = runCatching { iv.resources.getResourceEntryName(iv.id) }.getOrNull() ?: return@beforeHook
                    if (entryName == "mobile_signal" || entryName == "mobile_signal_slot1" || entryName == "mobile_signal_slot2") {
                        val raw = args(0).int()
                        val level = (raw and 0xFF).coerceIn(0, 4)
                        currentCellularLevel = level

                        if (isEnableIosIcons) {
                            val d = iv.drawable
                            if (d is LevelListDrawable) {
                                d.level = level
                            } else if (d !is DrawableWrapper) {
                                val levelList = getCellularLevelListDrawable(iv.context)
                                if (levelList != null) {
                                    levelList.level = level
                                    safeSetDrawable(iv, levelList)
                                }
                            }
                        }
                    }
                }
            }

            // setImageResource のフック
            injectMember {
                method {
                    name = "setImageResource"
                    param(IntType)
                }
                beforeHook {
                    if (!isEnableIosIcons) return@beforeHook
                    if (isReplacingDrawable.get() == true) return@beforeHook

                    val iv = instance as? ImageView ?: return@beforeHook
                    val entryName = runCatching { iv.resources.getResourceEntryName(iv.id) }.getOrNull() ?: return@beforeHook
                    val resId = args(0).int()
                    if (resId == 0) return@beforeHook
                    val resName = runCatching { iv.resources.getResourceEntryName(resId) }.getOrNull() ?: ""

                    when (entryName) {
                        "wifi_signal" -> {
                            val lvl = parseWifiLevel(resName).takeIf { it >= 0 } ?: when (currentWifiLevel) {
                                4, 3 -> 3
                                2 -> 2
                                1 -> 1
                                else -> 0
                            }
                            val drawable = getWifiDrawable(iv.context, lvl)
                            if (drawable != null) {
                                safeSetDrawable(iv, drawable)
                                resultNull()
                            }
                        }
                        "mobile_signal", "mobile_signal_slot1", "mobile_signal_slot2" -> {
                            val lvl = parseCellularLevel(resName)
                            currentCellularLevel = lvl
                            val levelList = getCellularLevelListDrawable(iv.context)
                            if (levelList != null) {
                                levelList.level = lvl
                                safeSetDrawable(iv, levelList)
                                resultNull()
                            }
                        }
                    }
                }
            }

            // setImageDrawable のフック (wifi_signal のみ)
            injectMember {
                method {
                    name = "setImageDrawable"
                    param(DrawableClass)
                }
                beforeHook {
                    if (!isEnableIosIcons) return@beforeHook
                    if (isReplacingDrawable.get() == true) return@beforeHook

                    val iv = instance as? ImageView ?: return@beforeHook
                    val entryName = runCatching { iv.resources.getResourceEntryName(iv.id) }.getOrNull() ?: return@beforeHook

                    if (entryName == "wifi_signal") {
                        val incoming = args(0).any() as? Drawable
                        if (incoming != null) {
                            val lvl = when (currentWifiLevel) {
                                4, 3 -> 3
                                2 -> 2
                                1 -> 1
                                else -> 0
                            }
                            val iosWifi = getWifiDrawable(iv.context, lvl)
                            if (iosWifi != null) {
                                val mutated = iosWifi.mutate()
                                iv.imageTintList?.let { mutated.setTintList(it) }
                                iv.imageTintMode?.let { mutated.setTintMode(it) }
                                iv.colorFilter?.let { mutated.colorFilter = it }
                                args(0).set(mutated)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun safeSetDrawable(iv: ImageView, drawable: Drawable) {
        try {
            isReplacingDrawable.set(true)
            val mutated = drawable.mutate()
            iv.imageTintList?.let { mutated.setTintList(it) }
            iv.imageTintMode?.let { mutated.setTintMode(it) }
            iv.colorFilter?.let { mutated.colorFilter = it }
            iv.setImageDrawable(mutated)
        } finally {
            isReplacingDrawable.set(false)
        }
    }

    private fun parseWifiLevel(resName: String): Int {
        val digit = resName.lastOrNull { it.isDigit() }?.digitToInt() ?: -1
        return when (digit) {
            4, 3 -> 3
            2 -> 2
            1 -> 1
            0 -> 0
            else -> -1
        }
    }

    private fun parseCellularLevel(resName: String): Int {
        val digit = resName.lastOrNull { it.isDigit() }?.digitToInt() ?: 4
        return digit.coerceIn(0, 4)
    }

    private fun getModuleContext(context: Context): Context? {
        if (moduleContextCache == null) {
            moduleContextCache = runCatching {
                context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
            }.getOrNull()
        }
        return moduleContextCache
    }

    private fun getCellularLevelListDrawable(context: Context): LevelListDrawable? {
        val modCtx = getModuleContext(context) ?: return null
        return runCatching {
            modCtx.getDrawable(R.drawable.ic_ios_cellular_level_list) as? LevelListDrawable
        }.getOrNull()
    }

    private fun getCellularDrawable(context: Context, level: Int): Drawable? {
        val clamped = level.coerceIn(0, 4)
        val resId = when (clamped) {
            0 -> R.drawable.ic_ios_cellular_0
            1 -> R.drawable.ic_ios_cellular_1
            2 -> R.drawable.ic_ios_cellular_2
            3 -> R.drawable.ic_ios_cellular_3
            else -> R.drawable.ic_ios_cellular_4
        }
        val modCtx = getModuleContext(context) ?: return null
        return runCatching { modCtx.getDrawable(resId) }.getOrNull()
    }

    private fun getWifiDrawable(context: Context, level: Int): Drawable? {
        val clamped = level.coerceIn(0, 3)
        val resId = when (clamped) {
            0 -> R.drawable.ic_ios_wifi_0
            1 -> R.drawable.ic_ios_wifi_1
            2 -> R.drawable.ic_ios_wifi_2
            else -> R.drawable.ic_ios_wifi_3
        }
        val modCtx = getModuleContext(context) ?: return null
        return runCatching { modCtx.getDrawable(resId) }.getOrNull()
    }
}
