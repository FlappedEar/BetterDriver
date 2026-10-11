// Pure Kotlin/JVM: the live coach (Phase 4, FBBD-6). Corners and the per-track reference from earlier
// sessions, the cue engine and the cue review. No Android dependencies, so all of it is tested on the
// JVM with simulated laps. The app runs it in its own process, fed by the recorder, so a coach
// failure never reaches a recording (FBBD-69).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":recorder"))
    implementation(project(":timing"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test {
    useJUnitPlatform()
}
