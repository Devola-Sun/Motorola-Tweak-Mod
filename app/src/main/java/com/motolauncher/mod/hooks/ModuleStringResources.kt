package com.motolauncher.mod.hooks

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import com.motolauncher.mod.BuildConfig

/** Resolve module resource IDs with the current host user's locale, not SystemUI's assets. */
internal fun Context.moduleStringResources(): Resources {
    if (packageName == BuildConfig.APPLICATION_ID) return resources
    val module = createPackageContext(BuildConfig.APPLICATION_ID, 0)
    val locales = resources.configuration.locales
    if (locales.isEmpty || module.resources.configuration.locales == locales) return module.resources
    return module.createConfigurationContext(Configuration().apply { setLocales(locales) }).resources
}
