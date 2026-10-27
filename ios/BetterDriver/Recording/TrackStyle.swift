import CoreText
import SwiftUI

/// The track screen's colours, as the Android app's TrackColorsGreenRed (core-ui TrackColors.kt).
enum TrackColors {
    static let background = Color.black
    static let surface = Color(hex: 0x12171B)
    static let line = Color(hex: 0x232B31)
    static let text = Color(hex: 0xF2F5F7)
    static let textDim = Color(hex: 0x96A5B0)
    static let gain = Color(hex: 0x22D37A)
    static let loss = Color(hex: 0xFF6863)
    static let onLoss = Color(hex: 0x100404)
    static let warning = Color(hex: 0xFFB020)
    static let onWarning = Color(hex: 0x141006)
    static let best = Color(hex: 0xC58BFF)
}

/// The Android app's track screen faces (core-ui Type.kt): Saira Semi Condensed for numbers, Barlow for
/// labels. The font files come from core-ui and are registered at launch, so no Info.plist list is needed.
enum TrackFonts {
    private static let files = [
        "saira_semi_condensed_medium", "saira_semi_condensed_semibold", "saira_semi_condensed_bold",
        "barlow_regular", "barlow_medium", "barlow_semibold",
    ]

    /// Registers the bundled fonts once; a font that fails falls back to the system font.
    static let register: Void = {
        for name in files {
            guard let url = Bundle.main.url(forResource: name, withExtension: "ttf") else { continue }
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        }
    }()

    /// Numbers: the delta, lap times and tile values (numberStyle).
    static func number(_ size: CGFloat, _ weight: Font.Weight = .bold) -> Font {
        _ = register
        let face = switch weight {
        case .medium: "SairaSemiCondensed-Medium"
        case .semibold: "SairaSemiCondensed-SemiBold"
        default: "SairaSemiCondensed-Bold"
        }
        return .custom(face, fixedSize: size)
    }

    /// Upper-case captions such as "DELTA VS BEST" (labelStyle), tracked 0.08 em by the caller.
    static func label(_ size: CGFloat) -> Font {
        _ = register
        return .custom("Barlow-SemiBold", fixedSize: size)
    }

    /// Running text on the track screen: status items and alerts (textStyle).
    static func text(_ size: CGFloat, _ weight: Font.Weight = .semibold) -> Font {
        _ = register
        return .custom(weight == .medium ? "Barlow-Medium" : "Barlow-SemiBold", fixedSize: size)
    }
}

/// An upper-case caption, as TrackLabel on Android.
struct TrackLabel: View {
    let text: String
    var size: CGFloat = 11
    var color: Color = TrackColors.textDim

    var body: some View {
        Text(text.uppercased())
            .font(TrackFonts.label(size))
            .tracking(size * 0.08)
            .foregroundStyle(color)
            .lineLimit(1)
    }
}
