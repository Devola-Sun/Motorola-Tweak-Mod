package com.motolauncher.mod

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Applies the in-app display language selection ("ja" / "en" / "zh-CN" / "ru") to the base
 * context of every activity, so all string resources resolve in the selected
 * language regardless of the system locale.
 */
object LocaleHelper {

    fun wrap(context: Context): Context {
        val language = runCatching { SettingsStore(context).appLanguage }
            .getOrDefault(HookConfig.DEFAULT_APP_LANGUAGE)

        val locale = when (language) {
            HookConfig.LANGUAGE_JA -> Locale(JAPANESE)
            HookConfig.LANGUAGE_EN -> Locale(ENGLISH)
            HookConfig.LANGUAGE_ZH_CN -> Locale.SIMPLIFIED_CHINESE
            HookConfig.LANGUAGE_RU -> Locale.forLanguageTag(HookConfig.LANGUAGE_RU)
            else -> return context
        }

        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    private const val JAPANESE = "ja"
    private const val ENGLISH = "en"
}
