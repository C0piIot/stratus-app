plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

kotlin {
    // A JVM target ships nowhere. It exists so the shared tests run on a plain
    // JVM in milliseconds, with no Android, no Robolectric and no emulator --
    // which is what makes a free-runner test budget stretch far enough to cover
    // the protocol layer properly. See CLAUDE.md.
    jvm()

    androidTarget()

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "dev.stratus.core"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
}
