rootProject.name = "IDC"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
    }
if (providers.gradleProperty("serverOnly").orNull != "true") {
    include(":app:androidApp")
    include(":app:desktopApp")
    include(":app:shared")
    include(":app:webApp")
}
include(":core")
include(":server")
if (providers.gradleProperty("serverOnly").orNull == "true") {
    project(":core").buildFileName = "build-server.gradle.kts"
}
