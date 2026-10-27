import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

// The iOS app's view of the Kotlin core (FBBD-329, FBBD-330): one BetterDriverCore.xcframework
// that exports every core module with iOS targets. Only one small entry of its own (CoreIos, which a
// framework link needs); each module tests its common code on the iOS simulator itself.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// The modules the app sees, in dependency order. Add a module here when it gains iOS targets.
val exported = listOf(":shared", ":recorder")

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
    }
}
