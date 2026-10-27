import SwiftUI

/// What the Android app has and this app does not do yet: the screen or button is here, in Android's
/// place, and says so instead of being missing (Arek, 2026-10-27). Each is replaced by the real one
/// as it is ported.
struct NotImplementedPage: View {
    let title: String

    var body: some View {
        ShellPage(title: title) {
            ShellCard {
                VStack(alignment: .leading, spacing: 6) {
                    Text(NotImplemented.title)
                        .font(.headline)
                        .foregroundStyle(Palette.onBackground)
                    DimText(NotImplemented.detail(title))
                }
                .padding(.vertical, 14)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .accessibilityIdentifier("notImplemented")
    }
}

enum NotImplemented {
    static let title = "Not implemented yet"

    static func detail(_ feature: String) -> String {
        "\(feature) works on Android and comes to iPhone and iPad in a later build."
    }
}

extension View {
    /// Shows "Not implemented yet" for [feature] while it is set; a button sets it.
    func notImplementedAlert(_ feature: Binding<String?>) -> some View {
        alert(
            NotImplemented.title,
            isPresented: Binding(get: { feature.wrappedValue != nil }, set: { if !$0 { feature.wrappedValue = nil } })
        ) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(feature.wrappedValue.map(NotImplemented.detail) ?? "")
        }
    }
}
