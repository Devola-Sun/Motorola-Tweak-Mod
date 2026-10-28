package com.motolauncher.mod.hooks

import android.view.View
import android.widget.FrameLayout
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker

object PaddingHook : YukiBaseHooker() {

    private val isHideVolte: Boolean
        get() = modulePrefs.getBoolean("sb_hide_volte", modulePrefs.getBoolean("pref_hide_volte", true))

    private val isEnablePadding: Boolean
        get() = modulePrefs.getBoolean("sb_enable_custom_padding", modulePrefs.getBoolean("pref_enable_custom_padding", true))

    override fun onHook() {
        // 1. メイン画面（内側 / Unfolded）: PhoneStatusBarView のパディング制御
        findClass("com.android.systemui.statusbar.phone.PhoneStatusBarView").hook {
            injectMember {
                method {
                    name = "updateSafeInsets"
                    emptyParam()
                }
                afterHook {
                    val view = instance as? FrameLayout ?: return@afterHook

                    // VoLTE 非表示トグルが有効な場合、PhoneStatusBarView 内の VoLTE ビューも走査して隠す
                    if (isHideVolte) {
                        IconSlotHook.hideVolteViews(view)
                    }

                    if (!isEnablePadding) return@afterHook

                    // 左右の余白をカスタマイズ（パンチホールや端の余白調整）
                    val customPaddingStart = 16
                    val customPaddingEnd = 16

                    val currentTop = view.paddingTop
                    val currentBottom = view.paddingBottom
                    val currentLeft = view.paddingLeft
                    val currentRight = view.paddingRight

                    view.setPadding(
                        currentLeft.coerceAtLeast(customPaddingStart),
                        currentTop,
                        currentRight.coerceAtLeast(customPaddingEnd),
                        currentBottom
                    )

                    // スタート側・エンド側のコンテナ余白も微調整
                    val context = view.context
                    val endSideId = context.resources.getIdentifier("status_bar_end_side_container", "id", context.packageName)
                    if (endSideId != 0) {
                        view.findViewById<View>(endSideId)?.let { endSide ->
                            endSide.setPadding(endSide.paddingLeft, endSide.paddingTop, 8, endSide.paddingBottom)
                        }
                    }
                    val startSideId = context.resources.getIdentifier("status_bar_start_side_container", "id", context.packageName)
                    if (startSideId != 0) {
                        view.findViewById<View>(startSideId)?.let { startSide ->
                            startSide.setPadding(8, startSide.paddingTop, startSide.paddingRight, startSide.paddingBottom)
                        }
                    }
                }
            }
        }

        // 2. カバー画面（外側 / Folded）: CliStatusbarLinearLayout
        findClass("com.motorola.systemui.cli.statusbar.CliStatusbarLinearLayout").hook {
            injectMember {
                method {
                    name = "onCliDensityOrFontScaleChanged"
                    emptyParam()
                }
                afterHook {
                    if (isHideVolte) {
                        (instance as? View)?.let { IconSlotHook.hideVolteViews(it) }
                    }
                    if (!isEnablePadding) return@afterHook
                    val layout = instance as? View ?: return@afterHook
                    layout.setPadding(12, layout.paddingTop, 12, layout.paddingBottom)
                }
            }
            injectMember {
                method {
                    name = "onFinishInflate"
                    emptyParam()
                }
                afterHook {
                    if (isHideVolte) {
                        (instance as? View)?.let { IconSlotHook.hideVolteViews(it) }
                    }
                    if (!isEnablePadding) return@afterHook
                    val layout = instance as? View ?: return@afterHook
                    layout.setPadding(12, layout.paddingTop, 12, layout.paddingBottom)
                }
            }
        }
    }
}
