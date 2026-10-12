plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt)
}

// Static analysis for every module: ./gradlew detekt
allprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        // The app's debug and release source sets hold the debug-only guardrails and their twin (FBBD-41).
        source.setFrom(
            "src/main/kotlin",
            "src/test/kotlin",
            "src/androidTest/kotlin",
            "src/debug/kotlin",
            "src/release/kotlin",
            "src/testDebug/kotlin",
        )
    }
    // detekt 1.23.8 is built against Kotlin 2.0.21 and refuses to run on a
    // newer compiler, so its own classpath keeps that Kotlin version.
    configurations.matching { it.name == "detekt" }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") useVersion("2.0.21")
        }
    }
}
