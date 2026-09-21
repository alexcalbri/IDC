plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
}

group = "com.ideasdeveloper.idc"
version = "1.0.0"
application {
    mainClass = "com.ideasdeveloper.idc.ApplicationKt"
}

dependencies {
    api(project(":core"))
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)

    /* === DB + Connection Pool === */
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.jodatime)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    /* === End DB === */
}