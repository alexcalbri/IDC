plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlinSerialization)
}

group = "com.ideasdeveloper.idc"
version = "0.1.0"
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
    implementation(libs.ktor.clientCore)
    implementation(libs.ktor.clientContentNegotiation)
    implementation(libs.ktor.clientEngineDefaults)
    /* === End DB === */
}

val generatedModuleServicesDir = layout.buildDirectory.dir("generated/resources/module-services")

val moduleProviderServiceFile = generatedModuleServicesDir.get()
    .file("META-INF/services/com.ideasdeveloper.idc.server.modules.ServerModuleProvider")
    .asFile

val moduleProviderEntries = rootProject.layout.projectDirectory.dir("modules").asFile
    .listFiles()
    ?.filter { moduleDir ->
        moduleDir.isDirectory &&
            moduleDir.resolve("server/src/main/kotlin").isDirectory
    }
    ?.sortedBy { it.name }
    ?.mapNotNull { moduleDir ->
        val moduleName = moduleDir.name
        val providerPrefix = moduleName
            .split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString("") { part ->
                part.replaceFirstChar { char -> char.uppercase() }
            }
        val providerName = "${providerPrefix}ServerModuleProvider"
        val declaresProvider = moduleDir
            .resolve("server/src/main/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .any { it.readText().contains("class $providerName") }

        if (declaresProvider) {
            "com.ideasdeveloper.idc.modules.$moduleName.$providerName"
        } else {
            null
        }
    }
    .orEmpty()

moduleProviderServiceFile.parentFile.mkdirs()
moduleProviderServiceFile.writeText(moduleProviderEntries.joinToString(separator = "\n", postfix = "\n"))

sourceSets {
    main {
        resources.srcDir(generatedModuleServicesDir)
    }
}

kotlin {
    sourceSets {
        main {
            rootProject.layout.projectDirectory.dir("modules").asFile.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedBy { it.name }
                ?.forEach { moduleDir ->
                    kotlin.srcDir(moduleDir.resolve("shared/src/commonMain/kotlin"))
                    kotlin.srcDir(moduleDir.resolve("server/src/main/kotlin"))
                }
        }
    }
}
