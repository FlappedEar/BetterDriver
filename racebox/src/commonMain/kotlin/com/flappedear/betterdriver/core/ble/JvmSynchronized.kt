@file:OptIn(ExperimentalMultiplatform::class)

package com.flappedear.betterdriver.core.ble

/**
 * kotlin.jvm.Synchronized on the JVM, where Android delivers notifications on more than one binder
 * thread; nothing on iOS, where one CoreBluetooth queue feeds the decoder.
 */
@OptionalExpectation
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
expect annotation class JvmSynchronized()
