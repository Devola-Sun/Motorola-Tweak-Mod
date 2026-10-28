package com.motolauncher.mod.hooks

import android.view.View
import android.view.ViewGroup
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.current
import com.highcapable.yukihookapi.hook.type.java.BooleanType
import com.highcapable.yukihookapi.hook.type.java.StringClass

object IconSlotHook : YukiBaseHooker() {

    // 非表示対象スロット一覧（Motorola固有スロット含む）
    private val hiddenSlots = setOf(
        "volte",
        "vowifi",
        "nfc",
        "moto_dual_sim",
        "alarm_clock",
        "zen",
        "ims",
        "hd",
        "call",
        "phone_signal_ims",
        "hd_voice"
    )

    val isHideVolte: Boolean
        get() = modulePrefs.getBoolean("sb_hide_volte", modulePrefs.getBoolean("pref_hide_volte", true))

    override fun onHook() {
        // 1. StatusBarIconControllerImpl.setIconVisibility
        findClass("com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl").hook {
            injectMember {
                method {
                    name = "setIconVisibility"
                    param(StringClass, BooleanType)
                }
                beforeHook {
                    val slot = args(0).string()

                    if (!isHideVolte) return@beforeHook

                    val slotLower = slot.lowercase()
                    val shouldHide = hiddenSlots.any { slotLower.contains(it) }
                    if (shouldHide) {
                        args(1).setFalse()
                    }
                }
            }
        }

        // 2. Motorola ImsIconController: IMS / VoLTE / VoWiFi / HD 通話アイコン更新のインターセプト
        findClass("com.android.systemui.statusbar.connectivity.ImsIconController").hook {
            injectMember {
                method {
                    name = "updateImsIcon"
                    emptyParam()
                }
                beforeHook {
                    if (!isHideVolte) return@beforeHook
                    try {
                        val iconController = instance.current().field { name = "mIconController" }.any()
                        val slotIms = instance.current().field { name = "mSlotIMS" }.string()
                        if (iconController != null && slotIms.isNotEmpty()) {
                            iconController.current().method {
                                name = "setIconVisibility"
                                param(StringClass, BooleanType)
                            }.call(slotIms, false)
                        }
                    } catch (_: Throwable) {
                    }
                    resultNull() // 描画処理自体をスキップ
                }
            }
        }

        // 3. MotoDualMobileIconBinder: デュアルSIMモバイルアイコン内のVoLTE/HDビューを非表示化
        findClass("com.android.systemui.statusbar.pipeline.mobile.ui.binder.MotoDualMobileIconBinder").hook {
            injectMember {
                method {
                    name = "bind"
                }
                afterHook {
                    if (!isHideVolte) return@afterHook
                    val mobileView = args(0).any() as? View ?: return@afterHook
                    hideVolteViews(mobileView)
                    mobileView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                        if (isHideVolte) {
                            hideVolteViews(v)
                        }
                    }
                }
            }
        }

        // 4. MotoMobileIconBinder: シングルSIM（Motorolaカスタム）モバイルアイコン内のVoLTE/HDビューを非表示化
        findClass("com.android.systemui.statusbar.pipeline.mobile.ui.binder.MotoMobileIconBinder").hook {
            injectMember {
                method {
                    name = "bind"
                }
                afterHook {
                    if (!isHideVolte) return@afterHook
                    val mobileView = args(0).any() as? View ?: return@afterHook
                    hideVolteViews(mobileView)
                    mobileView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                        if (isHideVolte) {
                            hideVolteViews(v)
                        }
                    }
                }
            }
        }

        // 5. MobileIconBinder: 標準モバイルアイコン内のサブビュー走査
        findClass("com.android.systemui.statusbar.pipeline.mobile.ui.binder.MobileIconBinder").hook {
            injectMember {
                method {
                    name = "bind"
                }
                afterHook {
                    if (!isHideVolte) return@afterHook
                    val mobileView = args(0).any() as? View ?: return@afterHook
                    hideVolteViews(mobileView)
                    mobileView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                        if (isHideVolte) {
                            hideVolteViews(v)
                        }
                    }
                }
            }
        }
    }

    /**
     * Viewツリー内を再帰走査し、VoLTE / VoWiFi / HD通話 / IMSに関連するViewを探索して GONE に設定
     */
    fun hideVolteViews(view: View) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                hideVolteViews(view.getChildAt(i))
            }
        }
        val resName = runCatching { view.context.resources.getResourceEntryName(view.id) }.getOrNull()?.lowercase() ?: ""
        val desc = view.contentDescription?.toString()?.lowercase() ?: ""

        val isVolteTarget = resName.contains("volte") ||
                resName.contains("vowifi") ||
                resName.contains("ims") ||
                resName.contains("hd_") ||
                resName.endsWith("_hd") ||
                desc.contains("volte") ||
                desc.contains("vowifi") ||
                desc.contains("hd voice") ||
                desc.contains("hd call") ||
                desc.contains("hd+") ||
                desc.contains("hd ")

        if (isVolteTarget) {
            if (view.visibility != View.GONE) {
                view.visibility = View.GONE
            }
        }
    }
}
