import SwiftUI
import UIKit

// The card style outside the car (UI-307 A, UI-308 A, FBBD-421), as Android's core-ui HealthCards.kt:
// Apple Health's layout on true black, in amber only. Big bold page titles, section titles ("Pinned",
// "Highlights"), cards with a small amber heading (icon and name) and the time on the right, one big
// value with a small unit and a tiny chart beside it. SF here, where Android draws Inter to look like
// it. The track screen does not use these. Named Health… so no type clashes with another file's.

enum HealthStyle {
    static let cornerRadius: CGFloat = 12
    static let chartWidth: CGFloat = 96
    static let chartHeight: CGFloat = 40
    static let detailFont = Font.system(size: 14)

    /// The tab bar and navigation bar of the Health-like look (UI-307 A), as Android's bottom bar: the
    /// card grey slightly see-through over a thin line, the selected item in amber without a pill; large
    /// bold page titles on black.
    static func applyBars() {
        let tabs = UITabBarAppearance()
        tabs.configureWithOpaqueBackground()
        tabs.backgroundColor = UIColor(red: 0x1C / 255, green: 0x1C / 255, blue: 0x1E / 255, alpha: 0.94)
        tabs.shadowColor = UIColor(red: 0x38 / 255, green: 0x38 / 255, blue: 0x3A / 255, alpha: 1)
        let grey = UIColor(red: 0x8E / 255, green: 0x8E / 255, blue: 0x93 / 255, alpha: 1)
        let amber = UIColor(red: 1, green: 0xC0 / 255, blue: 0x44 / 255, alpha: 1)
        for layout in [tabs.stackedLayoutAppearance, tabs.inlineLayoutAppearance, tabs.compactInlineLayoutAppearance] {
            layout.normal.iconColor = grey
            layout.normal.titleTextAttributes = [.foregroundColor: grey]
            layout.selected.iconColor = amber
            layout.selected.titleTextAttributes = [.foregroundColor: amber]
        }
        UITabBar.appearance().standardAppearance = tabs
        UITabBar.appearance().scrollEdgeAppearance = tabs

        let nav = UINavigationBarAppearance()
        nav.configureWithOpaqueBackground()
        nav.backgroundColor = .black
        nav.shadowColor = .clear
        nav.largeTitleTextAttributes = [
            .foregroundColor: UIColor.white,
            .font: UIFont.systemFont(ofSize: 32, weight: .bold),
        ]
        nav.titleTextAttributes = [
            .foregroundColor: UIColor.white,
            .font: UIFont.systemFont(ofSize: 17, weight: .semibold),
        ]
        UINavigationBar.appearance().standardAppearance = nav
        UINavigationBar.appearance().scrollEdgeAppearance = nav
        UINavigationBar.appearance().compactAppearance = nav
    }
}

/// "Pinned", "Highlights": the title above a group of cards.
struct HealthSectionTitle: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.system(size: 20, weight: .bold))
            .foregroundStyle(Palette.onBackground)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityAddTraits(.isHeader)
    }
}

/// A card's icon, drawn as a line in the heading's colour (Android's HealthIcon).
enum HealthIcon: String {
    /// A stopwatch: laps and lap times.
    case laps = "stopwatch"
    /// A line into a corner: the coach's next focus.
    case coach = "point.topleft.down.curvedto.point.bottomright.up"
    /// A heart: heart rate.
    case heart = "heart"
    /// A car: the car's data.
    case car = "car"
    /// A phone: the devices a session uses.
    case device = "iphone"
}

/// One card (UI-307 A): a heading with [icon] and [title] in amber on the left, [trailing] (a time, a
/// lap) in grey with › on the right when the card opens something ([opens]), then [content]. Without
/// a [title] the card has no heading. A card that opens something is wrapped in a Button by its caller.
struct HealthCard<Content: View>: View {
    var title: String?
    var icon: HealthIcon?
    var trailing: String?
    var opens = false
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let title {
                HStack(spacing: 6) {
                    if let icon {
                        Image(systemName: icon.rawValue)
                            .font(.system(size: 15, weight: .semibold))
                            .frame(width: 18, height: 18)
                    }
                    Text(title)
                        .font(.system(size: 15, weight: .semibold))
                        .lineLimit(1)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    let side = [trailing, opens ? "›" : nil].compactMap { $0 }.joined(separator: " ")
                    if !side.isEmpty {
                        Text(side).font(HealthStyle.detailFont).foregroundStyle(Palette.onSurfaceVariant).lineLimit(1)
                    }
                }
                .foregroundStyle(Palette.accent)
            }
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(Palette.surface, in: RoundedRectangle(cornerRadius: HealthStyle.cornerRadius))
        .contentShape(RoundedRectangle(cornerRadius: HealthStyle.cornerRadius))
    }
}

/// The card's big value with its small grey [unit]: "129 bpm avg", "1:49.91". Wraps rather than clips.
struct HealthValue: View {
    let value: String
    var unit: String?
    var color: Color = Palette.onBackground
    var size: CGFloat = 28

    var body: some View {
        (Text(value).foregroundColor(color)
            + Text(unit.map { " \($0)" } ?? "").font(.system(size: 14, weight: .medium)).foregroundColor(Palette.onSurfaceVariant))
            .font(.system(size: size, weight: .bold).monospacedDigit())
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// A grey line under a card's value.
struct HealthDetail: View {
    let text: String
    var color: Color = Palette.onSurfaceVariant
    init(_ text: String, color: Color = Palette.onSurfaceVariant) {
        self.text = text
        self.color = color
    }

    var body: some View {
        Text(text).font(HealthStyle.detailFont).foregroundStyle(color).fixedSize(horizontal: false, vertical: true)
    }
}

/// The card's body: [text] on the left, a tiny [chart] at the bottom right.
struct HealthValueRow<Words: View, Chart: View>: View {
    @ViewBuilder let text: Words
    @ViewBuilder let chart: Chart

    var body: some View {
        HStack(alignment: .bottom, spacing: 8) {
            VStack(alignment: .leading, spacing: 2) { text }
                .frame(maxWidth: .infinity, alignment: .leading)
            chart
        }
    }
}

/// A tiny bar chart of times (UI-307 A): one bar per value, the shortest time tallest and in [best]
/// (purple, the best lap's colour), the others in [color] at half strength.
struct HealthMiniBars: View {
    let values: [Double]
    var color: Color = Palette.accent
    var best: Color = Palette.best

    var body: some View {
        Canvas { ctx, size in
            guard let lo = values.min(), let hi = values.max() else { return }
            let slot = size.width / CGFloat(values.count)
            let gap = slot * 0.25
            for (i, v) in values.enumerated() {
                let share = hi > lo ? 1 - (v - lo) / (hi - lo) : 1
                let h = size.height * 0.1 + CGFloat(share) * size.height * 0.9
                let rect = CGRect(x: CGFloat(i) * slot + gap / 2, y: size.height - h, width: slot - gap, height: h)
                ctx.fill(Path(roundedRect: rect, cornerRadius: 2), with: .color(v == lo ? best : color.opacity(0.55)))
            }
        }
        .frame(width: HealthStyle.chartWidth, height: HealthStyle.chartHeight)
        .accessibilityHidden(true)
    }
}

/// A tiny line chart of [values] in [color], higher values higher, with a dot at the last one.
struct HealthMiniLine: View {
    let values: [Double]
    var color: Color = Palette.accent

    var body: some View {
        Canvas { ctx, size in
            guard values.count >= 2, let lo = values.min(), let hi = values.max() else { return }
            let pad: CGFloat = 4
            func at(_ i: Int) -> CGPoint {
                let share = hi > lo ? (values[i] - lo) / (hi - lo) : 0.5
                return CGPoint(
                    x: CGFloat(i) * size.width / CGFloat(values.count - 1),
                    y: size.height - pad - CGFloat(share) * (size.height - 2 * pad)
                )
            }
            var line = Path()
            line.move(to: at(0))
            for i in 1..<values.count { line.addLine(to: at(i)) }
            ctx.stroke(line, with: .color(color), style: StrokeStyle(lineWidth: 2.2, lineCap: .round, lineJoin: .round))
            let last = at(values.count - 1)
            ctx.fill(Path(ellipseIn: CGRect(x: last.x - 3, y: last.y - 3, width: 6, height: 6)), with: .color(color))
        }
        .frame(width: HealthStyle.chartWidth, height: HealthStyle.chartHeight)
        .accessibilityHidden(true)
    }
}

/// One bar of a comparison (UI-307 A): a session's [label], its [value] and a bar [share] of the longest.
struct HealthCompareBar {
    let label: String
    let value: String
    let share: Double
    let color: Color
}

/// Two or more sessions compared by bars, as the Highlights card sets this session against the one before.
struct HealthCompareBars: View {
    let bars: [HealthCompareBar]

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(Array(bars.enumerated()), id: \.offset) { _, bar in
                VStack(alignment: .leading, spacing: 2) {
                    HealthDetail(bar.label)
                    HStack(spacing: 8) {
                        Text(bar.value)
                            .font(.system(size: 15, weight: .bold).monospacedDigit())
                            .foregroundStyle(Palette.onBackground)
                            .lineLimit(1)
                            .frame(width: 76, alignment: .leading)
                        GeometryReader { geo in
                            RoundedRectangle(cornerRadius: 4)
                                .fill(bar.color)
                                .frame(width: geo.size.width * 0.85 * min(1, max(0, bar.share)), height: 8)
                                .frame(maxHeight: .infinity, alignment: .center)
                        }
                        .frame(height: 8)
                    }
                }
            }
        }
    }
}
