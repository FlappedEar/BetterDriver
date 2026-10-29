import SwiftUI
import BetterDriverCore

/// A session's coach review to open (FBBD-99): its id and title, as Android's route passes them.
struct CoachReviewLink: Identifiable, Hashable {
    let id: String
    /// "Sat 11 Oct, 14:02", as Android's sessionTitle.
    let title: String

    init(_ session: SessionRow) {
        id = session.id
        title = Self.titleFormat.string(from: session.start)
    }

    private static let titleFormat: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale.current
        f.dateFormat = "EEE d MMM, HH:mm"
        return f
    }()
}

/// How many cues each session has (FBBD-99), so a session the coach spoke in offers its review, as
/// Android's session card shows "Coach review · 12 cues". Read off the main thread, each session once
/// (ReviewLibrary.cueCounts keeps them); a session with no cues is not in [counts].
@MainActor
@Observable
final class CoachReviewCounts {
    static let shared = CoachReviewCounts()

    private(set) var counts: [String: Int] = [:]
    private var reading = false
    private var again = false

    /// Reads the counts again, after the session list changed.
    func refresh() {
        guard !reading else {
            again = true
            return
        }
        reading = true
        Task {
            counts = await Task.detached(priority: .utility) { ReviewBridge.cueCounts() }.value
            reading = false
            if again {
                again = false
                refresh()
            }
        }
    }

    /// "1 cue", "12 cues", as Android's row says it.
    static func text(_ n: Int) -> String { n == 1 ? "1 cue" : "\(n) cues" }
}

extension ReviewBridge {
    /// Cue counts by session id, for sessions with at least one (FBBD-99). Not on the main thread.
    static func cueCounts() -> [String: Int] {
        library.cueCounts().mapValues { $0.intValue }
    }

    /// The coach review of session [id], worded by the shared core as on Android. Not on the main thread.
    static func coachReview(_ id: String) -> CoachReviewUi { library.cueReview(id: id) }
}

/// The cue review of one session (FBBD-28, FBBD-99), as Android's CueReviewScreen: every cue the coach
/// said, in order, and for a corner cue what the next pass through that corner did, marked Better or
/// Not yet. One column on the phone, two or three on the tablet (cueColumns, FBBD-40).
struct CoachReviewScreen: View {
    let link: CoachReviewLink
    @Environment(\.dismiss) private var dismiss
    @State private var review: CoachReviewUi?

    var body: some View {
        NavigationStack {
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .background(Palette.background)
                .navigationTitle("Coach review")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
                }
        }
        .task(id: link) {
            let id = link.id
            // Only the session's events are read, off the main thread.
            review = await Task.detached(priority: .userInitiated) { ReviewBridge.coachReview(id) }.value
        }
    }

    @ViewBuilder private var content: some View {
        if let review {
            GeometryReader { geo in
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        CoachReviewHeader(title: link.title, review: review)
                        LazyVGrid(
                            columns: Array(
                                repeating: GridItem(.flexible(), spacing: 12, alignment: .top),
                                count: Self.columns(geo.size)
                            ),
                            alignment: .leading,
                            spacing: 12
                        ) {
                            // No id by cue number: a restarted coach numbers its cues from 1 again.
                            ForEach(Array(review.cards.enumerated()), id: \.offset) { _, card in
                                CoachReviewCueCard(card: card)
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 16)
                }
            }
        } else {
            CoachReviewBlock { DimText("Reading session…") }
                .padding(.horizontal, 16)
        }
    }

    /// Android's cueColumns on its window size classes (600 and 840 wide, 480 high): one column on a
    /// phone, two on an upright tablet or a large phone held sideways, three on a tablet held sideways.
    static func columns(_ size: CGSize) -> Int {
        let short = size.height < 480
        switch size.width {
        case ..<600: return 1
        case ..<840: return short ? 1 : 2
        default: return short ? 2 : 3
        }
    }
}

/// The session, the review's summary and the cues not said, over the cards.
private struct CoachReviewHeader: View {
    let title: String
    let review: CoachReviewUi

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(Palette.onBackground)
            DimText(review.summary)
            if let notSaid = review.notSaid { DimText(notSaid) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// One cue: where it was said and the verdict, its words in quotes, its confidence and what followed.
private struct CoachReviewCueCard: View {
    let card: CueCard

    var body: some View {
        CoachReviewBlock {
            HStack(alignment: .center, spacing: 8) {
                Text(card.place)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Palette.onBackground)
                    .frame(maxWidth: .infinity, alignment: .leading)
                DimText(card.verdict, color: verdictColor)
            }
            Text(card.quote)
                .font(.body)
                .foregroundStyle(Palette.onBackground)
                .fixedSize(horizontal: false, vertical: true)
            if let confidence = card.confidence { DimText(confidence) }
            if let outcome = card.outcome { DimText(outcome) }
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("cueCard")
    }

    private var verdictColor: Color {
        switch card.tone {
        case .gain: return TrackColors.gain
        case .loss: return TrackColors.loss
        default: return Palette.onSurfaceVariant
        }
    }
}

/// Android's ShellCard as the review uses it: a column 8 apart, on the background without a card.
private struct CoachReviewBlock<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) { content }
            .padding(.horizontal, 4)
            .padding(.vertical, 6)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}
