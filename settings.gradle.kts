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
    ":video",
    ":gopro",
    ":setup",
    ":updater",
    ":core-ui",
    ":core-ble",
    ":feature-track",
    ":feature-review",
)
