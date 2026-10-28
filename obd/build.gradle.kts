// The OBD-II reader without Bluetooth (FBBD-53, FBBD-16, FBBD-121, FBBD-333): channel definitions with
// RaceChrono-compatible equations, the standard mode 01 PIDs, car profiles, ELM327 response parsing,
// the polling scheduler, the FR-S CAN listen and the connect/retry loop. Kotlin Multiplatform, so the
// Android app (core-ble) and the iOS app (over CoreBluetooth) read a car the same way; the transports
// stay per platform. All of it is tested on the JVM with recorded and simulated adapter output.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(17)
    jvm()
    iosArm64()
    iosSimulatorArm64()

    // IOException and the monitor are expect classes, typealiased to the JVM's own on Android.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":recorder"))
            implementation(project(":shared"))
            // The driver's own parameters and the last scan are stored with the car (FBBD-344).
            implementation(project(":setup"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest {
            kotlin.srcDir("src/test/kotlin")
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
