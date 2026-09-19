// `java` inside an `android` block is Gradle's own extension, not the package.
import java.util.Base64

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

// The same signature on every build, where CI has been given one.
//
// Without this the APK is signed with a debug key Gradle mints inside the build
// container and throws away with it, so every build has a different signature
// and Android refuses to install one over the last -- which means uninstalling,
// and losing the session and the cache, to try any change on a real phone.
//
// A key for sideloading and nothing more: when there are stable releases they
// get one of their own. Absent the secrets -- in a fork, or on a laptop -- the
// ordinary debug key is used and everything still builds.
private val keystoreBase64 = providers.environmentVariable("STRATUS_KEYSTORE_BASE64")
private val keystorePassword = providers.environmentVariable("STRATUS_KEYSTORE_PASSWORD")

android {
    namespace = "dev.stratus.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.stratus.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    if (keystoreBase64.isPresent && keystorePassword.isPresent) {
        val keystore = layout.buildDirectory.file("signing/stratus.jks").get().asFile
        keystore.parentFile.mkdirs()
        keystore.writeBytes(Base64.getDecoder().decode(keystoreBase64.get()))

        signingConfigs.create("sideloaded") {
            storeFile = keystore
            storePassword = keystorePassword.get()
            keyAlias = "stratus-debug"
            keyPassword = keystorePassword.get()
        }
        buildTypes.getByName("debug").signingConfig = signingConfigs.getByName("sideloaded")
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
}
