// The Ktor plugin asks for commons-lang3 [3.18.0,); with an empty Gradle cache the range picks the
// newest release and plugin classpath resolution fails ("has no target nodes"). Pin it.
buildscript {
    dependencies {
        constraints {
            classpath("org.apache.commons:commons-lang3") {
                version { strictly("3.18.0") }
            }
        }
    }
}

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.ktor) apply false
}

// Ktor's JS client pins ws 8.20.1, affected by GHSA-96hv-2xvq-fx4p (fixed in 8.21.0).
// The webpack dev tooling pulls qs ~6.16.0; 6.16.0 has a stringify DoS (Dependabot #80, fixed in 6.16.1).
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension>().apply {
        resolution("ws", "8.21.3")
        resolution("qs", "6.16.1")
    }
}
