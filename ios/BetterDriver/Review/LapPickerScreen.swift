import SwiftUI
import BetterDriverCore

// Compare laps from any sessions (FBBD-379, Android's FBBD-92 LapPicker.kt): the sessions at a track,
// newest first, with their laps. Tap a slot, then a lap; the other slot is next. Laps can come from
// any sessions of the track; Compare opens the two side by side in the lap review. The laps, the
// picks and the words are the shared core's.

/// Lap A's and lap B's colours, as in the lap review.
private enum PickerColor {
    static let a = Palette.onBackground
    static let b = Palette.cold
}

struct LapPickerScreen: View {
    @Environment(\.dismiss) private var dismiss
    @State private var tracks: [PickerTrack]?
    @State private var trackId: String?
    @State private var picks = LapPicks(a: nil, b: nil, nextIsA: true)
    @State private var compared: PickedPair?

    var body: some View {
        NavigationStack {
            Group {
                if let tracks, !tracks.isEmpty {
                    picker(tracks.first { $0.id == trackId } ?? tracks[0], all: tracks)
                } else if tracks != nil {
                    Dim("No laps yet: record a session at a saved track, or import one.").padding(40)
                } else {
                    ProgressView().tint(Palette.accent)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Palette.background)
            .navigationTitle("Compare laps")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Done") { dismiss() }.accessibilityIdentifier("lapPickerDone")
                }
            }
            .navigationDestination(item: $compared) { pair in
                if let tracks, let review = ReviewBridge.pickedReview(tracks, a: pair.a, b: pair.b) {
                    LapVideoView(review: review)
                } else {
                    Dim("These laps are not in the lap review.").padding(40)
                }
            }
        }
        .preferredColorScheme(.dark)
        .task {
            tracks = await Task.detached(priority: .userInitiated) { ReviewBridge.lapPicker() }.value
        }
    }

    private func picker(_ track: PickerTrack, all: [PickerTrack]) -> some View {
        List {
            if all.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(all, id: \.id) { t in
                            Button { switchTo(t) } label: {
                                Text(t.name)
                                    .font(.subheadline.weight(.semibold))
                                    .padding(.horizontal, 14)
                                    .padding(.vertical, 8)
                                    .foregroundStyle(t.id == track.id ? Palette.onAccent : Palette.onBackground)
                                    .background(t.id == track.id ? Palette.accent : Palette.surface, in: Capsule())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .listRowBackground(Palette.background)
            }
            HStack(spacing: 8) {
                slot("A", key: picks.a, color: PickerColor.a, active: picks.nextIsA, track: track) { picks = picks.next(isA: true) }
                slot("B", key: picks.b, color: PickerColor.b, active: !picks.nextIsA, track: track) { picks = picks.next(isA: false) }
            }
            .listRowBackground(Palette.background)
            Button {
                if let a = picks.a, let b = picks.b { compared = PickedPair(a: a, b: b) }
            } label: {
                Text("Compare").fontWeight(.semibold).frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(Palette.accent)
            .foregroundStyle(Palette.onAccent)
            .disabled(!picks.ready)
            .listRowBackground(Palette.background)
            .accessibilityIdentifier("lapPickerCompare")
            ForEach(track.sessions, id: \.id) { session in
                Section {
                    ForEach(session.laps, id: \.key) { lap in
                        PickerLapRow(lap: lap, best: lap.number == session.bestNumber?.int32Value, slot: picks.slotOf(key: lap.key)) {
                            picks = picks.pick(key: lap.key)
                        }
                    }
                } header: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(session.title).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.onBackground)
                        if !session.detail.isEmpty { Dim(session.detail) }
                    }
                    .textCase(nil)
                }
            }
        }
        .scrollContentBackground(.hidden)
        .frame(maxWidth: 720)
        .frame(maxWidth: .infinity)
        .accessibilityIdentifier("lapPicker")
    }

    private func switchTo(_ track: PickerTrack) {
        trackId = track.id
        picks = LapPicks(a: nil, b: nil, nextIsA: true)
    }

    private func slot(_ name: String, key: String?, color: Color, active: Bool, track: PickerTrack, tap: @escaping () -> Void) -> some View {
        let lap = key.flatMap { k in tracks?.lazy.compactMap { $0.lap(key: k) }.first }
        return Button(action: tap) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Lap \(name)").font(.caption.weight(.semibold)).foregroundStyle(color)
                Text(lap.map { "Lap \($0.number) · \($0.timeText)" } ?? "Tap a lap")
                    .font(.headline)
                    .foregroundStyle(Palette.onBackground)
                Dim(lap?.sessionTitle ?? "")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(active ? color : .clear, lineWidth: 2))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("slot\(name)")
    }
}

/// The two laps picked, by key, opened in the lap review.
private struct PickedPair: Hashable {
    let a: String
    let b: String
}

/// A lap: the slot it is in, its number and time, and what Android's row notes after it.
private struct PickerLapRow: View {
    let lap: PickerLap
    let best: Bool
    let slot: String?
    let tap: () -> Void

    var body: some View {
        Button(action: tap) {
            HStack(spacing: 10) {
                ZStack {
                    if let slot {
                        Circle().fill(slot == "A" ? PickerColor.a : PickerColor.b).frame(width: 22, height: 22)
                        Text(slot).font(.caption2.weight(.bold)).foregroundStyle(Palette.background)
                    }
                }
                .frame(width: 24)
                Text("Lap \(lap.number)").foregroundStyle(Palette.onBackground).frame(width: 64, alignment: .leading)
                Text(lap.timeText)
                    .font(.body.weight(.semibold).monospacedDigit())
                    .foregroundStyle(best ? Palette.best : (lap.timed ? Palette.onBackground : Palette.onSurfaceVariant))
                    .frame(maxWidth: .infinity, alignment: .leading)
                Dim(lap.notes(best: best))
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .listRowBackground(Palette.surface)
        .accessibilityIdentifier("pickerLap")
    }
}
