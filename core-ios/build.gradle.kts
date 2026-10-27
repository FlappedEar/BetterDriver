import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

// The iOS app's view of the Kotlin core (FBBD-329, FBBD-330): one BetterDriverCore.xcframework
// that exports every core module with iOS targets, plus the app's glue around them (LiveSession);
// each module tests its common code on the iOS simulator itself.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// The modules the app sees, in dependency order. Add a module here when it gains iOS targets.
val exported = listOf(":shared", ":setup", ":recorder", ":racebox", ":obd", ":heartrate", ":timing", ":coach", ":analysis", ":transfer")

// Where the tests read the app's built-in demo replays (DemoSessionsTest, FBBD-331), as :analysis
// does: the iOS simulator shares the Mac's file system.
val generateDemoAssets by tasks.registering {
    val dir = file("../app/src/main/assets/demo").absolutePath
    val outDir = layout.buildDirectory.dir("generated/demoAssets")
    inputs.property("dir", dir)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("com/flappedear/betterdriver/ios/DemoAssetsDir.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.flappedear.betterdriver.ios
            |
            |internal const val DemoAssetsDir = "$dir"
            |""".trimMargin(),
        )
    }
}

kotlin {
    // Device and Apple-silicon simulator. GitHub's macOS runners and current Macs are arm64.
    val xcframework = XCFramework("BetterDriverCore")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "BetterDriverCore"
            isStatic = true
            exported.forEach { export(project(it)) }
            xcframework.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            exported.forEach { api(project(it)) }
        }
        commonTest {
            kotlin.srcDir(generateDemoAssets)
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
