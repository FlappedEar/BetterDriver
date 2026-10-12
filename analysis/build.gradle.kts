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
    // api: channels and the analysis carry the recorder's Provenance (FBBD-174).
    api(project(":recorder"))
    implementation(project(":timing"))
    // api: QualityReport is @Serializable, so its users see the serializer types.
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test {
    useJUnitPlatform()
}
