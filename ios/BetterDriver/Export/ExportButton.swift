import BetterDriverCore
import SwiftUI
import UIKit

/// Export from a session (FBBD-333), as the export menu of Android's session card: VBO + video for
/// Circuit Tools into a new folder (FBBD-25), when the session has video, and the VBO alone. The
/// files are written in the background, then Files asks where to save them.
struct ExportButton: View {
    let sessionId: String

    @State private var hasVideo = false
    @State private var running = false
    @State private var ready: ExportedFile?
    @State private var failure: String?
    @State private var notYet: String?

    var body: some View {
        Menu {
            if hasVideo {
                Button { export(withVideo: true) } label: {
                    Label("VBO + video", systemImage: "film")
                }
            }
            Button { export(withVideo: false) } label: {
                Label("VBO", systemImage: "doc")
            }
            // The rest of Android's export menu.
            Button { notYet = "CSV export" } label: { Label("CSV", systemImage: "tablecells") }
            Button { notYet = "GPX export" } label: { Label("GPX", systemImage: "point.topleft.down.to.point.bottomright.curvepath") }
            Button { notYet = "Video with data" } label: { Label("Video with data", systemImage: "film.stack") }
        } label: {
            if running {
                ProgressView().tint(Palette.accent)
            } else {
                Label("Export", systemImage: "square.and.arrow.up")
            }
        }
        .disabled(running)
        .accessibilityIdentifier("exportMenu")
        .task(id: sessionId) {
            let id = sessionId
            hasVideo = await Task.detached { Exports.make().hasVideo(id: id) }.value
        }
        .sheet(item: $ready) { file in
            SaveToFiles(url: file.url) { ready = nil }
        }
        .notImplementedAlert($notYet)
        .alert(failure ?? "", isPresented: Binding(get: { failure != nil }, set: { if !$0 { failure = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }

    private func export(withVideo: Bool) {
        let id = sessionId
        running = true
        Task {
            let result: Result<URL, Error> = await Task.detached {
                Result {
                    let exports = Exports.make()
                    let path = withVideo ? try exports.vboWithVideo(id: id) : try exports.vbo(id: id)
                    return URL(fileURLWithPath: path)
                }
            }.value
            running = false
            switch result {
            case .success(let url): ready = ExportedFile(url: url)
            case .failure(let error): failure = "Export failed: \(error.localizedDescription)"
            }
        }
    }
}

/// A finished export, waiting for Files.
struct ExportedFile: Identifiable {
    let url: URL
    var id: String { url.path }
}

/// The exporter over the app's folders; exports are made in the temporary folder, Files copies them out.
enum Exports {
    static func make() -> SessionExport {
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("export", isDirectory: true)
        return SessionExport(sessionsRoot: AppFolders.sessions.path, outRoot: out.path)
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
