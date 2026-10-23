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
    implementation(libs.angus.mail)
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

val moduleDirs = rootProject.layout.projectDirectory.dir("modules").asFile.listFiles()
    ?.filter { it.isDirectory }
    ?.sortedBy { it.name }
    .orEmpty()

sourceSets {
    main {
        resources.srcDir(generatedModuleServicesDir)
        moduleDirs.forEach { resources.srcDir(it.resolve("server/src/main/resources")) }
    }
}

kotlin {
    sourceSets {
        main {
            moduleDirs.forEach { kotlin.srcDir(it.resolve("server/src/main/kotlin")) }
        }
        test {
            moduleDirs.forEach { kotlin.srcDir(it.resolve("server/src/test/kotlin")) }
        }
    }
}

// Enforces the module layout documented in modules/README.md before the server compiles.
val validateModules by tasks.registering {
    group = "verification"
    description = "Checks that every folder under modules/ follows the IdeasCore module layout."
    val modulesRoot = rootProject.layout.projectDirectory.dir("modules").asFile
    inputs.dir(modulesRoot)
    doLast {
        val idPattern = Regex("[a-z][a-z0-9_]{0,62}")
        val migrationPattern = Regex("V[0-9]{3}__[a-z0-9_]+\\.sql")
        val allowedTopLevel = setOf("README.md", "module.json", "migrations", "server")
        val allowedSourceRoots = setOf("src/main/kotlin", "src/main/resources", "src/test/kotlin")
        val problems = mutableListOf<String>()

        modulesRoot.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.forEach { module ->
            val id = module.name
            // Empty scaffolds (only folders, as left by Git) are skipped.
            if (module.walkTopDown().none { it.isFile }) return@forEach
            fun problem(message: String) = problems.add("modules/$id: $message")

            if (!idPattern.matches(id)) problem("the folder name must match ${idPattern.pattern} (it is the module id and Kotlin package).")
            if (!module.resolve("README.md").isFile) problem("README.md is required.")
            module.listFiles().orEmpty()
                .filter { !it.name.startsWith(".") && it.name !in allowedTopLevel }
                .forEach { problem("'${it.name}' is not allowed here; only ${allowedTopLevel.joinToString()} (see modules/README.md).") }

            module.resolve("module.json").takeIf { it.isFile }?.let { manifest ->
                val json = runCatching { groovy.json.JsonSlurper().parse(manifest) as? Map<*, *> }.getOrNull()
                if (json == null) {
                    problem("module.json is not a valid JSON object.")
                } else {
                    if (json["id"] != id) problem("module.json id must be '$id'.")
                    listOf("displayName", "description", "version").forEach { key ->
                        if ((json[key] as? String).isNullOrBlank()) problem("module.json requires '$key'.")
                    }
                }
            }

            module.resolve("migrations").takeIf { it.isDirectory }?.listFiles().orEmpty().sortedBy { it.name }.forEach { file ->
                when {
                    file.name == "README.md" -> Unit
                    !file.isFile || !migrationPattern.matches(file.name) ->
                        problem("migrations/${file.name}: migrations must be named VNNN__description.sql.")
                    else -> {
                        val sql = file.readText().trim()
                        if (!sql.startsWith("BEGIN;") || !sql.endsWith("COMMIT;")) {
                            problem("migrations/${file.name}: must start with BEGIN; and end with COMMIT;.")
                        }
                    }
                }
            }

            val server = module.resolve("server")
            if (server.isDirectory) {
                server.walkTopDown().filter { it.isFile }.forEach { file ->
                    val relative = file.relativeTo(server).invariantSeparatorsPath
                    val root = allowedSourceRoots.firstOrNull { relative.startsWith("$it/") }
                    val packagePath = "com/ideasdeveloper/idc/modules/$id/"
                    when {
                        root == null -> problem("server/$relative: files go under server/${allowedSourceRoots.joinToString(" or server/")}.")
                        root == "src/main/resources" && !relative.startsWith("$root/modules/$id/") ->
                            problem("server/$relative: resources go under server/src/main/resources/modules/$id/ so modules cannot overwrite each other.")
                        root != "src/main/resources" && !relative.startsWith("$root/$packagePath") ->
                            problem("server/$relative: Kotlin code goes under server/$root/$packagePath.")
                        root != "src/main/resources" && file.extension == "kt" &&
                            !file.readText().contains(Regex("(?m)^package com\\.ideasdeveloper\\.idc\\.modules\\.$id(\\.|$)")) ->
                            problem("server/$relative: package must be com.ideasdeveloper.idc.modules.$id.")
                    }
                }
                val mainKotlin = server.resolve("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.readText() }.toList()
                val provider = id.split('_').joinToString("") { part -> part.replaceFirstChar { it.uppercase() } } + "ServerModuleProvider"
                if (mainKotlin.any { it.contains(Regex(":\\s*ServerModule\\b")) } && mainKotlin.none { it.contains("class $provider") }) {
                    problem("implements ServerModule but does not declare class $provider, so the server would never load it.")
                }
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException("Module layout problems:\n - " + problems.joinToString("\n - "))
        }
    }
}

tasks.named("compileKotlin") { dependsOn(validateModules) }
