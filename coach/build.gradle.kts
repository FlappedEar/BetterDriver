// The live coach (Phase 4, FBBD-6). Corners and the per-track reference from earlier sessions, the
// cue engine and the cue review. No Android dependencies, so all of it is tested on the JVM with
// simulated laps. The app runs it in its own process, fed by the recorder, so a coach failure never
// reaches a recording (FBBD-69). Kotlin Multiplatform (FBBD-330): the platform-free part is common
// Kotlin in src/commonMain and also builds for iOS; file storage stays JVM code in src/main until
// it moves over.
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
            implementation(project(":recorder"))
            implementation(project(":timing"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain {
            kotlin.srcDir("src/main/kotlin")
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
