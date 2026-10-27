// The recorder core has no Android dependencies. Kotlin Multiplatform (FBBD-330): the session
// model and the platform-free logic are common Kotlin in src/commonMain and also build for iOS;
// the file-backed store, the writer threads and the exporters stay JVM code in src/main until
// they move over. The Android service that hosts the recorder lives elsewhere.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(17)
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":shared"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain {
            kotlin.srcDir("src/main/kotlin")
        }
        jvmTest {
            kotlin.srcDir("src/test/kotlin")
            resources.srcDir("src/test/resources")
            dependencies {
                implementation(project.dependencies.platform(libs.junit5.bom))
                implementation(libs.junit5.jupiter)
                runtimeOnly(libs.junit5.launcher)
            }
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    // FaultInjectionTest (FBBD-72) starts child JVMs and kills them; skip it locally with -PskipFaultInjection.
    // SyncKitTest (FBBD-51) writes the Circuit Tools test pairs only when given a directory.
    providers.gradleProperty("syncKitDir").orNull?.let { systemProperty("syncKitDir", it) }
    if (project.hasProperty("skipFaultInjection")) {
        exclude("**/FaultInjectionTest*")
    }
}

// CI runs `./gradlew test`; let that include this module's JVM tests.
tasks.register("test") {
    dependsOn("jvmTest")
}
