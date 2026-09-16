plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

kotlin {
    // A JVM target ships nowhere. It exists so the shared tests run on a plain
    // JVM in milliseconds, with no Android, no Robolectric and no emulator --
    // which is what makes a free-runner test budget stretch far enough to cover
    // the protocol layer properly. See CLAUDE.md.
    jvm()

    androidLibrary {
        namespace = "dev.stratus.core"
        compileSdk = 36
        minSdk = 26
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            // An XML parser is not something to hand-roll: namespaces declared in
            // unhelpful places, numeric entity references and CDATA are exactly
            // the cases a home-made one gets wrong, and PROPFIND uses all three.
            implementation(libs.xmlutil.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        // Only the conformance suite talks to a real server, and only from the
        // JVM, so the engine that can is scoped to there rather than shipped.
        jvmTest.dependencies {
            implementation(libs.ktor.client.cio)
        }
    }
}

// The conformance suite talks to a real server over a network, so it is not part
// of the ordinary run: `make test` has to stay offline and finish in seconds.
// Selecting with a property rather than registering a second Test task keeps the
// two using one configuration, which is what stops them drifting apart.
private val conformancePackage = "dev.stratus.core.dav.conformance.*"

tasks.named<Test>("jvmTest") {
    val conformance = providers.gradleProperty("conformance").isPresent
    filter {
        if (conformance) includeTestsMatching(conformancePackage) else excludeTestsMatching(conformancePackage)
        isFailOnNoMatchingTests = conformance
    }
    if (conformance) {
        // The server it talks to is not an input Gradle can see, so a second run
        // against a different one would otherwise be reported as up to date.
        outputs.upToDateWhen { false }
    }
}
