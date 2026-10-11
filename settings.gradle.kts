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

include(
    ":app",
    ":recorder",
    ":timing",
    ":analysis",
    ":video",
    ":gopro",
    ":setup",
    ":updater",
    ":transfer",
    ":core-ui",
    ":core-ble",
    ":feature-track",
    ":feature-review",
)
