import BetterDriverCore
import SwiftUI

// The track's corners and straights in the editor (FBBD-366, Android's FBBD-179 EditorCorners): the
// list under the lines and the controls of the one being corrected. EditorMap draws them on the map.

/// The corners and straights under the lines: each with its name and, once corrected, a mark; a tap
/// picks one to correct.
struct CornerList: View {
    @ObservedObject var model: EditorModel
    @State private var restoring = false

    private var corners: CornerCorrector { model.editor.corners }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(CornerCorrectorKt.CornersTitle).font(.subheadline.weight(.semibold))
                    .frame(maxWidth: .infinity, alignment: .leading)
                if corners.canRestore {
                    Button("Restore automatic") { restoring = true }
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(Palette.accent)
                        .disabled(corners.correcting)
                }
            }
            .padding(.top, 16).padding(.bottom, 4)
            DimLine(corners.listNote).padding(.bottom, 4)
            ForEach(Array(corners.rows().enumerated()), id: \.element.id) { i, row in
                if i > 0 { Divider().overlay(Palette.outline) }
                Button { model.act { $0.corners.select(id: row.id) } } label: {
                    HStack(spacing: 12) {
                        SegmentSwatch(straight: row.straight)
                        Text(row.name).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                        if let number = row.number { DimLine(number) }
                        if row.edited {
                            Text("Edited").font(.caption.weight(.medium)).foregroundStyle(Palette.accent)
                        }
                    }
                    .padding(.vertical, 10)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(!corners.correctable)
                .accessibilityIdentifier("segment-\(row.id)")
            }
            if let note = corners.correctionNote { DimLine(note).padding(.top, 6) }
        }
        .alert(CornerCorrectorKt.RestoreCornersTitle, isPresented: $restoring) {
            Button("Restore") { model.correct(model.editor.corners.restoreEdit()) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(CornerCorrectorKt.RestoreCornersText)
        }
    }
}

private struct SegmentSwatch: View {
    let straight: Bool

    var body: some View {
        RoundedRectangle(cornerRadius: 2)
            .fill(Color(straight ? EditorColors.straight : EditorColors.corner))
            .frame(width: 18, height: straight ? 2 : 4)
    }
}

/// The corner or straight being corrected: what it is (corner, chicane or straight), and rename,
/// split, merge with the next one, or remove. Its ends are dragged on the map.
struct SegmentControls: View {
    @ObservedObject var model: EditorModel
    @State private var naming = false
    @State private var name = ""

    private var corners: CornerCorrector { model.editor.corners }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                ForEach(corners.typeChips(), id: \.label) { chip in
                    Chip(label: chip.label, on: chip.selected, enabled: chip.enabled) {
                        if !chip.selected { model.correct(chip.edit) }
                    }
                }
            }
            .padding(.vertical, 4)
            if naming {
                nameField
            } else {
                actions
            }
        }
        .padding(.vertical, 8)
        .onChange(of: corners.segmentId) { naming = false }
    }

    /// Named in place, under what it is: no window over the map.
    private var nameField: some View {
        let numbered = corners.selectedSegment?.numberedName ?? ""
        let valid = corners.validName(name: name)
        return VStack(alignment: .leading, spacing: 6) {
            TextField(numbered, text: $name)
                .textFieldStyle(.roundedBorder)
                .submitLabel(.done)
                .onSubmit { if valid { save() } }
                .accessibilityIdentifier("segmentName")
            DimLine("Leave it empty for “\(numbered)”.")
            HStack(spacing: 8) {
                Spacer()
                Button("Cancel") { naming = false }
                Button("Save") { save() }
                    .buttonStyle(.borderedProminent)
                    .disabled(!valid)
                    .accessibilityIdentifier("segmentNameSave")
            }
            .tint(Palette.accent)
        }
    }

    private func save() {
        naming = false
        model.correct(corners.renameEdit(name: name))
    }

    /// Rename, split, merge with the next one and remove, each on only when it applies.
    private var actions: some View {
        let split = corners.splitEdit()
        let merge = corners.mergeEdit()
        let remove = corners.removeEdit()
        let canRemove = remove.map { corners.applies(edit: $0) } ?? false
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                action("Rename", id: "segmentRename", enabled: !corners.correcting) {
                    name = corners.selectedSegment?.label ?? ""
                    naming = true
                }
                action("Split", id: "segmentSplit", enabled: applies(split)) { model.correct(split) }
            }
            HStack(spacing: 8) {
                action("Merge with next", id: "segmentMerge", enabled: applies(merge)) { model.correct(merge) }
                action("Remove", id: "segmentRemove", enabled: canRemove, color: canRemove ? TrackColors.loss : nil) {
                    model.correct(remove)
                }
            }
            DimLine(CornerCorrectorKt.SegmentDragNote).padding(.top, 6)
            if let note = corners.correctionNote { DimLine(note).padding(.top, 6) }
        }
    }

    private func applies(_ edit: SegmentEdit?) -> Bool { edit.map { corners.applies(edit: $0) } ?? false }

    private func action(
        _ label: String, id: String, enabled: Bool, color: Color? = nil, run: @escaping () -> Void
    ) -> some View {
        Button(action: run) {
            Text(label).font(.subheadline.weight(.medium))
                .foregroundStyle(enabled ? (color ?? Palette.accent) : Palette.onSurfaceVariant)
                .padding(.horizontal, 14).padding(.vertical, 8)
                .overlay(RoundedRectangle(cornerRadius: 18).stroke(Palette.outline))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityIdentifier(id)
    }
}
