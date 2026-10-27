// Tracks, track detection, line crossings, lap and sector timing and the predictive delta. No
// Android dependencies, so all of it is tested with fake or recorded GNSS. It reads the
// recorder's live output but the recorder never depends on it. Kotlin Multiplatform (FBBD-330):
// the platform-free part is common Kotlin in src/commonMain and also builds for iOS; file
// storage stays JVM code in src/main until it moves over.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":recorder"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain {
            kotlin.srcDir("src/main/kotlin")
            resources.srcDir("src/main/resources")
        }
        jvmTest {
            kotlin.srcDir("src/test/kotlin")
            resources.srcDir("src/test/resources")
            dependencies {
                implementation(project.dependencies.platform(libs.junit5.bom))
                implementation(libs.junit5.jupiter)
                runtimeOnly(libs.junit5.launcher)
            }
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}

// CI runs `./gradlew test`; let that include this module's JVM tests.
tasks.register("test") {
    dependsOn("jvmTest")
}
