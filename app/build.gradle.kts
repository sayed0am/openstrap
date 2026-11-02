import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
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
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        manifestPlaceholders["appLabel"] = "@string/app_name"
        // 64-bit ARM only: every phone from the last several years, and the emulator on Apple silicon. Each other CPU
        // type would add its own ~13 MB copy of the map engine (libmaplibre.so).
        ndk { abiFilters += "arm64-v8a" }
    }

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
        // `-PdevApp` builds a debug app that also installs beside it (org.openstrap.app.dev), named with its version,
        // to try a branch on a phone with its own data while the release stays as it is.
        debug {
            if (providers.gradleProperty("demoApp").isPresent) applicationIdSuffix = ".demo"
            if (providers.gradleProperty("devApp").isPresent) {
                applicationIdSuffix = ".dev"
                manifestPlaceholders["appLabel"] = "OpenStrap ${appVersionName.removeSuffix(".0")}"
            }
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

    // Android lint. Old findings live in the baseline (refresh with ./gradlew :app:updateLintBaseline), new ones fail
    // the build. SARIF reports are always written on AGP 9. The extra error ids are the Compose and dead resource checks from the November 2026 audit.
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
        checkDependencies = true
        error += listOf(
            "StateFlowValueCalledInComposition", "FlowOperatorInvokedInComposition",
            "CoroutineCreationDuringComposition", "ComposeModifierMissing",
            "ComposeParameterOrder", "ComposeUnstableCollections", "UnusedResources",
        )
    }
}

// Stability reports, so recomposition problems are visible rather than guessed (build/compose_compiler).
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}

// detekt with the Compose rules. Old findings live in detekt-baseline-debug.xml and detekt-baseline-release.xml
// (./gradlew :app:detektBaselineMain). detektMain is the type resolved run the coroutine rules need.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt.yml"))
    baseline = file("detekt-baseline.xml")
}

// BuildConfig is generated Java, which the Kotlin analysis only sees as a compiled class on the classpath. Without it
// detekt reports "25 compiler errors" (unresolved BuildConfig) and type resolved rules miss findings in those files.
// The plugin sets its classpath as a convention (Kotlin classes plus the Kotlin task's libraries), and adding to it
// replaces that convention, so the same two parts are restated here next to the javac output.
listOf("Debug", "Release").forEach { variant ->
    tasks.withType<dev.detekt.gradle.Detekt>().matching { it.name == "detekt$variant" }.configureEach {
        val kotlinCompile = tasks.named("compile${variant}Kotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java)
        val javac = tasks.named("compile${variant}JavaWithJavac", JavaCompile::class.java)
        classpath.setFrom(
            kotlinCompile.flatMap { it.destinationDirectory },
            kotlinCompile.map { it.libraries },
            javac.flatMap { it.destinationDirectory },
        )
    }
}

// Formatting only, from .editorconfig. Old violations live in the baseline, so nothing is reformatted.
ktlint {
    version = libs.versions.ktlint.get()
    baseline = file("ktlint-baseline.xml")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// The backtest harness (BacktestReplay, docs/algorithms/readiness-calibration.md):
// ./gradlew testDebugUnitTest --tests '*BacktestReplay*' -Pbacktest=/path/openstrap.db [-PbacktestProfile=1990,Male]
// Source scan tests read app/src/main from here, so they do not depend on the working directory.
// `-PtestLocale=de-DE` runs the suite under that locale and a zone with a non whole hour offset, which flushes out
// formatting that ignores the locale and dates that assume a whole hour offset.
val appSrcMain = layout.projectDirectory.dir("src/main").asFile.absolutePath
val testLocale = providers.gradleProperty("testLocale").orNull
tasks.withType<Test>().configureEach {
    systemProperty("openstrap.srcRoot", appSrcMain)
    testLocale?.let { tag ->
        val locale = Locale.forLanguageTag(tag)
        systemProperty("user.language", locale.language)
        systemProperty("user.country", locale.country)
        systemProperty("user.timezone", "Asia/Kathmandu")
    }
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
    // Migration tests (MigrationTestHelper).
    testImplementation(libs.room.testing)

    lintChecks(libs.slack.compose.lint)
    detektPlugins(libs.compose.rules.detekt)
}
