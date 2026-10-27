import SwiftUI

// The Android app's shell parts (ShellParts.kt): plain lists on the black background without a card
// behind them (UI-143 B), thin dividers between rows, captions in small capitals, + Add rows in amber.

/// One group on a settings page: rows on the background, 8 points apart.
struct ShellCard<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .padding(.horizontal, 4)
            .padding(.vertical, 6)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// One row: a label with an optional supporting line, an optional value on the right and a trailing
/// control; rows after the first get a divider above them. With [action] the whole row is tappable.
struct ListRow<Trailing: View>: View {
    let label: String
    var value: String?
    var divider = true
    var supporting: String?
    var chevron = false
    var action: (() -> Void)?
    @ViewBuilder var trailing: Trailing

    var body: some View {
        VStack(spacing: 0) {
            if divider { Rectangle().fill(Palette.outline).frame(height: 1) }
            if let action {
                Button(action: action) { row }.buttonStyle(.plain)
            } else {
                row
            }
        }
    }

    private var row: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(label).foregroundStyle(Palette.onBackground)
                if let supporting { DimText(supporting) }
            }
            Spacer(minLength: 8)
            if let value {
                Text(value).foregroundStyle(Palette.onSurfaceVariant).multilineTextAlignment(.trailing)
            }
            trailing
            if chevron {
                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Palette.onSurfaceVariant)
            }
        }
        .padding(.vertical, 12)
        .contentShape(Rectangle())
    }
}

extension ListRow where Trailing == EmptyView {
    init(
        _ label: String, value: String? = nil, divider: Bool = true, supporting: String? = nil,
        chevron: Bool = false, action: (() -> Void)? = nil
    ) {
        self.init(
            label: label, value: value, divider: divider, supporting: supporting, chevron: chevron,
            action: action, trailing: { EmptyView() }
        )
    }
}

/// Secondary text, in the dim colour.
struct DimText: View {
    let text: String
    var color: Color = Palette.onSurfaceVariant

    init(_ text: String, color: Color = Palette.onSurfaceVariant) {
        self.text = text
        self.color = color
    }

    var body: some View {
        Text(text).font(.subheadline).foregroundStyle(color).fixedSize(horizontal: false, vertical: true)
    }
}

/// Small uppercase caption above a group.
struct ShellLabel: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text.uppercased())
            .font(.caption2.weight(.semibold))
            .tracking(0.9)
            .foregroundStyle(Palette.onSurfaceVariant)
            .padding(.leading, 4)
            .padding(.top, 4)
    }
}

/// "+ Add …" as the last row of a list, in the accent colour.
struct AddRow: View {
    let text: String
    var divider = true
    let action: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            if divider { Rectangle().fill(Palette.outline).frame(height: 1) }
            Button(action: action) {
                Text("+ \(text)")
                    .fontWeight(.semibold)
                    .foregroundStyle(Palette.accent)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 12)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }
}

/// Green when ready, grey when off, as Android's StatusDot.
struct StatusDot: View {
    let ready: Bool

    var body: some View {
        Circle().fill(ready ? TrackColors.gain : Palette.outline).frame(width: 10, height: 10)
    }
}

/// A settings page: a scrolling column of groups, 12 points apart, on black, titled as on Android.
struct ShellPage<Content: View>: View {
    let title: String
    /// A tab's own page has the large title; a page opened from it the small one.
    var large = false
    @ViewBuilder let content: Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) { content }
                .padding(.horizontal, 16)
                .padding(.vertical, 4)
        }
        .background(Palette.background)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(large ? .large : .inline)
    }
}

/// A full-width button in the loss colour, as Android's Remove.
struct RemoveButton: View {
    let text: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(text)
                .foregroundStyle(TrackColors.loss)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .overlay(Capsule().stroke(Palette.outline, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

/// A full-width tonal button, as Android's FilledTonalButton.
struct ShellTonalButton: View {
    let text: String
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(text)
                .fontWeight(.medium)
                .foregroundStyle(enabled ? Palette.onBackground : Palette.onSurfaceVariant)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(Palette.surfaceVariant, in: Capsule())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}
