package com.motolauncher.mod.hooks

import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.current
import com.highcapable.yukihookapi.hook.log.YLog

object ClockHook : YukiBaseHooker() {

    private val isShowSeconds: Boolean
        get() = modulePrefs.getBoolean("sb_show_seconds", modulePrefs.getBoolean("pref_show_seconds", true))

    override fun onHook() {
        if (!isShowSeconds) return

        findClass("com.android.systemui.statusbar.policy.Clock").hook {
            injectMember {
                method {
                    name = "onAttachedToWindow"
                    emptyParam()
                }
                afterHook {
                    if (!isShowSeconds) return@afterHook
                    val inst = instance ?: return@afterHook
                    inst.current {
                        field { name = "mShowSeconds" }.set(true)
                        method { name = "updateShowSeconds" }.call()
                        method { name = "updateClock" }.call()
                    }
                    // YLog.debug("Clock: Enabled seconds on attached to window")
                }
            }

            injectMember {
                method {
                    name = "updateShowSeconds"
                    emptyParam()
                }
                beforeHook {
                    if (!isShowSeconds) return@beforeHook
                    instance?.current {
                        field { name = "mShowSeconds" }.set(true)
                    }
                }
            }

            injectMember {
                method {
                    name = "getSmallTime"
                    emptyParam()
                }
                beforeHook {
                    if (!isShowSeconds) return@beforeHook
                    instance?.current {
                        field { name = "mShowSeconds" }.set(true)
                    }
                }
            }
        }
    }
}
