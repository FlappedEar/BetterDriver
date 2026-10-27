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
            let editor = await Task.detached { () -> TrackEditor? in
                guard let track = TracksModel.library.get(id: id) else { return nil }
                return TrackEditor(initial: track, sessions: TracksModel.library.editorSessions(track: track), stored: track)
            }.value
            if let editor { model = EditorModel(editor) } else { onClose() }
        }
    }
}

/// Track editor (FBBD-56 on Android, FBBD-336): phone, map on top and the lines below (UI-23 A);
/// iPad, the map with a side panel (UI-25 A). Lines are placed from the recorded lap by default
/// (UI-24 A), on a satellite background once a MapTiler key is entered (UI-26 A).
struct TrackEditorView: View {
    @ObservedObject var model: EditorModel
    let onClose: () -> Void
    @Environment(\.horizontalSizeClass) private var width
    @State private var sources = MapTiles.sources()
    @State private var picked: String?
    @State private var leaving = false
    @State private var deleting = false
    @State private var naming = false
    @State private var editingKey = false
    @State private var failed: String?

    private var editor: TrackEditor { model.editor }
    private var source: TileSource { sources.first { $0.id == picked } ?? sources[0] }

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
            NameField(initial: editor.track.name) { name in model.act { $0.rename(name: name) } }
        }
        .alert("MapTiler key", isPresented: $editingKey) {
            KeyField { key in
                MapTiles.mapTilerKey = key
                sources = MapTiles.sources()
                picked = nil
            }
        } message: {
            Text("Turns on MapTiler's satellite map. The key stays on this device.")
        }
        .alert(failed ?? "", isPresented: Binding(get: { failed != nil }, set: { if !$0 { failed = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }

    private func back() {
        if editor.selected != nil {
            model.act { $0.cancel() }
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
            Text(editor.title)
                .font(.title3.weight(.semibold))
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
            if editor.selected != nil {
                Button("Done") { model.act { $0.done() } }
                    .buttonStyle(.borderedProminent)
                    .disabled(editor.selectedLine == nil)
            } else {
                if editor.isStored {
                    Button("Delete") { deleting = true }.foregroundStyle(TrackColors.loss)
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
            EditorMap(model: model, source: source)
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
                HStack(alignment: .bottom) {
                    Text(source.attribution)
                        .font(.system(size: 10))
                        .foregroundStyle(Color(hex: 0xEEF2F4))
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.black.opacity(0.5))
                    Spacer()
                    Menu {
                        ForEach(sources, id: \.id) { s in
                            Button(s.id == source.id ? "✓ \(s.name)" : s.name) { picked = s.id }
                        }
                        Divider()
                        Button(MapTiles.mapTilerKey == nil ? "Add MapTiler key…" : "Change MapTiler key…") {
                            editingKey = true
                        }
                    } label: {
                        Text("Map: \(source.name)")
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
            } else {
                lineList
            }
        }
    }

    private var placingControls: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Chip(label: "From your lap", on: editor.method == .fromLap, enabled: editor.path != nil) {
                    model.act { $0.pick(m: .fromLap) }
                }
                Chip(label: "Two taps", on: editor.method == .twoTaps) { model.act { $0.pick(m: .twoTaps) } }
            }
            if editor.path == nil {
                Dim("No session here yet, so lines are placed with two taps.")
            }
            HStack(spacing: 16) {
                Button("Flip direction") { model.act { $0.flip() } }
                    .disabled(editor.selectedLine == nil)
                    .foregroundStyle(editor.selectedLine == nil ? Palette.onSurfaceVariant : Palette.accent)
                Button("Remove line") { model.act { $0.remove() } }
                    .foregroundStyle(TrackColors.loss)
            }
            .padding(.vertical, 4)
            Dim("The arrow shows the way you drive across the line. Crossings the other way never count.")
        }
        .padding(.vertical, 8)
    }

    private var lineList: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button { naming = true } label: {
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
                Chip(label: "Circuit", on: !editor.pointToPoint) { model.act { $0.setPointToPoint(on: false) } }
                Chip(label: "Point to point", on: editor.pointToPoint) { model.act { $0.setPointToPoint(on: true) } }
            }
            .padding(.vertical, 8)
            if let note = editor.kindNote { Dim(note).padding(.bottom, 8) }
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
                            Dim("Not set")
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
            Dim(model.retimeNote).padding(.top, 6)
        }
    }
}

/// A filter chip, as Material's FilterChip on Android.
private struct Chip: View {
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
struct Dim: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text).font(.subheadline).foregroundStyle(Palette.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// The text field and buttons of a name alert; [onDone] gets a valid name.
struct NameField: View {
    let initial: String
    var confirm = "Rename"
    let onDone: (String) -> Void
    @State private var name = ""

    var body: some View {
        TextField("Name", text: $name).onAppear { name = initial }
        Button(confirm) { onDone(name) }
            .disabled(!TracksModel.library.validName(name: name))
        Button("Cancel", role: .cancel) {}
    }
}

private struct KeyField: View {
    let onSave: (String?) -> Void
    @State private var key = MapTiles.mapTilerKey ?? ""

    var body: some View {
        TextField("Key", text: $key)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
        Button("Save") { onSave(key) }
        if MapTiles.mapTilerKey != nil {
            Button("Remove", role: .destructive) { onSave(nil) }
        }
        Button("Cancel", role: .cancel) {}
    }
}
