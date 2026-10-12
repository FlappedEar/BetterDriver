package com.flappedear.betterdriver.uikill

/**
 * A phase of a scripted emulator test: the UI-process kill test (FBBD-72, ui-kill.sh), the fault
 * injection test (faults.sh) and the permissions test (permissions.sh). Each phase runs alone, in
 * order, from its script in .github/scripts with faults injected between them, so the Gradle test run
 * leaves these out (notAnnotation in app/build.gradle.kts).
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class UiKillPhase
