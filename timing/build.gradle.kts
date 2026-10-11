// Pure Kotlin/JVM: tracks, track detection, line crossings, lap and sector timing and the
// predictive delta. No Android dependencies, so all of it is tested on the JVM with fake or
// recorded GNSS. It reads the recorder's live output but the recorder never depends on it.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":recorder"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test {
    useJUnitPlatform()
}
