// Web-only deployment profile; reuse the same application sources.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    js {
        browser()
        binaries.library()
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.navigation.compose)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.ktor.clientCore)
            implementation(libs.ktor.clientContentNegotiation)
            implementation(libs.ktor.clientEngineDefaults)
            implementation(libs.ktor.serializationKotlinxJson)
        }
        jsMain.dependencies {
            implementation(libs.wrappers.browser)
        }
    }
}
