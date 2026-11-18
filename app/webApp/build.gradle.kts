
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    js {
        browser {
            // Sin source maps en el empaquetado: generarlos hacia que webpack se quedara sin memoria
            // en el servidor (limite de 2 GB de update.sh) y ademas publicaba el codigo fuente.
            // El servidor de desarrollo (jsBrowserDevelopmentRun) los conserva.
            webpackTask {
                sourceMaps = false
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":app:shared"))

            implementation(libs.compose.ui)
        }
        jsMain.dependencies {
            implementation(libs.wrappers.browser)
        }
    }
}
