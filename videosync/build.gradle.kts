// Reading GoPro files and lining them up with a session, shared by Android and iOS (FBBD-22,
// FBBD-343): the MP4 index and GPMF GPS readers, GPS speed matching, the clip index format and
// where a camera's files fall in a session. Common Kotlin over an abstract file ([Mp4Source]), so
// :video and :gopro keep their java.io wrappers and the iOS app reads files through Foundation.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    jvm()
    iosArm64()
    iosSimulatorArm64()

    // IoProblem is an expect class: java.io.IOException on the JVM, so callers that handle
    // IOException handle every damaged file as before the readers were common.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":recorder"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(project.dependencies.platform(libs.junit5.bom))
            implementation(libs.junit5.jupiter)
            runtimeOnly(libs.junit5.launcher)
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
