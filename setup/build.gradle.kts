// Pure Kotlin/JVM: what Arek has set up in the app (FBBD-83). His equipment (GNSS receivers,
// cameras), his car profiles and the choice for the next session, stored as one JSON file. No
// Android dependencies, so it is tested on the JVM. Nothing about his hardware or cars is assumed
// at build time; the app shows only what is set up here.
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
