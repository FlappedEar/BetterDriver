plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.flappedear.betterdriver.feature.track"
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
        getByName("test").java.srcDir("../core-ui/src/layoutTest/kotlin")
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
    implementation(project(":core-ui"))
    implementation(project(":timing"))
    implementation(libs.androidx.core.ktx)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.compose.ui.test.junit4)
}
