plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
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

dependencies {
    implementation(project(":ui"))
    implementation(libs.androidx.activity.compose)
}
