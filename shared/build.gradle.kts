import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

// Kotlin Multiplatform core shared by the Android app (JVM target) and the iOS app
// (BetterDriverCore.xcframework, FBBD-329). Code here must not use JVM-only APIs; the pure
// Kotlin modules move their platform-free code here or gain iOS targets themselves (FBBD-330).
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
    val xcframework = XCFramework("BetterDriverCore")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "BetterDriverCore"
            isStatic = true
            xcframework.add(this)
        }
    }
    // The simulator the common tests run on (name or UDID); ios.yml passes one that exists.
    providers.gradleProperty("iosSimulator").orNull?.let { device ->
        iosSimulatorArm64().testRuns.named("test") { deviceId = device }
    }

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
