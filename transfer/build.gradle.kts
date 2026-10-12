// Pure Kotlin/JVM: moving sessions (with their video) and tracks between the phone and the tablet
// (FBBD-23, FBBD-80). A session travels as one bundle file and a track as one JSON file; Quick Share
// or any file copy carries them. Backup and restore of the app's data as one file (FBBD-156) use
// the same container. Sessions from other apps (VBO, GPX, RaceChrono CSV) are read into ordinary
// sessions by `foreign` (FBBD-164). No Android dependencies, so it is tested on the JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":recorder"))
    api(project(":setup"))
    api(project(":timing"))
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test {
    useJUnitPlatform()
}
