import BetterDriverCore
import SwiftUI

/// Tracks (UI-27 A, Android's TracksScreen; FBBD-336): where you are first, from the saved tracks and
/// the circuit list, with one tap to create a track there; any other place gets a track at your
/// position with a name you type (New track here). Your tracks are listed below; each opens the editor.
/// A track made here times laps from the next session's first fix.
struct TracksView: View {
    var body: some View {
        NavigationStack { TracksScreen() }
    }
}

/// The Tracks screen without a navigation stack of its own, to push from another screen (Settings).
struct TracksScreen: View {
    @StateObject private var model = TracksModel()
    @State private var editing: EditingTrack?
    @State private var naming = false
    @State private var nameText = ""

    var body: some View {
        Group {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    whereYouAre
                    if let here = model.here, here.track == nil {
                        Button {
                            nameText = ""
                            naming = true
                        } label: {
                            Text("Not on the list? New track here uses your position and a name you type.")
                                .font(.subheadline)
                                .foregroundStyle(Palette.accent)
                                .multilineTextAlignment(.leading)
                                .padding(.vertical, 4)
                        }
                        .accessibilityIdentifier("newTrackHere")
                    }
                    SectionLabel("My tracks")
                    TrackCard {
                        if model.tracks.isEmpty { DimLine("No tracks yet") }
                        ForEach(Array(model.tracks.enumerated()), id: \.element.id) { i, t in
                            Button { open(t) } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(t.displayName).foregroundStyle(Palette.onBackground)
                                    DimLine(t.detail)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.vertical, 10)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("trackRow")
                            .overlay(alignment: .top) {
                                if i > 0 { Rectangle().fill(Palette.outline).frame(height: 1) }
                            }
                        }
                    }
                }
                .padding(.horizontal, 16)
            }
            .background(Palette.background)
            .navigationTitle("Tracks")
            .toolbarBackground(Palette.background, for: .navigationBar)
        }
        .onAppear { model.refresh() }
        .fullScreenCover(item: $editing) { e in
            TrackEditorScreen(trackId: e.id) {
                editing = nil
                model.refresh()
            }
        }
        .alert("New track here", isPresented: $naming) {
            TextField("Name", text: $nameText)
            Button("Create") { model.createHere(name: nameText, open: open) }
                .disabled(!TracksModel.library.validName(name: nameText))
            Button("Cancel", role: .cancel) {}
        }
        .alert(model.message ?? "", isPresented: Binding(get: { model.message != nil }, set: { if !$0 { model.message = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }

    private func open(_ track: Track) { editing = EditingTrack(id: track.id) }

    private var whereYouAre: some View {
        TrackCard {
            SectionLabel("Where you are")
            if let here = model.here {
                if let track = here.track {
                    BigName(track.displayName)
                    DimLine("Your track · \(here.away) · \(track.detail)")
                    TonalButton("Edit lines") { open(track) }
                } else if let circuit = here.circuit {
                    BigName(circuit.name)
                    DimLine("From the circuit list · \(here.away) · no lines yet")
                    TonalButton("Create track") { model.create(from: circuit, open: open) }
                } else {
                    DimLine("No circuit on the list is near you.")
                }
            } else if model.locating {
                DimLine("Finding where you are…")
            } else {
                DimLine("Your position is not known: allow BetterDriver to use your location in Settings to make a track here.")
            }
        }
    }
}

private struct EditingTrack: Identifiable {
    let id: String
}

private struct TrackCard<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) { content }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: 16))
    }
}

private struct SectionLabel: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.onSurfaceVariant)
    }
}

private struct BigName: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text).font(.title2.weight(.semibold)).foregroundStyle(Palette.onBackground)
    }
}

private struct TonalButton: View {
    let title: String
    let action: () -> Void
    init(_ title: String, action: @escaping () -> Void) {
        self.title = title
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(title).font(.subheadline.weight(.semibold))
                .padding(.horizontal, 20).padding(.vertical, 10)
                .foregroundStyle(Palette.onBackground)
                .background(Palette.surfaceVariant, in: Capsule())
        }
        .buttonStyle(.plain)
        .padding(.top, 4)
    }
}
