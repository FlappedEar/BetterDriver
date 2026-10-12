package com.flappedear.betterdriver.uikill

/**
 * A phase of the UI-process kill test (FBBD-72). Each phase runs alone, in order, from
 * .github/scripts/ui-kill.sh with the UI process killed between them, so the Gradle test run
 * leaves these out (notAnnotation in app/build.gradle.kts).
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class UiKillPhase
