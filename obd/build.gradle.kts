// Pure Kotlin/JVM: the OBD-II data model for Phase 5 car data (FBBD-53, FBBD-16). Channel
// definitions with RaceChrono-compatible equations, the standard mode 01 PIDs, car profiles,
// ELM327 response parsing and the polling scheduler. No Android dependencies, so all of it is
// tested on the JVM with recorded adapter output; the Bluetooth ELM327 reader (FBBD-15) builds on it.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test {
    useJUnitPlatform()
}
