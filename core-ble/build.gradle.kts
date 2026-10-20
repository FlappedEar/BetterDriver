plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.flappedear.betterdriver.core.ble"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // RaceBoxSource is a recorder DataSource and writes recorder channels.
    api(project(":recorder"))
    // ObdSource drives the ELM327 reader and decodes PIDs (FBBD-15).
    implementation(project(":obd"))
    // The scanner and link state are Flows.
    api(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
