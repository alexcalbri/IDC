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

// Kotlin compiler shipped in the distribution (compiler/) so the server can build optional
// modules from source when the server owner installs them. Same version as the server.
val moduleCompiler: Configuration = configurations.create("moduleCompiler") {
    isCanBeConsumed = false
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

    moduleCompiler("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.asProvider().get()}")
    moduleCompiler("org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:${libs.versions.kotlin.asProvider().get()}")
}

val modulesRoot = rootProject.layout.projectDirectory.dir("modules").asFile

// Folders under modules/ that contain files (empty scaffolds left by Git are ignored).
val moduleDirs = modulesRoot.listFiles()
    ?.filter { dir -> dir.isDirectory && dir.walkTopDown().any { it.isFile } }
    ?.sortedBy { it.name }
    .orEmpty()

// Base modules ("bundled": true in module.json) are compiled into the server. Every other
// module is optional: it is compiled here only to catch errors and for :server:run, and the
// installed server builds it from the configured catalog when the server owner installs it.
fun isBundled(moduleDir: File): Boolean = moduleDir.resolve("module.json").takeIf { it.isFile }
    ?.let { runCatching { (groovy.json.JsonSlurper().parse(it) as? Map<*, *>)?.get("bundled") == true }.getOrDefault(false) }
    ?: false

val bundledModuleDirs = moduleDirs.filter(::isBundled)
val optionalModuleDirs = moduleDirs.filterNot(::isBundled)

fun providerEntries(dirs: List<File>): List<String> = dirs.mapNotNull { moduleDir ->
    val moduleName = moduleDir.name
    val providerName = moduleName.split('_').filter { it.isNotBlank() }
        .joinToString("") { part -> part.replaceFirstChar { it.uppercase() } } + "ServerModuleProvider"
    val declaresProvider = moduleDir.resolve("server/src/main/kotlin").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .any { it.readText().contains("class $providerName") }
    if (declaresProvider) "com.ideasdeveloper.idc.modules.$moduleName.$providerName" else null
}

fun writeProviderFile(directory: Provider<Directory>, entries: List<String>) {
    val file = directory.get().file("META-INF/services/com.ideasdeveloper.idc.server.modules.ServerModuleProvider").asFile
    file.parentFile.mkdirs()
    file.writeText(entries.joinToString(separator = "\n", postfix = "\n"))
}

val bundledServicesDir = layout.buildDirectory.dir("generated/resources/module-services")
val optionalServicesDir = layout.buildDirectory.dir("generated/resources/optional-module-services")
writeProviderFile(bundledServicesDir, providerEntries(bundledModuleDirs))
writeProviderFile(optionalServicesDir, providerEntries(optionalModuleDirs))

sourceSets {
    main {
        resources.srcDir(bundledServicesDir)
        bundledModuleDirs.forEach { resources.srcDir(it.resolve("server/src/main/resources")) }
    }
    create("modules") {
        resources.srcDir(optionalServicesDir)
        optionalModuleDirs.forEach { resources.srcDir(it.resolve("server/src/main/resources")) }
    }
}

configurations["modulesImplementation"].extendsFrom(configurations["implementation"], configurations["api"])
configurations["modulesRuntimeOnly"].extendsFrom(configurations["runtimeOnly"])

dependencies {
    "modulesImplementation"(sourceSets.main.get().output)
    testImplementation(sourceSets["modules"].output)
}

kotlin {
    sourceSets {
        main {
            bundledModuleDirs.forEach { kotlin.srcDir(it.resolve("server/src/main/kotlin")) }
        }
        named("modules") {
            optionalModuleDirs.forEach { kotlin.srcDir(it.resolve("server/src/main/kotlin")) }
        }
        test {
            moduleDirs.forEach { kotlin.srcDir(it.resolve("server/src/test/kotlin")) }
        }
    }
}

// Local development runs optional modules from the source tree; the distribution does not.
tasks.named<JavaExec>("run") {
    classpath += sourceSets["modules"].runtimeClasspath
}

distributions {
    main {
        contents {
            from(moduleCompiler) { into("compiler") }
        }
    }
}

// Enforces the module layout documented in modules/README.md before the server compiles.
val validateModules = tasks.register("validateModules") {
    group = "verification"
    description = "Checks that every folder under modules/ follows the IdeasCore module layout."
    val modulesDir = modulesRoot
    inputs.dir(modulesDir)
    doLast {
        val idPattern = Regex("[a-z][a-z0-9_]{0,62}")
        val migrationPattern = Regex("V[0-9]{3}__[a-z0-9_]+\\.sql")
        val allowedTopLevel = setOf("README.md", "module.json", "migrations", "server")
        val allowedSourceRoots = setOf("src/main/kotlin", "src/main/resources", "src/test/kotlin")
        val problems = mutableListOf<String>()

        modulesDir.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.forEach { module ->
            val id = module.name
            // Empty scaffolds (only folders, as left by Git) are skipped.
            if (module.walkTopDown().none { it.isFile }) return@forEach
            fun problem(message: String) = problems.add("modules/$id: $message")

            if (!idPattern.matches(id)) problem("the folder name must match ${idPattern.pattern} (it is the module id and Kotlin package).")
            if (!module.resolve("README.md").isFile) problem("README.md is required.")
            module.listFiles().orEmpty()
                .filter { !it.name.startsWith(".") && it.name !in allowedTopLevel }
                .forEach { problem("'${it.name}' is not allowed here; only ${allowedTopLevel.joinToString()} (see modules/README.md).") }

            val manifest = module.resolve("module.json")
            var manifestVersion: String? = null
            if (!manifest.isFile) {
                problem("module.json is required (the module catalog lists modules from it).")
            } else {
                val json = runCatching { groovy.json.JsonSlurper().parse(manifest) as? Map<*, *> }.getOrNull()
                if (json == null) {
                    problem("module.json is not a valid JSON object.")
                } else {
                    if (json["id"] != id) problem("module.json id must be '$id'.")
                    listOf("displayName", "description", "version").forEach { key ->
                        if ((json[key] as? String).isNullOrBlank()) problem("module.json requires '$key'.")
                    }
                    if (json.containsKey("bundled") && json["bundled"] !is Boolean) problem("module.json 'bundled' must be true or false.")
                    manifestVersion = json["version"] as? String
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
                val serverModuleSource = mainKotlin.firstOrNull { it.contains(Regex(":\\s*ServerModule\\b")) }
                if (serverModuleSource != null && mainKotlin.none { it.contains("class $provider") }) {
                    problem("implements ServerModule but does not declare class $provider, so the server would never load it.")
                }
                // The catalog shows module.json; the server reports the version in the code. Keep them equal.
                val codeVersion = serverModuleSource?.let { Regex("\\bversion\\s*=\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
                if (codeVersion != null && manifestVersion != null && codeVersion != manifestVersion) {
                    problem("module.json version '$manifestVersion' differs from the ModuleDefinition version '$codeVersion'.")
                }
                // A migration missing from migrationPaths is never applied to any company.
                if (serverModuleSource != null) {
                    module.resolve("migrations").listFiles().orEmpty().filter { migrationPattern.matches(it.name) }.sortedBy { it.name }.forEach { file ->
                        val path = "modules/$id/migrations/${file.name}"
                        if (mainKotlin.none { it.contains("\"$path\"") }) problem("migrations/${file.name} is not listed in migrationPaths, so it would never be applied.")
                    }
                }
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException("Module layout problems:\n - " + problems.joinToString("\n - "))
        }
    }
}

tasks.named("compileKotlin") { dependsOn(validateModules) }
tasks.named("compileModulesKotlin") { dependsOn(validateModules) }
// Optional modules must compile even though the distribution does not include them.
tasks.named("installDist") { dependsOn("modulesClasses") }
