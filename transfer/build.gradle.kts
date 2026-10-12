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
    // MockSessionTest (FBBD-191) adds a video clip to the session it builds.
    testImplementation(project(":video"))
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test {
    useJUnitPlatform()
    // MockSessionTest (FBBD-191) builds a mock session only when tools/mock-session/make.sh passes its inputs.
    for (name in listOf("mockVbo", "mockVideo", "mockVideoStartUtcMs", "mockVideoDurationUs", "mockOut", "mockTrackName")) {
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
}
