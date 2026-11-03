import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

// Version of the apps, shared with Xcode: app/Version.xcconfig.
val appVersionFile = rootProject.layout.projectDirectory.file("app/Version.xcconfig")
fun appVersionValue(key: String): Provider<String> = providers.fileContents(appVersionFile).asText.map { text ->
    text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.trim()
        ?: error("$key no esta en app/Version.xcconfig")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":app:shared"))

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "com.ideasdeveloper.idc"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.ideasdeveloper.idc"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = appVersionValue("APP_BUILD").get().toInt()
        versionName = appVersionValue("APP_VERSION").get()
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}
