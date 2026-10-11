// Pure Kotlin/JVM: moving sessions (with their video) and tracks between the phone and the tablet
// (FBBD-23, FBBD-80). A session travels as one bundle file and a track as one JSON file; Quick Share
// or any file copy carries them. No Android dependencies, so it is tested on the JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":recorder"))
    api(project(":timing"))
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test {
    useJUnitPlatform()
}
