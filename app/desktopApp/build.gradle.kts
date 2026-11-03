import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":app:shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.compose.uiToolingPreview)
}

// Version of the apps, shared with Xcode: app/Version.xcconfig.
val appVersionFile = rootProject.layout.projectDirectory.file("app/Version.xcconfig")
fun appVersionValue(key: String): Provider<String> = providers.fileContents(appVersionFile).asText.map { text ->
    text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.trim()
        ?: error("$key no esta en app/Version.xcconfig")
}

compose.desktop {
    application {
        mainClass = "com.ideasdeveloper.idc.MainKt"
        // JCEF (navegador integrado de modulos web) accede a clases internas de AWT.
        jvmArgs += listOf(
            "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/java.awt.peer=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.ideasdeveloper.idc"
            packageVersion = appVersionValue("APP_VERSION").get()
        }
    }
}
