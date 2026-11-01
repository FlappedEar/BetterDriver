import BetterDriverCore
import SwiftUI

/// The editor's working copy for the views: every change goes through [act], which republishes it.
@MainActor
final class EditorModel: ObservableObject {
    let editor: TrackEditor
    @Published private(set) var revision = 0
    /// What saving does to the sessions here; worked out off the main thread after each change.
    @Published private(set) var retimeNote = ""
    @Published private(set) var saving = false
    private var noteTask: Task<Void, Never>?

    init(_ editor: TrackEditor) {
        self.editor = editor
        updateNote()
    }

    func act(_ change: (TrackEditor) -> Void) {
        change(editor)
        revision += 1
        updateNote()
    }

    private func updateNote() {
        noteTask?.cancel()
        let editor = editor
        noteTask = Task {
            // Dragging a line end changes the track many times a second.
            try? await Task.sleep(nanoseconds: 150_000_000)
            if Task.isCancelled { return }
            let note = await Task.detached { editor.retimeNote() }.value
            if !Task.isCancelled { retimeNote = note }
        }
    }

    /// Starts correction [edit] of the corners (FBBD-366) and stores it; nothing when it does not apply.
    func correct(_ edit: SegmentEdit?) {
        guard let edit else { return }
        var started: SegmentEdit?
        act { started = $0.corners.startCorrection(edit: edit) }
        store(started)
    }

    /// Stores correction [edit], already started, made to the corners shown; then shows them as stored.
    func store(_ edit: SegmentEdit?) {
        guard let edit, let expected = editor.corners.identity else { return }
        let id = editor.track.id
        Task.detached {
            let result = TracksModel.library.correct(id: id, expected: expected, edit: edit)
            await MainActor.run {
                self.act { $0.corners.correctionStored(stored: result.track?.identity, applied: result.applied) }
                NotificationCenter.default.post(name: .tracksChanged, object: nil)
            }
        }
    }

    /// Stores the track; [done] gets false when it could not be written.
    func save(done: @escaping (Bool) -> Void) {
        let sent = editor.track
        saving = true
        Task.detached {
            let stored = try? TracksModel.library.save(track: sent)
            await MainActor.run {
                self.saving = false
                if let stored {
                    self.act { $0.saved(sent: sent, stored: stored) }
                    NotificationCenter.default.post(name: .tracksChanged, object: nil)
                }
                done(stored != nil)
            }
        }
    }
}

/// Opens track [id] in the editor once its sessions are read (Android's TrackEditorRoute).
struct TrackEditorScreen: View {
    let trackId: String
    /// A track made from a session (FBBD-367), not saved yet; null to edit saved track [trackId].
    var proposal: TrackProposal? = nil
    let onClose: () -> Void
    @State private var model: EditorModel?
    @State private var missing = false

    var body: some View {
        Group {
            if let model {
                TrackEditorView(model: model, onClose: onClose)
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity).background(Palette.background)
            }
        }
        .task {
            let id = trackId
            let proposal = proposal
            let editor = await Task.detached { () -> TrackEditor? in
                if let proposal { return ReviewBridge.library.proposalEditor(proposal: proposal) }
                guard let track = TracksModel.library.get(id: id) else { return nil }
                return TrackEditor(initial: track, sessions: TracksModel.library.editorSessions(track: track), stored: track)
            }.value
            if let editor { model = EditorModel(editor) } else { onClose() }
        }
    }
}

/// Track editor (FBBD-56 on Android, FBBD-336): phone, map on top and the lines below (UI-23 A);
/// iPad, the map with a side panel (UI-25 A). Lines are placed from the recorded lap by default
/// (UI-24 A), on Apple's satellite photo (UI-26 A) or Apple's map.
struct TrackEditorView: View {
    @ObservedObject var model: EditorModel
    let onClose: () -> Void
    @Environment(\.horizontalSizeClass) private var width
    @State private var background = EditorBackground.satellite
    @State private var leaving = false
    @State private var deleting = false
    @State private var naming = false
    @State private var nameText = ""
    @State private var failed: String?

    private var editor: TrackEditor { model.editor }

    var body: some View {
        VStack(spacing: 0) {
            if width == .regular {
                HStack(spacing: 0) {
                    mapPane
                    VStack(spacing: 0) {
                        bar
                        ScrollView { panel.padding(.horizontal, 16) }
                    }
                    .frame(width: 380)
                }
            } else {
                bar.padding(.horizontal, 4)
                mapPane
                ScrollView { panel.padding(.horizontal, 16).padding(.vertical, 8) }
                    .frame(maxHeight: 320)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .background(Palette.background)
        .foregroundStyle(Palette.onBackground)
        .navigationBarHidden(true)
        .confirmationDialog("Discard changes?", isPresented: $leaving, titleVisibility: .visible) {
            Button("Discard", role: .destructive) { onClose() }
            Button("Keep editing", role: .cancel) {}
        } message: {
            Text("The lines you moved are not saved.")
        }
        .alert("Delete track?", isPresented: $deleting) {
            Button("Delete", role: .destructive) { delete() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("\(editor.track.displayName): its lines will be removed from this phone. Sessions recorded there stay. This can't be undone.")
        }
        .alert("Track name", isPresented: $naming) {
            TextField("Name", text: $nameText)
            Button("Rename") { model.act { $0.rename(name: nameText) } }
                .disabled(!TracksModel.library.validName(name: nameText))
            Button("Cancel", role: .cancel) {}
        }
        .alert(failed ?? "", isPresented: Binding(get: { failed != nil }, set: { if !$0 { failed = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }

    private func back() {
        if editor.selected != nil {
            model.act { $0.cancel() }
        } else if editor.corners.segmentId != nil {
            model.act { $0.corners.select(id: nil) }
        } else if editor.changed {
            leaving = true
        } else {
            onClose()
        }
    }

    private func delete() {
        let id = editor.track.id
        Task.detached {
            let gone = TracksModel.library.delete(id: id)
            await MainActor.run {
                if gone {
                    NotificationCenter.default.post(name: .tracksChanged, object: nil)
                    onClose()
                } else {
                    failed = "Delete failed"
                }
            }
        }
    }

    private var bar: some View {
        HStack(spacing: 8) {
            Button(action: back) {
                Image(systemName: "chevron.left").font(.title3).foregroundStyle(Palette.onSurfaceVariant)
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel("Back")
            .accessibilityIdentifier("editorBack")
            Text(editor.title)
                .font(.title3.weight(.semibold))
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
            if editor.selected != nil {
                Button("Done") { model.act { $0.done() } }
                    .buttonStyle(.borderedProminent)
                    .disabled(editor.selectedLine == nil)
            } else if editor.corners.segmentId != nil {
                Button("Done") { model.act { $0.corners.select(id: nil) } }
                    .buttonStyle(.borderedProminent)
                    .disabled(editor.corners.correcting)
                    .accessibilityIdentifier("segmentDone")
            } else {
                if editor.isStored {
                    Button("Delete") { deleting = true }.foregroundStyle(TrackColors.loss)
                    // A saved track as one file to the other device (FBBD-80, UI-56 B, FBBD-372).
                    Button("Share") { failed = TrackImportModel.share(editor.track.id) }
                        .disabled(editor.changed)
                        .accessibilityIdentifier("shareTrack")
                }
                Button("Save") {
                    model.save { ok in if !ok { failed = "The track could not be saved." } }
                }
                .buttonStyle(.borderedProminent)
                .disabled(!editor.changed || model.saving)
                .accessibilityIdentifier("saveTrack")
            }
        }
        .tint(Palette.accent)
        .frame(height: 56)
    }

    private var mapPane: some View {
        ZStack {
            EditorMap(model: model, background: background)
            VStack {
                if let hint = editor.hint {
                    Text(hint)
                        .font(.subheadline)
                        .foregroundStyle(Color(hex: 0xEEF2F4))
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .background(Color(hex: 0x0B0F12).opacity(0.8), in: RoundedRectangle(cornerRadius: 12))
                        .padding(12)
                }
                Spacer()
                // Apple's logo and Legal link are MapKit's own, bottom left.
                HStack(alignment: .bottom) {
                    Spacer()
                    Menu {
                        ForEach(EditorBackground.allCases) { b in
                            Button(b == background ? "✓ \(b.name)" : b.name) { background = b }
                        }
                    } label: {
                        Text("Map: \(background.name)")
                            .font(.system(size: 12))
                            .foregroundStyle(Color(hex: 0xEEF2F4))
                            .padding(.horizontal, 12).padding(.vertical, 8)
                            .background(Color.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 16))
                    }
                    .accessibilityIdentifier("mapPicker")
                    .padding(.trailing, 8).padding(.bottom, 8)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    @ViewBuilder private var panel: some View {
        VStack(alignment: .leading, spacing: 0) {
            if editor.selected != nil {
                placingControls
            } else if editor.corners.segmentId != nil && editor.corners.correctable {
                SegmentControls(model: model)
            } else {
                lineList
                CornerList(model: model)
            }
        }
    }

    private var placingControls: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                EditorChip(label: "From your lap", on: editor.placingFromLap, enabled: editor.path != nil) {
                    model.act { $0.pickFromLap(fromLap: true) }
                }
                EditorChip(label: "Two taps", on: !editor.placingFromLap) { model.act { $0.pickFromLap(fromLap: false) } }
            }
            if editor.path == nil {
                DimLine("No session here yet, so lines are placed with two taps.")
            }
            HStack(spacing: 16) {
                Button("Flip direction") { model.act { $0.flip() } }
                    .disabled(editor.selectedLine == nil)
                    .foregroundStyle(editor.selectedLine == nil ? Palette.onSurfaceVariant : Palette.accent)
                Button("Remove line") { model.act { $0.remove() } }
                    .foregroundStyle(TrackColors.loss)
            }
            .padding(.vertical, 4)
            DimLine("The arrow shows the way you drive across the line. Crossings the other way never count.")
        }
        .padding(.vertical, 8)
    }

    private var lineList: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                nameText = editor.track.name
                naming = true
            } label: {
                HStack(spacing: 12) {
                    Text("Name")
                    Text(editor.track.name).foregroundStyle(Palette.onSurfaceVariant).lineLimit(1)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text("Rename").font(.subheadline.weight(.medium)).foregroundStyle(Palette.accent)
                }
                .padding(.vertical, 12)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Divider().overlay(Palette.outline)
            HStack(spacing: 8) {
                Text("Kind").frame(maxWidth: .infinity, alignment: .leading)
                EditorChip(label: "Circuit", on: !editor.pointToPoint) { model.act { $0.setPointToPoint(on: false) } }
                EditorChip(label: "Point to point", on: editor.pointToPoint) { model.act { $0.setPointToPoint(on: true) } }
            }
            .padding(.vertical, 8)
            if let note = editor.kindNote { DimLine(note).padding(.bottom, 8) }
            Divider().overlay(Palette.outline)
            ForEach(editor.lines(), id: \.index) { line in
                Button { model.act { $0.select(index: line.index) } } label: {
                    HStack(spacing: 12) {
                        RoundedRectangle(cornerRadius: 2)
                            .fill(Color(line.pit ? EditorColors.pit : EditorColors.line))
                            .frame(width: 18, height: 4)
                        Text(line.label).frame(maxWidth: .infinity, alignment: .leading)
                        if line.placed {
                            Text("✓").fontWeight(.bold).foregroundStyle(TrackColors.gain)
                        } else {
                            DimLine("Not set")
                        }
                    }
                    .padding(.vertical, 12)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("line\(line.index)")
                Divider().overlay(Palette.outline)
            }
            Button { model.act { $0.addSector() } } label: {
                Text("+ Add sector line")
                    .fontWeight(.semibold)
                    .foregroundStyle(editor.canAddSector ? Palette.accent : Palette.onSurfaceVariant)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 12)
            }
            .buttonStyle(.plain)
            .disabled(!editor.canAddSector)
            DimLine(model.retimeNote).padding(.top, 6)
        }
    }
}

/// A filter chip, as Material's FilterChip on Android.
struct EditorChip: View {
    let label: String
    let on: Bool
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if on { Image(systemName: "checkmark").font(.caption.weight(.bold)) }
                Text(label).font(.subheadline.weight(.medium))
            }
            .padding(.horizontal, 12).padding(.vertical, 6)
            .foregroundStyle(on ? Palette.onAccent : (enabled ? Palette.onBackground : Palette.onSurfaceVariant))
            .background(on ? Palette.accent : Color.clear, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(on ? Color.clear : Palette.outline))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

/// Secondary text (Android's DimLine).
struct DimLine: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text).font(.subheadline).foregroundStyle(Palette.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
    }
}
