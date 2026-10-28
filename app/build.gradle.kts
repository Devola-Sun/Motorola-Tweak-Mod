import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val moduleApplicationId = "io.github.devola_sun.motorolatweak"

android {
    namespace = "com.motolauncher.mod"
    // YukiHookAPI 1.3.2 is published against API 37, so the module must compile
    // against 37. targetSdk intentionally stays at 36 (Android 16, the Razr ROM).
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        // LSPosed repository review requires the module id to follow the
        // io.github.<owner>.<repo> convention. The namespace stays
        // com.motolauncher.mod so the Kotlin sources are untouched.
        applicationId = moduleApplicationId
        minSdk = 36
        targetSdk = 36
        versionCode = 20
        versionName = "2.5.7"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        // YukiHookAPI's KSP processor reads BuildConfig / namespace
        // to generate the xposed_init entry point.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Yuki's processor can infer a unit-test applicationId when test and app KSP
// tasks run in the same build. Check the generated code that actually ships,
// not just output-metadata.json (which still reports the correct APK id).
androidComponents.onVariants { variant ->
    val capitalized = variant.name.replaceFirstChar { it.uppercaseChar() }
    val generatedEntry = layout.buildDirectory.file(
        "generated/ksp/${variant.name}/kotlin/com/motolauncher/mod/HookEntry_Impl.kt"
    )
    val verifyIdentity = tasks.register("verify${capitalized}XposedIdentity") {
        dependsOn("ksp${capitalized}Kotlin")
        inputs.file(generatedEntry)
        inputs.property("moduleApplicationId", moduleApplicationId)
        doLast {
            val source = generatedEntry.get().asFile.readText()
            val actual = Regex("MODULE_PACKAGE_NAME\\s*=\\s*\"([^\"]+)\"")
                .find(source)?.groupValues?.get(1)
            check(actual == moduleApplicationId) {
                "Xposed module id mismatch: generated=$actual expected=$moduleApplicationId. " +
                    "Refusing to assemble an APK that reads the wrong preferences."
            }
            logger.lifecycle("Verified Xposed module identity: $actual (${variant.name})")
        }
    }
    tasks.matching { it.name == "assemble$capitalized" || it.name == "package$capitalized" }
        .configureEach { dependsOn(verifyIdentity) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // YukiHookAPI module runtime + KSP entry-point generator.
    implementation("com.highcapable.yukihookapi:api:1.3.2")
    ksp("com.highcapable.yukihookapi:ksp-xposed:1.3.2")

    // YukiHookAPI 1.3.x uses KavaRef as its reflection backend.
    implementation("com.highcapable.kavaref:kavaref-core:1.0.3")
    implementation("com.highcapable.kavaref:kavaref-extension:1.0.3")

    // Used by the KSP-generated Xposed entry classes (@Keep).
    implementation("androidx.annotation:annotation:1.9.1")

    // Settings UI (MainActivity / SwitchCompat / AppCompat theme).
    implementation("androidx.appcompat:appcompat:1.7.1")

    // Material Components: SwitchMaterial widget of the settings screen.
    implementation("com.google.android.material:material:1.11.0")

    // YukiHookAPI's prefs bridge and ModulePreferenceFragment use AndroidX
    // Preference. Declared explicitly so the UI side is not dependent on
    // transitive runtime metadata.
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Drag-to-reorder list for the status bar element order UI.
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    // Xposed API is provided by LSPosed at runtime, so compileOnly.
    compileOnly("de.robv.android.xposed:api:82")
}
