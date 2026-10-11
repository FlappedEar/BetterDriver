// Pure Kotlin/JVM: GoPro support (FBBD-22). The Open GoPro BLE protocol and HERO11 control
// against an abstract BLE link, the camera's HTTP media API, and importing GoPro files into a
// session with time alignment. The Android BLE and Wi-Fi glue lives in the app, so all of this
// is tested on the JVM. Nothing here is used by the recorder (FBBD-69).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":video"))
    implementation(project(":recorder"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(testFixtures(project(":video")))
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test {
    useJUnitPlatform()
}
