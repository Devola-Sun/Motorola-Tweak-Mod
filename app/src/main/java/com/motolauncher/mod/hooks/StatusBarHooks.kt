package com.motolauncher.mod.hooks

import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker

object StatusBarHooks : YukiBaseHooker() {

    override fun onHook() {
        loadHooker(PaddingHook)
        loadHooker(IconSlotHook)
        loadHooker(BatteryHook)
        loadHooker(ClockHook)
        loadHooker(SignalIconHook)
        loadHooker(StatusBarOrderHook)
        loadHooker(FlagSecureHook)
    }
}
