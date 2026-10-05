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
        minSdk = 29

        // The Android halves were compiled and never run. This is the only way
        // to find out whether a cursor reads what was seeded and whether a
        // permission state is reported as one.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

            // Declared in the build rather than in the CI workflow, so the same
            // command works on a laptop: AGP downloads the image and runs it.
            // An atd image because it boots faster and carries nothing a test
            // of MediaStore needs to see.
            managedDevices {
                localDevices.create("emulator") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp-atd"
                    // Pinned rather than defaulted, because the default becomes
                    // arm64-v8a in AGP 10.0 -- which would fetch a different
                    // system image and quietly empty the cache the Makefile
                    // mounts, on a machine where this image is the right one.
                    //
                    // AGP 9.4.0 goes on printing "does not specify a testedAbi"
                    // even with this set, and the property is real -- it is
                    // `getTestedAbi`/`setTestedAbi` on ManagedVirtualDevice in
                    // gradle-api-9.4.0. So the warning looks like a check that
                    // reads the wrong place rather than a value being ignored.
                    // Worth settling at the AGP 10 bump, which is the release
                    // where being wrong about it would start costing a download.
                    testedAbi = "x86_64"
                }
            }
        }
    }

    // The framework the File Provider extension links (stratus-app#105).
    // `:ui` produces one too, and an extension cannot use it: that one carries
    // Compose, and an extension runs on a much shorter memory budget than an
    // app. Static for the reason `:ui` gives -- one artifact to carry.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "StratusCore"
            isStatic = true
        }
    }

    // The trust manager is plain JDK code with no Android API in it, and Android
    // is where it ships -- so it lives where both the Android target and the JVM
    // one can see it, which is what puts a security control in the fast loop
    // instead of on a device. Declared as a group on the default template rather
    // than with a manual `dependsOn`, because a manual edge switches the default
    // hierarchy off entirely and takes the iOS source sets with it.
    applyDefaultHierarchyTemplate {
        common {
            group("jvmCommon") {
                withJvm()
                withCompilations { it.target.name == "android" }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            // An XML parser is not something to hand-roll: namespaces declared in
            // unhelpful places, numeric entity references and CDATA are exactly
            // the cases a home-made one gets wrong, and PROPFIND uses all three.
            implementation(libs.xmlutil.core)
            // SQLite with the engine bundled, so every target -- the JVM one
            // included -- runs the same database. That is what lets the schema
            // and the queries be covered by the fast loop rather than by a fake.
            implementation(libs.androidx.sqlite.bundled)
        }
        // The engines, at last: until now nothing in the app could make a real
        // request. Each target gets the one that belongs to it, and common code
        // never names an engine -- it is handed one.
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            // FileProvider: a file:// URI has been refused since Android 7.
            implementation(libs.androidx.core)
            // Scheduling that survives a reboot, and the foreground service that
            // keeps a transfer alive while it runs.
            implementation(libs.androidx.work)
            // The Cast sender. Present on every build and useless on a phone
            // without Play Services, which is exactly how it is meant to be:
            // the app installs and runs there, and the button never appears.
            implementation(libs.play.services.cast)
            implementation(libs.androidx.mediarouter)
            // Already in the APK through :ui; named here so casting can report
            // what it did, which is the only way to see a television's side.
            implementation(libs.sentry)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        // The Darwin trust half has the one test in this project that needs a
        // Mac: a real TLS handshake against a certificate nothing vouches for,
        // on the simulator (stratus-app#58).
        iosTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // The JVM target ships nowhere, but it needs an engine to run the
        // conformance suite against a real server.
        // No typed accessor for this one: the device-test source set is created
        // by withDeviceTest above, after the accessors are generated.
        getByName("androidDeviceTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.rules)
            implementation(libs.androidx.test.junit)
            implementation(libs.androidx.work.testing)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

// The conformance suite talks to a real server over a network, so it is not part
// of the ordinary run: `make test` has to stay offline and finish in seconds.
// Selecting with a property rather than registering a second Test task keeps the
// two using one configuration, which is what stops them drifting apart.
private val conformancePackage = "*.conformance.*"

// Every test task, which since stratus-app#58 means the simulator's too: the
// Darwin trust half is proved against a real TLS server the macOS job starts,
// and it is selected the same way the JVM suite is.
//
// Kotlin/Native also reports a failed test as a class name and nothing else,
// which is useless for anything thrown with a message worth reading -- an
// OSStatus from the Keychain, say -- hence the logging.
tasks.withType<AbstractTestTask>().configureEach {
    val conformance = providers.gradleProperty("conformance").isPresent
    filter.apply {
        if (conformance) includeTestsMatching(conformancePackage) else excludeTestsMatching(conformancePackage)
        isFailOnNoMatchingTests = conformance
    }
    if (conformance) {
        // The server it talks to is not an input Gradle can see, so a second run
        // against a different one would otherwise be reported as up to date.
        outputs.upToDateWhen { false }
    }
    testLogging {
        showExceptions = true
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Where the simulator's TLS test finds its server.
//
// Handed to the task by name rather than inherited: a test binary does not run
// in this process, it runs under `simctl`, which passes nothing through unless
// the plugin is told to -- and it prefixes what it is told with SIMCTL_CHILD_
// on the way. The JVM suites take theirs from the ambient environment because
// they do run here.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    for (name in listOf("STRATUS_TLS_URL", "STRATUS_TLS_FINGERPRINT")) {
        val value = providers.environmentVariable(name).orNull ?: continue
        // Both spellings on purpose. `simctl spawn` hands the child only the
        // variables prefixed SIMCTL_CHILD_, with the prefix taken off -- and
        // whether the plugin adds that prefix for you has changed between
        // versions. Setting both means the test sees the name either way, and
        // the loser is one unread variable.
        environment(name, value)
        environment("SIMCTL_CHILD_$name", value)
    }
}
