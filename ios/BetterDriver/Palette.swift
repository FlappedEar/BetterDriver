import SwiftUI

/// The Android app's dark colours (core-ui Theme.kt DarkScheme): black background for OLED.
enum Palette {
    static let accent = Color(hex: 0xFCB203)
    static let onAccent = Color(hex: 0x1A1200)
    static let background = Color.black
    static let surface = Color(hex: 0x151B20)
    static let surfaceVariant = Color(hex: 0x1C242A)
    static let onBackground = Color(hex: 0xEEF2F4)
    static let onSurfaceVariant = Color(hex: 0x8C9AA5)
    static let outline = Color(hex: 0x26313A)
    /// The best lap, purple as on Android (TrackColors.best).
    static let best = Color(hex: 0xC58BFF)
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}
