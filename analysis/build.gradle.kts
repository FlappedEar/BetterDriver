// Post-session analysis (FBBD-4), a port of FlappedEar Telemetry's analysis core. No Android
// dependencies, so all of it is tested on the JVM, against Telemetry's own parity fixtures. It
// reads recorded sessions; the recorder never depends on it. Kotlin Multiplatform (FBBD-330): the
// platform-free part is common Kotlin in src/commonMain and also builds for iOS; file storage,
// the drive checks and the java.time parts stay JVM code in src/main until they move over.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The session validation report is stored as JSON next to the session (FBBD-183).
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api: channels and the analysis carry the recorder's Provenance (FBBD-174).
            api(project(":recorder"))
            // api: SessionAnalysis carries the track's corner identity (FBBD-172), a :timing type.
            api(project(":timing"))
            // api: QualityReport is @Serializable, so its users see the serializer types.
            api(libs.kotlinx.serialization.json)
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
                // The coach's corners are checked against the analysis's (FBBD-172); the coach never
                // depends on the analysis. Arek's real recordings are read through the session
                // import, as the app reads them (FBBD-164). Neither is in the core-only build of
                // the iOS workflows, which runs no JVM tests.
                listOf(":coach", ":transfer").mapNotNull(rootProject::findProject).forEach { implementation(it) }
                implementation(project.dependencies.platform(libs.junit5.bom))
                implementation(libs.junit5.jupiter)
                runtimeOnly(libs.junit5.launcher)
            }
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    // BuiltInReplayTest (FBBD-237) checks, or with -PwriteBuiltInReplay=true writes, the app's built-in replay.
    val demo = file("../app/src/main/assets/demo")
    systemProperty("builtInReplayDir", demo.path)
    providers.gradleProperty("writeBuiltInReplay").orNull?.let { systemProperty("writeBuiltInReplay", it) }
    inputs.dir(demo).optional().withPropertyName("builtInReplay")
}

// CI runs `./gradlew test`; let that include this module's JVM tests.
tasks.register("test") {
    dependsOn("jvmTest")
}
