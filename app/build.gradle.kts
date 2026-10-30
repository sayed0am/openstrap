plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

fun env(name: String): String? = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

// The version comes from the release tag (CI passes -PappVersion=1.2.3); local builds default to the current one.
val appVersionName = providers.gradleProperty("appVersion").orNull?.removePrefix("v") ?: "2.1.4"
// 1.2.3 -> 10203, so every release installs over the one before it.
val appVersionCode = appVersionName.substringBefore('-').split('.').map { it.toInt() }
    .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }
val releaseKeystore = env("OPENSTRAP_KEYSTORE")

android {
    namespace = "org.openstrap.app"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "org.openstrap.app"
        minSdk = 28
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        // 64-bit ARM only: every phone from the last several years, and the emulator on Apple silicon. Each other CPU
        // type would add its own ~13 MB copy of the map engine (libmaplibre.so).
        ndk { abiFilters += "arm64-v8a" }
    }

    // English and Simplified Chinese (res/xml/locales_config.xml): the libraries' other languages stay out.
    androidResources { localeFilters += listOf("en", "zh-rCN") }

    signingConfigs {
        // The release key, when its env vars are set (CI sets them from secrets); see README "Releases".
        if (releaseKeystore != null) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = env("OPENSTRAP_KEYSTORE_PASSWORD")
            keyAlias = env("OPENSTRAP_KEY_ALIAS")
            keyPassword = env("OPENSTRAP_KEY_PASSWORD")
        }
    }

    buildTypes {
        // `-PdemoApp` builds a debug app that installs beside the real one (org.openstrap.app.demo), so sample data
        // for screenshots never touches a phone's own data.
        debug {
            if (providers.gradleProperty("demoApp").isPresent) applicationIdSuffix = ".demo"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the release key, signed with the debug key so a release build still installs locally.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// The backtest harness (BacktestReplay, docs/algorithms/readiness-calibration.md):
// ./gradlew testDebugUnitTest --tests '*BacktestReplay*' -Pbacktest=/path/openstrap.db [-PbacktestProfile=1990,Male]
tasks.withType<Test>().configureEach {
    // The English text for code that looks its words up outside an app (Texts.kt).
    systemProperty("openstrap.strings", file("src/main/res/values/strings.xml").absolutePath)
    listOf("backtest", "backtestOut", "backtestProfile").forEach { name ->
        providers.gradleProperty(name).orNull?.let { systemProperty("openstrap.$name", it) }
    }
}

dependencies {
    implementation(project(":protocol"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.health.connect)
    implementation(libs.coroutines.android)
    implementation(libs.core.ktx)
    implementation(libs.window)
    implementation(libs.haze.blur)
    // Workout maps: open source, no API key; tiles from OpenFreeMap (README, Network use).
    implementation(libs.maplibre)
    // Home screen widgets (docs/design/widgets/spec.md 6.1).
    implementation(libs.glance.appwidget)
    implementation(libs.work.runtime.ktx)
    // Automatic backup writes into a folder the user picks (export/AutoBackup.kt).
    implementation(libs.documentfile)

    testImplementation(libs.junit)
    // Android's org.json is a stub in plain JVM unit tests.
    testImplementation(libs.org.json)
    testImplementation(libs.coroutines.test)
    // A real Room database in unit tests (CSV restore).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
