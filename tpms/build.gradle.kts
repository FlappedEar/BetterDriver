// Tyre pressures and temperatures without Bluetooth (FBBD-224): the FlappedEar TPMS bridge's
// (RCTPMS) RaceChrono DIY notifications decoded into channels, and what its link does to a
// recording. Common Kotlin, so the Android app (core-ble, over its GATT link) and the iOS app (over
// CoreBluetooth) record the bridge the same way. The Bluetooth links stay per platform.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(17)
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":recorder"))
            // LinkState and the shared notify link (FBBD-358).
            api(project(":racebox"))
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
