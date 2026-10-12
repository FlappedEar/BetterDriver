// Pure Kotlin/JVM: post-session analysis (FBBD-4), a port of FlappedEar Telemetry's analysis core.
// No Android dependencies, so all of it is tested on the JVM, against Telemetry's own parity
// fixtures. It reads recorded sessions; the recorder never depends on it.
plugins {
    alias(libs.plugins.kotlin.jvm)
    // The session validation report is stored as JSON next to the session (FBBD-183).
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":recorder"))
    // api: SessionAnalysis carries the track's corner identity (FBBD-172), a :timing type.
    api(project(":timing"))
    // api: QualityReport is @Serializable, so its users see the serializer types.
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    // The coach's corners are checked against the analysis's (FBBD-172); the coach never depends on the analysis.
    testImplementation(project(":coach"))
    // Arek's real recordings are read through the session import, as the app reads them (FBBD-164).
    testImplementation(project(":transfer"))
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test {
    useJUnitPlatform()
}
