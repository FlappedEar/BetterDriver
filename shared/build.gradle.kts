// Kotlin Multiplatform base shared by the Android app (JVM target) and the iOS app (FBBD-329):
// formatting, maths and the version, used by every core module. Code here must not use JVM-only
// APIs. The iOS app gets it, with the other core modules, through :core-ios.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// The app version, so the iOS app shows the same version as the Android app.
val generateCoreVersion by tasks.registering {
    val version = providers.gradleProperty("betterdriver.version")
    val outDir = layout.buildDirectory.dir("generated/coreVersion")
    inputs.property("version", version)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("com/flappedear/betterdriver/core/CoreVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.flappedear.betterdriver.core
            |
            |internal const val CoreVersion = "${version.get()}"
            |""".trimMargin(),
        )
    }
}

kotlin {
    jvmToolchain(17)
    jvm()

    // Device and Apple-silicon simulator. GitHub's macOS runners and current Macs are arm64.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            kotlin.srcDir(generateCoreVersion)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// CI runs `./gradlew test` on Linux; let that include this module's JVM tests.
tasks.register("test") {
    dependsOn("jvmTest")
}
