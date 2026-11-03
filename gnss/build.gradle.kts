// GNSS receivers other than the RaceBox without their links (FBBD-374): NMEA 0183 and RaceChrono
// DIY $RC2/$RC3 streams, Bluetooth LE Location and Navigation (LNS) and RaceChrono DIY BLE GPS
// decoded into recorder channels, and what a receiver link does to a recording. Common Kotlin, so
// the Android app (core-ble: SPP, TCP and GATT links) and the iOS app (TCP and CoreBluetooth)
// record a receiver the same way. The links stay per platform.
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
            // LinkState, logLink, utcEpochMillis and the shared notify link (FBBD-358).
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
