import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
}

fun env(name: String): String? = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

// The version comes from the release tag (CI passes -PappVersion=1.2.3); local builds default to the current one.
val appVersionName = providers.gradleProperty("appVersion").orNull?.removePrefix("v") ?: "3.0.0"
// 1.2.3 -> 10203, so every release installs over the one before it.
val appVersionCode = appVersionName.substringBefore('-').split('.').map { it.toInt() }
    .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

// `-PphoneApp` builds a debug app as the real one (org.openstrap.app, signed with the release key), so a branch installs
// over the release on the developer's own phone and keeps its data, and the phone runs one app like everyone else's.
val phoneApp = providers.gradleProperty("phoneApp").isPresent
if (phoneApp && (providers.gradleProperty("devApp").isPresent || providers.gradleProperty("demoApp").isPresent)) {
    error("-PphoneApp builds the real app: leave out -PdevApp and -PdemoApp")
}

// The release key's settings for a phone build, when the env vars aren't set: ~/.openstrap/signing.env, shell
// `NAME=value` lines (an `export` and quotes are allowed, and a leading ~ or $HOME in a value is the home folder).
val home: String = System.getProperty("user.home")
val signingFile: Map<String, String> = if (!phoneApp) emptyMap() else
    providers.fileContents(layout.projectDirectory.file("$home/.openstrap/signing.env")).asText.orNull.orEmpty()
        .lines().map { it.trim().removePrefix("export ").trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
        .associate { line ->
            val value = line.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
            line.substringBefore('=').trim() to value.replaceFirst(Regex("^(~|\\\$HOME|\\$\\{HOME})"), home)
        }

/** A signing setting: its env var (CI sets them from secrets), or for a phone build the signing file. */
fun signing(name: String): String? = env(name) ?: signingFile[name]?.takeIf { it.isNotBlank() }

val releaseKeystore = signing("OPENSTRAP_KEYSTORE")

android {
    namespace = "org.openstrap.app"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "org.openstrap.app"
        minSdk = 30
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
            storePassword = signing("OPENSTRAP_KEYSTORE_PASSWORD")
            keyAlias = signing("OPENSTRAP_KEY_ALIAS")
            keyPassword = signing("OPENSTRAP_KEY_PASSWORD")
        }
    }
    if (phoneApp && releaseKeystore == null) {
        error("-PphoneApp needs the release key: set the OPENSTRAP_KEYSTORE env vars or write ~/.openstrap/signing.env")
    }

    buildTypes {
        // `-PdemoApp` builds a debug app that installs beside the real one (org.openstrap.app.demo), so sample data
        // for screenshots never touches a phone's own data.
        // `-PdevApp` builds a debug app that also installs beside it (org.openstrap.app.dev), named with its version,
        // to try a branch on a phone with its own data while the release stays as it is.
        debug {
            // en-XA (accented and longer, so text still in Kotlin or text that clips stands out) and ar-XB (right to
            // left) can be picked as the phone's language on a debug build (docs/audits/2026-11-04-i18n-plan.md).
            isPseudoLocalesEnabled = true
            if (providers.gradleProperty("demoApp").isPresent) applicationIdSuffix = ".demo"
            if (providers.gradleProperty("devApp").isPresent) {
                applicationIdSuffix = ".dev"
                manifestPlaceholders["appLabel"] = "Unstrap ${appVersionName.removeSuffix(".0")}"
            }
            // Installs only over a release with a lower version: pass -PappVersion past the one on the phone.
            if (phoneApp) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the release key, signed with the debug key so a release build still installs locally.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        // A release build the :baselineprofile module drives to record app/src/main/baseline-prof.txt: not shrunk, so
        // the profile names the real classes (R8 renames them for the release), and not debuggable, so it runs like
        // the release. It installs as the dev app, beside the real one and never over it.
        create("benchmark") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "Unstrap ${appVersionName.removeSuffix(".0")}"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    // The benchmark build is a release build: the release stubs of the debug tools.
    sourceSets.getByName("benchmark").kotlin.directories += "src/release/kotlin"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The languages under res/ become the app's locale config, so Android 13 and up list OpenStrap under App languages
    // in system settings. res/resources.properties says the unqualified values/ folder is English.
    androidResources {
        generateLocaleConfig = true
    }

    // The unit tests get the merged resources and manifest, so a Robolectric test reads real strings
    // (i18n/StringsParityTest). robolectric.properties keeps a plain Application, not OpenStrapApp and its container.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
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
        // LogNotTimber comes in with a dependency's lint checks, but the app logs with android.util.Log on purpose
        // and has no Timber. NewerVersionAvailable, GradleDependency and AndroidGradlePluginVersion ask the network, so
        // their answer changes from run to run with nothing in the repository changed.
        disable += listOf("LogNotTimber", "NewerVersionAvailable", "GradleDependency", "AndroidGradlePluginVersion")
    }
}

// Stability reports, so recomposition problems are visible rather than guessed (build/compose_compiler).
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}

// detekt with the Compose rules. Old findings live in detekt-baseline-debug.xml and detekt-baseline-release.xml
// (./gradlew :app:detektBaselineMain). The type resolved runs are what the coroutine rules need. CI runs detektDebug
// only: release adds four stub files to the same main sources, and checking it meant a second full compile.
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
    // A failure prints its message and the assertion's values, so a CI only failure can be read from the log.
    testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    // Robolectric's SQLite is native: its 'localtime' follows the process's TZ, not Java's default zone or a test's
    // ZoneRule. UTC on every machine, so a day grouped in SQL is the same day on a Mac in Dubai and on CI.
    environment("TZ", "UTC")
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
    // Home screen widgets.
    implementation(libs.glance.appwidget)
    implementation(libs.work.runtime.ktx)
    // Automatic backup writes into a folder the user picks (export/AutoBackup.kt).
    implementation(libs.documentfile)
    // Hands the baseline profile to ART on phones that install the APK outside the Play Store.
    implementation(libs.profileinstaller)

    testImplementation(libs.junit)
    // Android's org.json is a stub in plain JVM unit tests.
    testImplementation(libs.org.json)
    testImplementation(libs.coroutines.test)
    // A real Room database in unit tests (CSV restore).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Compose UI tests under Robolectric, for saved state across a recreate (StateRestorationTester).
    testImplementation(libs.compose.ui.test.junit4)
    // Migration tests (MigrationTestHelper).
    testImplementation(libs.room.testing)

    lintChecks(libs.slack.compose.lint)
    detektPlugins(libs.compose.rules.detekt)
}
