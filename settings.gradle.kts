pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BetterDriver"

// The Kotlin Multiplatform core of the iOS app (FBBD-328): :core-ios exports the others.
val iosCore = listOf(":shared", ":recorder", ":racebox", ":heartrate", ":timing", ":coach", ":analysis", ":transfer", ":core-ios")

// -Pbetterdriver.coreOnly builds only the Kotlin Multiplatform core, without the Android
// modules (and so without the Android SDK): the iOS workflows on macOS use it (FBBD-329).
if (providers.gradleProperty("betterdriver.coreOnly").isPresent) {
    include(iosCore)
    // Pure JVM helpers of :transfer's JVM side (the backup's setup, the mock session's video).
    include(":setup", ":video")
} else {
    include(
        ":app",
        ":video",
        ":gopro",
        ":setup",
        ":updater",
        ":obd",
        ":core-ui",
        ":core-ble",
        ":feature-track",
        ":feature-review",
        ":feature-analysis",
        // FR-S CAN/OBD reader, a separate test APK (FBBD-319).
        ":tools:canreader",
    )
    include(iosCore)
}
