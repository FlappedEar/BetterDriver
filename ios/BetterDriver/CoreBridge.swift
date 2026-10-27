import BetterDriverCore

/// The one place the app talks to the Kotlin core, so Kotlin types (Int64 for Long, `shared`
/// for objects, `…Kt` classes for top-level functions) stay out of the views.
enum CoreBridge {
    /// The BetterDriver version the core was built with, the same as the Android app's.
    static var version: String { CoreInfo.shared.version }

    /// Lap time as m:ss.cc, for example 1:52.07 (core: formatLapTime).
    static func lapTime(millis: Int64) -> String { TimeFormatKt.formatLapTime(millis: millis) }

    /// Signed gap with two decimals, minus = gain, for example −0.34 (core: formatDelta).
    static func delta(seconds: Double) -> String { TimeFormatKt.formatDelta(seconds: seconds) }
}
