import BetterDriverCore
import SwiftUI

/// The track screen's layout in Settings › Track screen (FBBD-345, UI-304 A), as the Android app's
/// TrackLayoutSection, so the tiles are set before driving: the screen drawn as it looks, and a tap on
/// a tile opens its list (UI-124 A). Each car has its own layout (UI-306): the cars are listed and the
/// picked one's is drawn, the next session's car first. Both screens are drawn: the main one and the
/// second, with the G radar (FBBD-262, FBBD-318).
struct TrackLayoutSection: View {
    @EnvironmentObject private var settings: SettingsModel
    @State private var picked: String?
    @State private var editing: SheetId?

    /// The phone gives G when the receiver does not (FBBD-337), so the G tile is always offered.
    private static let receiverHasG = true

    var body: some View {
        let setup = settings.setup
        let next = setup.session.carId
        let carId = picked.flatMap { setup.car(id: $0) != nil ? $0 : nil } ?? next
        let ids = TrackTiles.shared.ids(setup: setup, carId: carId, second: false)
        let secondIds = TrackTiles.shared.ids(setup: setup, carId: carId, second: true)
        let model = TrackScreenModel(
            layout: ids, secondIds: secondIds, receiverHasG: Self.receiverHasG,
            coachOn: SettingsCore.shared.coachOn(s: settings.coach)
        )
        if !setup.cars.isEmpty {
            ShellLabel("Layout for")
            ShellCard {
                ForEach(Array(setup.cars.enumerated()), id: \.element.id) { i, car in
                    ListRow(
                        label: car.name,
                        divider: i > 0,
                        supporting: car.id == next ? "Next session" : nil,
                        action: { picked = car.id },
                        trailing: {
                            Image(systemName: car.id == carId ? "largecircle.fill.circle" : "circle")
                                .foregroundStyle(car.id == carId ? Palette.accent : Palette.onSurfaceVariant)
                        }
                    )
                }
            }
        }
        ShellLabel("Screen 1 · tap a tile to change it")
        TrackLayoutPreview(model: model, page: 0) { editing = SheetId(id: "0:\($0)") }
            .accessibilityIdentifier("layoutMain")
        ShellLabel("Screen 2 · swipe left while driving")
        TrackLayoutPreview(model: model, page: 1) { editing = SheetId(id: "1:\($0)") }
            .accessibilityIdentifier("layoutSecond")
        DimText(
            "Screen 2 shows the G radar and the first \(TrackScreenModel.radarColumns * TrackScreenModel.secondRows) "
                + "tiles. A tile with no data in a session is left out."
        )
        ShellCard {
            ListRow("Reset tiles to default", divider: false) {
                settings.update { TrackTiles.shared.reset(setup: $0, carId: carId) }
                AppLog.i("Settings", "tiles of car=\(carId ?? "none") reset to default")
            }
        }
        .sheet(item: $editing) { chosen in
            let parts = chosen.id.split(separator: ":").compactMap { Int($0) }
            let page = parts.first ?? 0
            let slot = parts.last ?? 0
            let pageIds = page == 0 ? ids : secondIds
            TileListSheet(
                title: "Tile \(slot + 1) · screen \(page + 1)",
                current: pageIds.indices.contains(slot) && !pageIds[slot].isEmpty ? pageIds[slot] : nil,
                groups: TrackTiles.shared.settingsOptions(
                    receiverHasG: Self.receiverHasG, receiver: setup.active().gnss?.name
                )
            ) { id in
                settings.update {
                    TrackTiles.shared.choose(setup: $0, carId: carId, second: page == 1, slot: Int32(slot), id: id)
                }
                AppLog.i("Settings", "tile car=\(carId ?? "none") page=\(page) slot=\(slot) id=\(id ?? "empty")")
                editing = nil
            }
        }
    }
}

/// One track screen drawn as it looks on the phone in landscape, scaled to the width it is given
/// (TrackLayoutPreview): the track screen is designed on an iPhone 15 (852 x 393 points).
private struct TrackLayoutPreview: View {
    let model: TrackScreenModel
    let page: Int
    let onTapTile: (Int) -> Void

    private static let width: CGFloat = 852
    private static let height: CGFloat = 393

    var body: some View {
        GeometryReader { geo in
            let scale = geo.size.width / Self.width
            TrackScreenContent(model: model, page: page) { _, slot in onTapTile(slot) }
                .frame(width: Self.width, height: Self.height)
                .background(TrackColors.background)
                .clipShape(RoundedRectangle(cornerRadius: 8 / scale))
                .scaleEffect(scale, anchor: .topLeading)
        }
        .aspectRatio(Self.width / Self.height, contentMode: .fit)
    }
}

/// A tile's list (UI-124 A) in the track screen's colours (TilePicker): "Empty", then every channel by
/// where it comes from. A channel with no data is dimmed and can still be picked. Picking one closes it.
private struct TileListSheet: View {
    let title: String
    let current: String?
    let groups: [TileOptionGroup]
    let onChoose: (String?) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(TrackFonts.text(18)).foregroundStyle(TrackColors.text)
            ScrollView {
                VStack(alignment: .leading, spacing: 4) {
                    row("Empty", "", selected: current == nil, hasData: true) { onChoose(nil) }
                    ForEach(groups, id: \.title) { group in
                        TrackLabel(text: group.title).padding(.top, 10).padding(.bottom, 2)
                        ForEach(group.options, id: \.id) { option in
                            row(option.label, option.value, selected: option.id == current, hasData: option.hasData) {
                                onChoose(option.id)
                            }
                        }
                    }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 20)
        .presentationDetents([.large])
        .presentationBackground(TrackColors.surface)
        .accessibilityIdentifier("tileList")
    }

    private func row(
        _ label: String, _ value: String, selected: Bool, hasData: Bool, action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                ZStack {
                    Circle().strokeBorder(selected ? TrackColors.gain : TrackColors.textDim, lineWidth: 2)
                    if selected { Circle().fill(TrackColors.gain).frame(width: 8, height: 8) }
                }
                .frame(width: 18, height: 18)
                Text(label)
                    .font(TrackFonts.text(16, selected ? .semibold : .medium))
                    .foregroundStyle(TrackColors.text)
                    .lineLimit(1)
                Spacer(minLength: 8)
                Text(value).font(TrackFonts.text(15, .medium)).foregroundStyle(TrackColors.textDim).lineLimit(1)
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
            .opacity(hasData ? 1 : 0.45)
        }
        .buttonStyle(.plain)
    }
}
