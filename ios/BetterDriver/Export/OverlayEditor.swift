import BetterDriverCore
import SwiftUI

/// The video overlay editor's state (FBBD-472) over the shared OverlayEditorState: every change is
/// saved to the setup at once (Setup.videoOverlay, one choice for every video, UI-316 A).
@MainActor
final class OverlayEditorModel: ObservableObject {
    @Published private(set) var revision = 0
    let state: OverlayEditorState

    init(state: OverlayEditorState) {
        self.state = state
    }

    /// From Settings › Video: no session, so no channels to add and no widget dimmed.
    static func settings() -> OverlayEditorModel {
        OverlayEditorModel(state: OverlayEditorState(stored: SettingsModel.shared.setup.videoOverlay, available: nil, channels: nil))
    }

    func change(_ edit: (OverlayEditorState) -> Void) {
        edit(state)
        let stored = state.stored
        SettingsModel.shared.update { $0.withVideoOverlay(stored: stored) }
        revision += 1
    }
}

/// The session video's overlay editor (FBBD-472, round 65), as Android's: a picture of the Tech HUD
/// on top where each widget and value plate is dragged to its place (UI-322 A, snapped to the grid,
/// never over another), a switch per widget (UI-315 A; dimmed without data, UI-317 A), the value plates
/// from the session's channels (UI-319 A) and Reset. Opened from a session's Video tab and from
/// Settings › Video (UI-314 C).
struct OverlayEditorView: View {
    @ObservedObject var model: OverlayEditorModel
    let onClose: () -> Void

    @State private var adding = false
    @State private var renaming: String?
    @State private var label = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 8) {
                OverlayPicture(model: model)
                    .padding(.horizontal, 16)
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        DimText("Drag a widget on the picture to move it. Save video and Watch draw what is on here.")
                        switches
                        values
                        ShellCard {
                            ListRow("Reset to Tech HUD", divider: false, supporting: "Every widget back in its place") {
                                model.change { $0.reset() }
                            }
                            .disabled(!model.state.canReset)
                            .opacity(model.state.canReset ? 1 : 0.45)
                            .accessibilityIdentifier("overlayReset")
                        }
                    }
                    .padding(16)
                }
            }
            .background(Palette.background)
            .navigationTitle("Overlay")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done", action: onClose) }
            }
            .sheet(isPresented: $adding) { addSheet }
            .alert("Plate label", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
                TextField("Label", text: $label)
                Button("Save") {
                    if let channel = renaming {
                        let text = String(label.prefix(10))
                        model.change { $0.rename(channel: channel, label: text) }
                    }
                    renaming = nil
                }
                Button("Cancel", role: .cancel) { renaming = nil }
            }
        }
        .accessibilityIdentifier("overlayEditor")
    }

    private var switches: some View {
        ShellCard {
            ForEach(Array(model.state.rows.enumerated()), id: \.element.widget.name) { i, row in
                ListRow(
                    label: row.name, divider: i > 0,
                    supporting: row.hasData ? nil : "No data in this session",
                    trailing: {
                        Toggle("", isOn: Binding(
                            get: { row.on },
                            set: { on in model.change { $0.toggle(widget: row.widget, on: on) } }
                        ))
                        .labelsHidden()
                        .tint(Palette.accent)
                        .accessibilityIdentifier("overlaySwitch\(row.widget.name)")
                    }
                )
                .opacity(row.hasData ? 1 : 0.45)
            }
        }
    }

    private var values: some View {
        ShellCard {
            ForEach(Array(model.state.values.enumerated()), id: \.element.channel) { i, v in
                ListRow(
                    label: v.label, divider: i > 0, supporting: v.channel,
                    action: {
                        label = v.label
                        renaming = v.channel
                    },
                    trailing: {
                        Button("Remove") { model.change { $0.remove(channel: v.channel) } }
                            .buttonStyle(.borderless)
                    }
                )
            }
            if model.state.hasSession {
                ListRow(
                    "Add a value", divider: !model.state.values.isEmpty,
                    supporting: model.state.canAdd ? "From the channels this session recorded" : "Five values is the most",
                    chevron: true
                ) { adding = true }
                .disabled(!model.state.canAdd)
                .accessibilityIdentifier("overlayAddValue")
            } else {
                ListRow(
                    "Add a value", divider: !model.state.values.isEmpty,
                    supporting: "From a session's Video tab: values come from its recorded channels"
                )
            }
        }
    }

    private var addSheet: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    let channels = model.state.addable
                    if channels.isEmpty { DimText("Every channel this session recorded is on already.") }
                    ForEach(Array(channels.enumerated()), id: \.element.name) { i, c in
                        ListRow(
                            c.name, divider: i > 0,
                            supporting: [c.source, c.unit].filter { !$0.isEmpty }.joined(separator: " · ")
                        ) {
                            model.change { $0.add(channel: c.name) }
                            adding = false
                        }
                    }
                }
                .padding(16)
            }
            .background(Palette.background)
            .navigationTitle("Add a value")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { adding = false } }
            }
        }
    }
}

/// The 16:9 picture with the shown widgets and plates at their places; a plate dragged and let go
/// moves there if it fits, else goes back.
private struct OverlayPicture: View {
    @ObservedObject var model: OverlayEditorModel

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height
            ZStack(alignment: .topLeading) {
                LinearGradient(
                    colors: [Color(red: 0.36, green: 0.46, blue: 0.56), Color(red: 0.16, green: 0.17, blue: 0.18)],
                    startPoint: .top, endPoint: .bottom
                )
                ForEach(model.state.plates, id: \.key) { plate in
                    OverlayPlate(plate: plate, width: w, height: h) { x, y in
                        model.change { $0.drop(key: plate.key, x: x, y: y) }
                    }
                }
            }
            .frame(width: w, height: h)
        }
        .aspectRatio(16 / 9, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .accessibilityIdentifier("overlayPicture")
    }
}

private struct OverlayPlate: View {
    let plate: OverlayEditorPlate
    let width: CGFloat
    let height: CGFloat
    let onDrop: (Float, Float) -> Void

    @GestureState private var drag: CGSize = .zero

    var body: some View {
        let b = plate.box
        let pw = CGFloat(b.right - b.left) * width
        let ph = CGFloat(b.bottom - b.top) * height
        Text(plate.label)
            .font(.system(size: 8, weight: .semibold))
            .foregroundStyle(.white)
            .multilineTextAlignment(.center)
            .padding(1)
            .frame(width: pw, height: ph)
            .background(Color(red: 0.04, green: 0.05, blue: 0.055).opacity(drag == .zero ? 0.82 : 0.94))
            .overlay(Rectangle().stroke(Palette.accent.opacity(drag == .zero ? 0.5 : 1), lineWidth: 1))
            .offset(x: CGFloat(b.left) * width + drag.width, y: CGFloat(b.top) * height + drag.height)
            .gesture(
                DragGesture()
                    .updating($drag) { value, state, _ in state = value.translation }
                    .onEnded { value in
                        onDrop(
                            b.left + Float(value.translation.width / width),
                            b.top + Float(value.translation.height / height)
                        )
                    }
            )
            .accessibilityIdentifier("overlayPlate-\(plate.key)")
    }
}

/// The row that opens the editor, with how many widgets and values it draws (UI-314 C).
struct OverlayRow: View {
    @ObservedObject private var settings = SettingsModel.shared
    var divider = true
    let onOpen: () -> Void

    var body: some View {
        ListRow(
            "Overlay",
            value: HudChoiceKt.hudChoiceSummary(choice: HudChoice.companion.decode(stored: settings.setup.videoOverlay)),
            divider: divider, chevron: true, action: onOpen
        )
        .accessibilityIdentifier("overlayRow")
    }
}
