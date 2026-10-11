// Pure Kotlin/JVM: the recorder core has no Android dependencies, so it is
// tested on the JVM. The Android service that hosts it lives elsewhere.
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
    // FaultInjectionTest (FBBD-72) starts child JVMs and kills them; skip it locally with -PskipFaultInjection.
    if (project.hasProperty("skipFaultInjection")) {
        exclude("**/FaultInjectionTest*")
    }
}
