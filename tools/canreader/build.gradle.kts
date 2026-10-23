// FR-S CAN/OBD reader (FBBD-319): a separate test APK, not part of the BetterDriver app. It reuses
// the app's OBD-II code (:obd) and Bluetooth LE adapter link (:core-ble) and has its own
// applicationId and version, so it installs next to the app and never touches its release line.
// Published by .github/workflows/canreader.yml as a canreader-vX.Y.Z release.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.flappedear.betterdriver.canreader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.flappedear.betterdriver.canreader"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // As the app: signed with the building machine's debug key; canreader.yml re-signs it in a
            // job that runs no project code.
            signingConfig = signingConfigs.getByName("debug")
        }
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
    implementation(project(":obd"))
    implementation(project(":core-ble"))
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
