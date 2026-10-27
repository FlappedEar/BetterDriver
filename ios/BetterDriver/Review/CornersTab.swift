import SwiftUI
import BetterDriverCore

/// The Corners tab, as on Android: the raw theoretical best by corner, then each corner's
/// braking, speeds and pickup on the best lap and the compared lap (FBBD-104, UI-68).
struct CornersTab: View {
    let screen: AnalysisUi?

    var body: some View {
        CardColumn {
            if let screen {
                Sectors(ui: screen)
                ForEach(Array(screen.corners.enumerated()), id: \.offset) { _, corner in
                    CornerDetailCard(corner: corner, bestLap: Int(screen.bestLapNumber), other: screen.compare.map { Int($0.otherNumber) })
                }
            } else {
                Card { Dim("No counted lap, so no corners.") }
            }
        }
        .accessibilityIdentifier("cornersTab")
    }
}

/// Android's raw theoretical best by corner.
private struct Sectors: View {
    let ui: AnalysisUi
    /// Losses below this are GPS timing noise (AnalysisUi.kt MeaningfulLossSeconds).
    private let meaningfulLoss = 0.05

    var body: some View {
        Card {
            SectionTitle("Raw theoretical best by corner")
            if let note = ui.sectorsNote { Dim(note) }
            if !ui.sectors.isEmpty {
                let compared = ui.compare.map { "Lap \($0.otherNumber)" }
                HStack {
                    Dim("").frame(maxWidth: .infinity, alignment: .leading)
                    Dim("Best").frame(width: 84, alignment: .leading)
                    if let compared { Dim(compared).frame(width: 72, alignment: .leading) }
                    Dim("Spread").frame(width: 60, alignment: .leading)
                }
                ForEach(Array(ui.sectors.enumerated()), id: \.offset) { _, s in
                    HStack(alignment: .center) {
                        Text(s.name)
                            .foregroundStyle(s.corner ? Palette.onBackground : Palette.onSurfaceVariant)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        VStack(alignment: .leading, spacing: 0) {
                            Text(s.bestSeconds.map { CoreText.seconds($0.doubleValue) } ?? CoreText.missing).fontWeight(.semibold)
                            if let lap = s.bestLapNumber { Dim("lap \(lap.intValue)") }
                        }
                        .frame(width: 84, alignment: .leading)
                        if compared != nil {
                            let loss = s.lossSeconds?.doubleValue
                            Text(loss.map { CoreBridge.delta(seconds: $0) } ?? CoreText.missing)
                                .foregroundStyle((loss ?? 0) > meaningfulLoss ? Palette.loss : Palette.onSurfaceVariant)
                                .frame(width: 72, alignment: .leading)
                        }
                        Dim(s.spreadSeconds.map { CoreText.seconds($0.doubleValue) } ?? CoreText.missing)
                            .frame(width: 60, alignment: .leading)
                    }
                    .font(.body.monospacedDigit())
                }
            }
        }
    }
}

/// Android's corner details card for one corner.
private struct CornerDetailCard: View {
    let corner: CornerDetail
    let bestLap: Int
    let other: Int?

    var body: some View {
        Card {
            SectionTitle(corner.name)
            HStack {
                Dim("").frame(maxWidth: .infinity, alignment: .leading)
                Dim("Lap \(bestLap)").frame(width: 76, alignment: .leading)
                if let other { Dim("Lap \(other)").frame(width: 76, alignment: .leading) }
                Dim("Spread").frame(width: 76, alignment: .leading)
            }
            ForEach(Array(corner.rows.enumerated()), id: \.offset) { _, row in
                HStack {
                    Text(row.label).foregroundStyle(Palette.onBackground).frame(maxWidth: .infinity, alignment: .leading)
                    // A Low value is not shown (FBBD-177).
                    Text(ConfidenceUiKt.valueText(text: value(row, row.best), confidence: row.confidence))
                        .fontWeight(.semibold)
                        .frame(width: 76, alignment: .leading)
                    if other != nil {
                        Text(ConfidenceUiKt.valueText(text: value(row, row.lap), confidence: row.lapConfidence))
                            .frame(width: 76, alignment: .leading)
                    }
                    Dim(value(row, row.spread)).frame(width: 76, alignment: .leading)
                }
                .font(.body.monospacedDigit())
            }
            ForEach(Array(corner.rows.enumerated()), id: \.offset) { _, row in
                if let c = row.shownConfidence {
                    Dim("\(row.label): \(c.note)", color: c.doubtful ? Palette.warning : Palette.onSurfaceVariant)
                }
            }
            Dim("Brakes: metres before the corner. Throttle: metres after its start. Coasting: off both pedals.")
            if corner.rows.contains(where: { $0.marked }) { Dim(CoreText.legend) }
            if let spread = corner.lineSpreadMeters {
                let line = CornerDetailsUiKt.formatMeasure(value: spread, measure: .meters, speedUnit: "")
                Dim(corner.lineWithinGps ? "Line spread \(line), within GPS accuracy" : "Line spread \(line)")
            }
        }
    }

    private func value(_ row: CornerMetricRow, _ v: KotlinDouble?) -> String {
        CoreText.marked(CornerDetailsUiKt.formatMeasure(value: v, measure: row.measure, speedUnit: corner.speedUnit), row.provenance)
    }
}
