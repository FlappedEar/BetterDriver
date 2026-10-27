plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.flappedear.betterdriver.core.ui"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // Robolectric screenshot tests need the fonts and other resources.
        unitTests.isIncludeAndroidResources = true
    }
    sourceSets {
        // Layout checks shared by the screen tests (FBBD-40).
        getByName("test").java.srcDir("src/layoutTest/kotlin")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Screenshot goldens (FBBD-62), recorded in CI by screenshots.yml and verified by ci.yml.
roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

// The debug and release unit tests share that folder. Roborazzi's finalize step for one variant
// rewrote it while the other's test task was reading it as an input, and the task failed with
// NoSuchFileException (track_screen.png on main e5ae8ee, delta_display.png on #260). Run the
// variants one after the other.
tasks.matching { it.name == "testReleaseUnitTest" }.configureEach {
    mustRunAfter(tasks.matching { it.name == "testDebugUnitTest" || it.name == "finalizeTestRoborazziDebug" })
}

dependencies {
    // Lap time and delta formatting, shared with the iOS app (FBBD-329).
    api(project(":shared"))
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)
    api(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.compose.ui.test.junit4)
}
