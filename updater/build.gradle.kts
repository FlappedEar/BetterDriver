// Pure Kotlin/JVM: the in-app updater (FBBD-73, FBBD-74). Finds a newer release on GitHub,
// downloads its APK, checks it against SHA256SUMS.txt and the pinned release certificate, and does
// nothing while a session records. Network and APK inspection come in through interfaces, so all
// of it is tested on the JVM with a fake network.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test {
    useJUnitPlatform()
}
