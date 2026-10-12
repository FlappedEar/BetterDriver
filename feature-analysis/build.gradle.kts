// Session analysis screens (Phase 3, FBBD-102): laps, theoretical best and lap comparison by
// distance, drawn from the pure Kotlin :analysis results.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.flappedear.betterdriver.feature.analysis"
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-ui"))
    api(project(":analysis"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // Every lap's pass through a corner on video (FBBD-153).
    implementation(libs.androidx.media3.exoplayer)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
