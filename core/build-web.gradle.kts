// Compile the existing common/JS sources on Ubuntu without an Android SDK.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    js {
        browser()
        binaries.library()
    }
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serializationJson)
        }
    }
}
