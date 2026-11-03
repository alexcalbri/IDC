// Web-only deployment profile; reuse the same application sources.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

// Version of the apps, shared with Xcode: app/Version.xcconfig.
val appVersionFile = rootProject.layout.projectDirectory.file("app/Version.xcconfig")
fun appVersionValue(key: String): Provider<String> = providers.fileContents(appVersionFile).asText.map { text ->
    text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.trim()
        ?: error("$key no esta en app/Version.xcconfig")
}

// Same as build.gradle.kts. ClientVersion (shown in the app and compared with the server's latest) comes from the same file.
val generateClientVersion by tasks.registering {
    val version = appVersionValue("APP_VERSION")
    val output = layout.buildDirectory.dir("generated/clientVersion/kotlin")
    inputs.property("version", version)
    outputs.dir(output)
    doLast {
        val file = output.get().file("com/ideasdeveloper/idc/app/core/version/ClientVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.ideasdeveloper.idc.app.core.version
            |
            |// Generated from app/Version.xcconfig; do not edit.
            |const val ClientVersion = "${version.get()}"
            |""".trimMargin()
        )
    }
}

kotlin {
    js {
        browser()
        binaries.library()
    }
    sourceSets {
        commonMain {
            kotlin.srcDir(generateClientVersion)
        }
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
