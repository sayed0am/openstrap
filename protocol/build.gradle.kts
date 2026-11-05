import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(21)
    // Bytecode level matches the Android app module.
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // The module is small and clean, so a new warning fails the build here (audit section 10, step 5).
        allWarningsAsErrors.set(true)
    }
}

// Same rules as :app (config/detekt.yml). Old findings live in detekt-baseline-main.xml.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt.yml"))
    baseline = file("detekt-baseline.xml")
}

dependencies {
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // Only used to cross-check our B-163 implementation in tests; not shipped.
    testImplementation(libs.bouncycastle)

    detektPlugins(libs.compose.rules.detekt)
}
