package com.motolauncher.mod.hooks

import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.xposed.prefs.YukiHookPrefsBridge
import com.motolauncher.mod.HookConfig

/**
 * All SystemUI hooks read the same XML file as the launcher hooks and the
 * settings Activity. LSPosed exposes this file as XSharedPreferences in the
 * com.android.systemui process.
 */
internal val YukiBaseHooker.modulePrefs: YukiHookPrefsBridge
    get() = prefs(HookConfig.PREFS_NAME)
