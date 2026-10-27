// Moving sessions (with their video) and tracks between the phone and the tablet (FBBD-23,
// FBBD-80). A session travels as one bundle file and a track as one JSON file; Quick Share or any
// file copy carries them. Backup and restore of the app's data as one file (FBBD-156) use the same
// container. Sessions from other apps (VBO, GPX, RaceChrono CSV) are read into ordinary sessions by
// `foreign` (FBBD-164). No Android dependencies.
//
// Kotlin Multiplatform (FBBD-331): the bundle format and its checks are common Kotlin in
// src/commonMain and also build for iOS, where src/iosMain imports bundles into the iOS session
// store; the java.io-based writers, readers and backup stay JVM code in src/main. Of the foreign
// readers, VBO and RaceChrono CSV and the session they make are common; GPX (SAX) and gzip stay JVM.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(17)
    jvm()
    iosArm64()
    iosSimulatorArm64()

    // ImportFormatException is an expect class: an IOException on the JVM, as before the import was common.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":recorder"))
            api(project(":timing"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain {
            kotlin.srcDir("src/main/kotlin")
            dependencies {
                api(project(":setup"))
            }
        }
        jvmTest {
            kotlin.srcDir("src/test/kotlin")
            resources.srcDir("src/test/resources")
            dependencies {
                // MockSessionTest (FBBD-191) adds a video clip to the session it builds.
                implementation(project(":video"))
                implementation(project.dependencies.platform(libs.junit5.bom))
                implementation(libs.junit5.jupiter)
                runtimeOnly(libs.junit5.launcher)
            }
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    // MockSessionTest (FBBD-191) builds a mock session only when tools/mock-session/make.sh passes its inputs.
    for (name in listOf("mockVbo", "mockVideo", "mockVideoStartUtcMs", "mockVideoDurationUs", "mockOut", "mockTrackName")) {
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
}

// CI runs `./gradlew test`; let that include this module's JVM tests.
tasks.register("test") {
    dependsOn("jvmTest")
}
