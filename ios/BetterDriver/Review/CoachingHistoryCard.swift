import SwiftUI
import BetterDriverCore

/// Android's coaching history card (Coaching.kt, FBBD-180, FBBD-310, FBBD-364): what came of the
/// focus this session worked on, with the time won back on the corners worked on, then whether gains
/// from earlier sessions held. The shared core keeps the session's record and words every row; this
/// only draws them. Read off the main thread once the summary shows; nothing while it reads, or when
/// the history has nothing for this session. Tapping a row opens every pass through its corner.
struct CoachingHistoryCard: View {
    let summary: SessionSummary
    @State private var card: CoachingCardUi?

    var body: some View {
        Group {
            if let card, !card.empty { CoachingHistoryContent(card: card) }
        }
        .task(id: summary.id) {
            let read = summary
            card = await Task.detached(priority: .utility) { ReviewBridge.coaching(read) }.value
        }
    }
}

private struct CoachingHistoryContent: View {
    let card: CoachingCardUi

    var body: some View {
        Card {
            if !card.attempts.isEmpty {
                SectionTitle(CoachingRowsKt.LastFocusLabel)
                if let figure = card.wonBack {
                    HStack(alignment: .lastTextBaseline, spacing: 10) {
                        Text(figure)
                            .font(.system(size: 24, weight: .semibold).monospacedDigit())
                            .foregroundStyle(card.wonBackLost ? Palette.loss : Palette.gain)
                        Dim(card.wonBackText ?? "")
                    }
                    .accessibilityIdentifier("lastFocusWonBack")
                }
                ForEach(Array(card.attempts.enumerated()), id: \.offset) { _, row in CoachingHistoryLine(row: row) }
                if let note = card.note { Dim(note) }
            }
            if !card.checks.isEmpty {
                if !card.attempts.isEmpty { Divider().overlay(Palette.outline) }
                SectionTitle(CoachingRowsKt.EarlierGainsLabel)
                ForEach(Array(card.checks.enumerated()), id: \.offset) { _, row in CoachingHistoryLine(row: row) }
            }
        }
        .accessibilityIdentifier("coachingHistory")
    }
}

/// One corner of the card: its name and status, then what came of it; dim where its time was not precise.
private struct CoachingHistoryLine: View {
    let row: CoachingRow
    @Environment(\.everyPass) private var everyPass

    var body: some View {
        let open = row.cornerId.isEmpty ? nil : everyPass
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(row.name)
                    .fontWeight(.semibold)
                    .foregroundStyle(Palette.onBackground)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(row.status.label)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(row.status.gain ? Palette.gain : row.status.loss ? Palette.loss : Palette.onSurfaceVariant)
                if open != nil { Dim(" ›") }
            }
            if row.doubtful {
                Dim(row.text)
            } else {
                Text(row.text).font(.subheadline).foregroundStyle(Palette.onBackground)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .onTapGesture { open?(row.cornerId) }
    }
}
