@file:OptIn(ExperimentalMultiplatform::class)

package com.flappedear.betterdriver.core

/**
 * kotlin.jvm.Synchronized on the JVM, where Android calls in from more than one thread (binder
 * threads, the recorder's listener thread and the UI); nothing on iOS, where each caller serialises
 * its own calls (one CoreBluetooth queue feeds the RaceBox decoder, a lock guards the live session).
 */
@OptionalExpectation
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
expect annotation class JvmSynchronized()
