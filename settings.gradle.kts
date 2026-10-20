pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Required for the Rovo89 Xposed API stubs (compileOnly).
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "MotoLauncherMod"
include(":app")
