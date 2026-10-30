import BetterDriverCore
import SwiftUI

/// A session at no saved track with a line (FBBD-367, Android's FBBD-151): when it drove a closed
/// loop again and again, the summary offers to make a track from it. The line goes across the middle
/// of the longest straight and the track editor opens on it; nothing is saved until Save there,
/// after which the session is timed with it. Without a loop, the summary's plain no-laps line.
struct MakeTrackCard: View {
    let sessionId: String
    @State private var proposal: TrackProposal?
    @State private var looked = false
    @State private var editing: TrackProposalLink?

    var body: some View {
        Card {
            if let proposal {
                Text(proposal.text).foregroundStyle(Palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                Button(TrackProposalKt.MakeTrackAction) { editing = TrackProposalLink(proposal: proposal) }
                    .buttonStyle(.borderedProminent)
                    .tint(Palette.accent)
                    .accessibilityIdentifier("makeTrack")
            } else {
                Text(looked ? "No saved track with a start/finish line here, so no laps." : "Looking for laps of a loop…")
                    .foregroundStyle(Palette.onSurfaceVariant)
            }
        }
        .task(id: sessionId) {
            let id = sessionId
            proposal = await Task.detached { ReviewBridge.library.trackProposal(id: id) }.value
            looked = true
        }
        .fullScreenCover(item: $editing) { link in
            TrackEditorScreen(trackId: link.proposal.track.id, proposal: link.proposal) { editing = nil }
        }
    }
}

private struct TrackProposalLink: Identifiable {
    let proposal: TrackProposal
    var id: String { proposal.track.id }
}
