import SwiftUI

/// The Android app's dark colours (core-ui Theme.kt DarkScheme), the Health-like look (UI-307 A,
/// FBBD-421): true black for OLED, cards in Apple's grouped grey #1C1C1E, grey text #8E8E93,
/// separators #38383A, amber the only accent.
enum Palette {
    static let accent = Color(hex: 0xFFC044)
    static let onAccent = Color(hex: 0x211B0F)
    static let background = Color.black
    /// A card on black.
    static let surface = Color(hex: 0x1C1C1E)
    /// A control or a selected row on a card.
    static let surfaceVariant = Color(hex: 0x2C2C2E)
    static let onBackground = Color.white
    static let onSurfaceVariant = Color(hex: 0x8E8E93)
    static let outline = Color(hex: 0x38383A)
    /// The earlier session's bar in a comparison: Apple's grey.
    static let beforeBar = Color(hex: 0x636366)
    /// The best lap, purple as on Android (TrackColors.best).
    static let best = Color(hex: 0xC58BFF)
    /// The other lap of a comparison (TrackColors.cold).
    static let cold = Color(hex: 0x51A7FF)
    /// The time delta line (TrackColors.warning).
    static let warning = Color(hex: 0xFFB020)
    static let gain = Color(hex: 0x22D37A)
    static let loss = Color(hex: 0xFF6863)
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

    /// A colour the shared core works out as ARGB (Android's Color(Int)); its alpha is left out.
    init(argb: Int32) {
        self.init(hex: UInt32(bitPattern: argb) & 0xFFFFFF)
    }
}
