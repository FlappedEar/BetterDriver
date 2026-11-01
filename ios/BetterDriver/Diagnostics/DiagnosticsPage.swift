import SwiftUI

/// Settings › Diagnostics, as on Android (DebugLogRows.kt, FBBD-146, FBBD-183): the app's one log,
/// kept across sessions and restarts. "Share log" sends it as one zip through the share sheet; after
/// a drive that is all Arek does, every hardware check is worked out from it. "Clear log" is the only
/// thing that deletes it, after a confirmation that shows its size. Sizes, the zip and the delete
/// run off the main thread.
@MainActor
struct DiagnosticsPage: View {
    @State private var busy = false
    @State private var confirmClear = false
    @State private var problem: String?
    @State private var size: Int64?
    @State private var changes = 0

    var body: some View {
        ShellPage(title: "Diagnostics") {
            ShellCard {
                ListRow("Share log", value: busy ? "Preparing…" : DiagnosticsPage.sizeLabel(size) ?? "…", divider: false) {
                    share()
                }
                .accessibilityIdentifier("diagnostics.share")
                ListRow("Clear log") { if !busy { confirmClear = true } }
                    .accessibilityIdentifier("diagnostics.clear")
                // The camera test (FBBD-278, UI-150 A on Android; iOS FBBD-371): its results go into this log.
                NavigationLink { CameraTestPage() } label: {
                    ListRow("Camera test", supporting: "1080p · 60, with and without MP4", chevron: true)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("diagnostics.cameraTest")
            }
            DimText("Kept until you clear it. After a drive, share it once: the app's checks are worked out from it.")
                .padding(.horizontal, 4)
            if let problem { DimText(problem).padding(.horizontal, 4) }
        }
        .task(id: changes) { await measure() }
        .alert("Clear the log?", isPresented: $confirmClear) {
            Button("Cancel", role: .cancel) {}
            Button("Clear", role: .destructive) {
                Task {
                    await Task.detached { AppLog.shared.clear() }.value
                    changes += 1
                }
            }
        } message: {
            Text("This deletes \(size.flatMap { $0 > 0 ? DiagnosticsPage.sizeLabel($0) : nil } ?? "the log") of log, with the records of every drive in it.")
        }
    }

    /// "1.3 MB", or "16 KB" under a megabyte: the iPhone's log is often a few KB, which "0.0 MB"
    /// hid (FBBD-383).
    nonisolated static func sizeLabel(_ bytes: Int64?) -> String? {
        guard let bytes else { return nil }
        if bytes <= 0 { return "Empty" }
        if bytes < 1_000_000 { return "\(max(1, (bytes + 500) / 1_000)) KB" }
        return String(format: "%.1f MB", Double(bytes) / 1_000_000)
    }

    private func measure() async {
        size = await Task.detached { AppLog.shared.bytes() }.value
    }

    private func share() {
        guard !busy else { return }
        busy = true
        problem = nil
        Task {
            let result = await Task.detached { Result(catching: { try LogShare.prepare() }) }.value
            busy = false
            switch result {
            case .success(let zip):
                LogShare.share(zip)
            case .failure(let error):
                AppLog.w("DebugLogs", "could not prepare the log: \(error.localizedDescription)")
                problem = "Could not prepare the log: \(error.localizedDescription)"
            }
            changes += 1
        }
    }
}
