# The debug build is not minified. These rules are kept for release builds.
-keep class com.motolauncher.mod.HookEntry { *; }
-keep class com.motolauncher.mod.HookEntry_YukiHookXposedInit { *; }
-keep class com.motolauncher.mod.LauncherHooker { *; }
-keep class com.motolauncher.mod.HookConfig { *; }
-keep class com.highcapable.yukihookapi.** { *; }
-keep class com.highcapable.kavaref.** { *; }
