// The Sentry project crash reports go to, from the build's environment and
// never from the source: a fork builds with none, and then the app has no
// reporting and no switch offering it, rather than reporting to us.
val reportingDsn = providers.environmentVariable("SENTRY_DSN").orElse("")

val generateReportingDsn by tasks.registering {
    val dsn = reportingDsn
    val out = layout.buildDirectory.dir("generated/reporting/kotlin")
    inputs.property("dsn", dsn)
    outputs.dir(out)
    doLast {
        val file = out.get().file("dev/stratus/ui/ReportingDsn.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package dev.stratus.ui\n\ninternal const val REPORTING_DSN = \"${dsn.get()}\"\n",
        )
    }
}

// Which build this is, for the row at the bottom of Settings. From the
// environment like the DSN above, because git runs on the host: the Makefile
// reads it and passes it in, so there is no git inside the toolchain image and
// no `safe.directory` to configure.
//
// The date is the commit's and not today's -- a rebuild of one commit has to
// produce the same APK -- and a build made outside a git tree says `dev` and
// `unknown` rather than inventing something.
val buildVersion = providers.environmentVariable("STRATUS_VERSION").orElse("dev")
val buildDate = providers.environmentVariable("STRATUS_BUILD_DATE").orElse("unknown")

val generateBuildStamp by tasks.registering {
    val version = buildVersion
    val date = buildDate
    val out = layout.buildDirectory.dir("generated/buildstamp/kotlin")
    inputs.property("version", version)
    inputs.property("date", date)
    outputs.dir(out)
    doLast {
        val file = out.get().file("dev/stratus/ui/BuildStamp.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package dev.stratus.ui\n\n" +
                "internal const val BUILD_VERSION = \"${version.get()}\"\n" +
                "internal const val BUILD_DATE = \"${date.get()}\"\n",
        )
    }
}

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
            // Compiled in is not the same as visible: without this, `:core`'s
            // declarations are inside the framework and absent from its
            // headers, so Swift cannot name them. The app needs to, for the
            // door a background transfer comes back through (stratus-app#20).
            export(project(":core"))
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateReportingDsn)
            kotlin.srcDir(generateBuildStamp)
        }
        commonMain.dependencies {
            // api, not implementation: App() takes a SignInController, so the
            // application module has to be able to name one.
            api(project(":core"))
            // api, not implementation: the Android application calls App() and
            // needs the runtime on its own compile classpath to do it.
            api(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.lifecycle.runtime.compose)
            // Two halves of it are left out of the APK; see androidApp.
            implementation(libs.sentry)
        }
    }
}
