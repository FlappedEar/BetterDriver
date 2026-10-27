// What Arek has set up in the app (FBBD-83): his equipment (GNSS receivers, cameras), his car
// profiles and the choice for the next session, stored as one JSON file. Nothing about his hardware
// or cars is assumed at build time; the app shows only what is set up here. Kotlin Multiplatform:
// the model and its JSON are common Kotlin in src/commonMain and also build for iOS, where Settings
// edits the same file; the java.io file store stays JVM code in src/main.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain {
            kotlin.srcDir("src/main/kotlin")
        }
        jvmTest {
            kotlin.srcDir("src/test/kotlin")
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
}

// CI runs `./gradlew test`; let that include this module's JVM tests.
tasks.register("test") {
    dependsOn("jvmTest")
}
