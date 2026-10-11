plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release and CI builds pass the commit count as the build number, so each
// build installs over the previous one (release.yml, ci.yml).
val appVersionName = providers.gradleProperty("betterdriver.version").get()
val appVersionCode = providers.gradleProperty("betterdriver.build").orNull?.toInt() ?: 1

android {
    namespace = "com.flappedear.betterdriver"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.flappedear.betterdriver"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key of the machine that builds it (a
            // throwaway key in CI). release.yml re-signs it with the release
            // key in a separate job that runs no project code.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-ui"))
    implementation(project(":feature-track"))
    implementation(project(":feature-review"))
    implementation(project(":feature-analysis"))
    implementation(project(":recorder"))
    implementation(project(":video"))
    implementation(project(":gopro"))
    implementation(project(":setup"))
    implementation(project(":timing"))
    implementation(project(":core-ble"))
    implementation(project(":updater"))
    implementation(project(":transfer"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.compose.material3.window.size)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
