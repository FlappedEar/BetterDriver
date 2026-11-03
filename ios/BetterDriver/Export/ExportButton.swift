import BetterDriverCore
import Combine
import SwiftUI
import UIKit

/// Export from a session (FBBD-333), as the export menu of Android's session card: VBO + video for
/// Circuit Tools, the VBO and its video files (FBBD-25), when the session has video, the VBO alone, CSV and
/// GPX, and Video with data (FBBD-152) when it has video. The files are written in the background,
/// then the share sheet opens, as Share log does (FBBD-443): AirDrop, Mail, Save to Files or another
/// app in one step. VBO + video shares the VBO and each video file, so Save to Files keeps them together.
struct ExportButton: View {
    let sessionId: String
    /// Opens Video with data (FBBD-152). The session's page shows it, outside the toolbar, so the
    /// sheet and its player stay up while the phone turns to landscape for the video.
    var dataVideo: () -> Void = {}

    @State private var hasVideo = false
    /// Counts the session changes, so [hasVideo] is read again after each.
    @State private var videoCheck = 0
    @State private var running = false
    @State private var failure: String?

    var body: some View {
        Menu {
            if hasVideo {
                Button { export(.vboWithVideo) } label: {
                    Label("VBO + video", systemImage: "film")
                }
            }
            Button { export(.vbo) } label: {
                Label("VBO", systemImage: "doc")
            }
            // The rest of Android's export menu.
            Button { export(.csv) } label: { Label("CSV", systemImage: "tablecells") }
            Button { export(.gpx) } label: { Label("GPX", systemImage: "point.topleft.down.to.point.bottomright.curvepath") }
            if hasVideo {
                Button { dataVideo() } label: { Label("Video with data", systemImage: "film.stack") }
            }
        } label: {
            if running {
                ProgressView().tint(Palette.accent)
            } else {
                Label("Export", systemImage: "square.and.arrow.up")
            }
        }
        .disabled(running)
        .accessibilityIdentifier("exportMenu")
        .task(id: "\(sessionId)#\(videoCheck)") {
            let id = sessionId
            // Not while the phone camera is still finishing its MP4 (audit 30 Oct BD-02); read again
            // when it is done, as the sessions change then.
            hasVideo = await Task.detached {
                !PhoneVideoFinishing.shared.busy(sessionId: id) && Exports.make().hasVideo(id: id)
            }.value
        }
        .onReceive(NotificationCenter.default.publisher(for: .sessionsChanged)) { _ in videoCheck += 1 }
        .alert(failure ?? "", isPresented: Binding(get: { failure != nil }, set: { if !$0 { failure = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }

    private enum Format {
        case vboWithVideo, vbo, csv, gpx

        /// The shared core's format.
        var core: ExportFormat {
            switch self {
            case .vboWithVideo: return .vboVideo
            case .vbo: return .vbo
            case .csv: return .csv
            case .gpx: return .gpx
            }
        }
    }

    private func export(_ format: Format) {
        let id = sessionId
        let core = format.core
        running = true
        Task {
            let result: Result<[URL], Error> = await Task.detached {
                Result {
                    let exports = Exports.make()
                    // Written and listed by the shared core, as on Android (ExportShare).
                    let share = try exports.share(id: id, format: core)
                    AppLog.i(Exports.tag, share.logLine(bytes: exports.bytes(share: share)))
                    return share.files.map { URL(fileURLWithPath: $0) }
                }
            }.value
            running = false
            switch result {
            case .success(let urls): LogShare.share(urls)
            case .failure(let error):
                AppLog.w(Exports.tag, "export \(id) failed: \(error.localizedDescription)")
                failure = "Export failed: \(error.localizedDescription)"
            }
        }
    }
}

/// A finished export, waiting for Files.
struct ExportedFile: Identifiable {
    let url: URL
    var id: String { url.path }
}

/// The exporter over the app's folders; exports are made in the temporary folder, the share sheet sends them on.
enum Exports {
    /// The log's tag for exports, Android's: the same line under the same tag on both.
    static let tag = "Sessions"

    static func make() -> SessionExport {
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("export", isDirectory: true)
        return SessionExport(sessionsRoot: AppFolders.sessions.path, tracksRoot: AppFolders.tracks.path, outRoot: out.path)
    }
}

/// Files' "Save to" for an exported file or folder, as Android's document picker.
struct SaveToFiles: UIViewControllerRepresentable {
    let url: URL
    let onDone: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onDone: onDone) }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onDone: () -> Void

        init(onDone: @escaping () -> Void) {
            self.onDone = onDone
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) { onDone() }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { onDone() }
    }
}
