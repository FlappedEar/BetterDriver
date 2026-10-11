// Pure Kotlin/JVM: the video-source interface shared by the phone camera (FBBD-21) and the
// GoPros (FBBD-22), the per-session video index, and video-to-session time alignment. No Android
// dependencies, so it is tested on the JVM. The recorder never depends on it: a camera failure
// cannot reach the session data (FBBD-69).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test {
    useJUnitPlatform()
}
