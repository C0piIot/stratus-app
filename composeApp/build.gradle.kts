plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    androidTarget()

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            // Static, so the Xcode side has one artifact to embed and no dynamic
            // library to sign and ship alongside it.
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
    }
}

android {
    namespace = "dev.stratus.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.stratus.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}
