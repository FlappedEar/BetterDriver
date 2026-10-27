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

// -Pbetterdriver.coreOnly builds only the Kotlin Multiplatform core, without the Android
// modules (and so without the Android SDK): the iOS workflow on macOS uses it (FBBD-329).
if (providers.gradleProperty("betterdriver.coreOnly").isPresent) {
    include(":shared")
} else {
    include(
        ":app",
        ":recorder",
        ":timing",
        ":coach",
        ":analysis",
        ":video",
        ":gopro",
        ":setup",
        ":updater",
        ":transfer",
        ":obd",
        ":core-ui",
        ":core-ble",
        ":feature-track",
        ":feature-review",
        ":feature-analysis",
        // Kotlin Multiplatform core shared with the iOS app (FBBD-328).
        ":shared",
        // FR-S CAN/OBD reader, a separate test APK (FBBD-319).
        ":tools:canreader",
    )
}
