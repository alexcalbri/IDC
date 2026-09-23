// Compile the existing common/JS sources on Ubuntu without an Android SDK.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    js {
        browser()
        binaries.library()
    }
}
