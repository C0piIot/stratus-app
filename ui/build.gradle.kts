plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    androidLibrary {
        namespace = "dev.stratus.ui"
        compileSdk = 36
        minSdk = 29
    }

    // The framework Xcode embeds. Static, so there is one artifact to carry and
    // no dynamic library to sign and ship beside it.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "StratusUI"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: App() takes a SignInController, so the
            // application module has to be able to name one.
            api(project(":core"))
            // api, not implementation: the Android application calls App() and
            // needs the runtime on its own compile classpath to do it.
            api(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
        }
    }
}
