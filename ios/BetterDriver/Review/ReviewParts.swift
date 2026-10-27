import SwiftUI
import BetterDriverCore

/// Android's analysis card: the surface colour, rounded, its rows 8 apart.
struct Card<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: 16))
    }
}

/// Android's TrackLabel: a small caps heading (the recording screens have their own).
struct SectionTitle: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text.uppercased())
            .font(.caption2.weight(.semibold))
            .tracking(0.8)
            .foregroundStyle(Palette.onSurfaceVariant)
    }
}

/// Android's Dim: secondary text, wrapping (the review and the Tracks screens).
struct Dim: View {
    let text: String
    var color: Color = Palette.onSurfaceVariant
    init(_ text: String, color: Color = Palette.onSurfaceVariant) {
        self.text = text
        self.color = color
    }

    var body: some View {
        Text(text).font(.subheadline).foregroundStyle(color)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// A tab's scrolling column of cards, as wide as Android's on a tablet.
struct CardColumn<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        ScrollView {
            VStack(spacing: 12) { content }
                .padding(20)
                .frame(maxWidth: 900)
                .frame(maxWidth: .infinity)
        }
    }
}

/// The shared core's text helpers, so numbers read as on Android.
enum CoreText {
    static let missing = "–"
    static let legend = "≈ calculated or inferred, not measured"

    static func seconds(_ v: Double) -> String { ChartMathKt.seconds(value: v) }
    static func fixed(_ v: Double, _ decimals: Int) -> String { NumberFormat_iosKt.fixed(value: v, decimals: Int32(decimals)) }
    static func marked(_ text: String, _ provenance: Provenance?) -> String { ProvenanceMarkKt.marked(text: text, provenance: provenance) }
}
