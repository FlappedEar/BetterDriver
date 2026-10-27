// The heart-rate strap without Bluetooth (FBBD-333): the Heart Rate Measurement decoder, its
// channels and events, and what a strap link does to a recording (the start window, the status).
// Common Kotlin, so the Android app (core-ble, over its GATT link) and the iOS app (over
// CoreBluetooth) record a strap the same way. The Bluetooth links stay per platform.
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
