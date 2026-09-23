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
val serverOnly = providers.gradleProperty("serverOnly").orNull == "true"
val webOnly = providers.gradleProperty("webOnly").orNull == "true"
require(!(serverOnly && webOnly)) { "Choose serverOnly or webOnly, not both" }
if (!serverOnly && !webOnly) {
    include(":app:androidApp")
    include(":app:desktopApp")
}
if (!serverOnly) {
    include(":app:shared")
    include(":app:webApp")
}
include(":core")
if (!webOnly) include(":server")
if (serverOnly) {
    project(":core").buildFileName = "build-server.gradle.kts"
}
if (webOnly) {
    project(":core").buildFileName = "build-web.gradle.kts"
    project(":app:shared").buildFileName = "build-web.gradle.kts"
}
