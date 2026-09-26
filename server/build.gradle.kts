plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlinSerialization)
}

group = "com.ideasdeveloper.idc"
version = "0.2.0"
application {
    mainClass = "com.ideasdeveloper.idc.server.app.ApplicationKt"
}

dependencies {
    testImplementation(kotlin("test"))
    api(project(":core"))
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)

    /* === DB + Connection Pool === */
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.jodatime)
    implementation(libs.exposed.javaTime)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serializationKotlinxJson)
    implementation(libs.ktor.serverRateLimit)
    /* === End DB === */
}

kotlin {
    sourceSets {
        main {
            kotlin.srcDir("../modules/clientes/shared/src/commonMain/kotlin")
            kotlin.srcDir("../modules/clientes/server/src/main/kotlin")
        }
    }
}
