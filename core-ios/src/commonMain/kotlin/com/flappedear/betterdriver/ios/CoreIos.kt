package com.flappedear.betterdriver.ios

/**
 * The umbrella framework's own entry. Kotlin/Native links a framework only for a module with
 * sources (without one the link tasks are NO-SOURCE and no XCFramework is written), so this
 * module keeps one small declaration; the app uses the exported modules' API.
 */
object CoreIos {
    /** Name of the framework the app imports. */
    const val FRAMEWORK = "BetterDriverCore"
}
