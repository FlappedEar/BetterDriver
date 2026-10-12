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
        // Emulator smoke tests (FBBD-161): ./gradlew :app:connectedDebugAndroidTest
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The UI-process kill test (FBBD-72) runs in phases with the process killed between them,
        // so .github/scripts/ui-kill.sh runs it on its own, not with the smoke tests.
        testInstrumentationRunnerArguments["notAnnotation"] = "com.flappedear.betterdriver.uikill.UiKillPhase"
    }

    sourceSets {
        // The smoke tests' sessions are made from the analysis parity corpus (SmokeFixtures).
        getByName("androidTest").assets.srcDir("../analysis/src/test/resources/parity/corpus")
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
    implementation(project(":coach"))
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
    // CameraX hands out ListenableFuture. Media3 (lap review) brings Guava, which swaps the standalone
    // listenablefuture jar for an empty one but keeps Guava itself off the compile classpath.
    implementation(libs.guava)
    // "Video with data" export (FBBD-152): renders the data overlay into the session's video on the phone.
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.compose.material3.window.size)
    debugImplementation(libs.compose.ui.tooling)
    // JankStats for the debug build's slow-frame log (FBBD-41); release builds do not have it.
    debugImplementation(libs.androidx.metrics.performance)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.espresso.intents)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
