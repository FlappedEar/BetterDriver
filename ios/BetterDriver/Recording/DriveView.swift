import BetterDriverCore
import CoreLocation
import SwiftUI

/// The Drive tab on the iPhone, as Android's home (FBBD-338): the devices of the next session as chips,
/// the amber START with the place the phone is at, and the car and receiver line with Change, which
/// opens This session. Without a GNSS receiver START is grey and a card offers to add one.
struct DriveView: View {
    @EnvironmentObject private var recorder: RecordingController
    @EnvironmentObject private var settings: SettingsModel
    @StateObject private var place = PlaceFinder()
    @State private var choosing = false
    @State private var adding = false

    var body: some View {
        let setup = settings.setup
        let canStart = setup.active().gnss != nil
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    DeviceChips(setup: setup)
                    StartButton(
                        label: canStart ? place.name ?? "No track detected" : "No GNSS receiver",
                        enabled: canStart
                    ) { recorder.start(settings: settings) }
                        .padding(.vertical, 8)
                    ChoiceLine(setup: setup) { choosing = true }
                    if !canStart {
                        ShellCard {
                            Text("Add a GNSS receiver to start").fontWeight(.semibold).foregroundStyle(Palette.onBackground)
                            DimText("Lap timing needs one. Add a RaceBox, or Phone GPS to try the app.")
                                .padding(.vertical, 6)
                            ShellTonalButton(text: "Add receiver") { adding = true }
                        }
                    }
                    if let error = recorder.error {
                        Text(error)
                            .font(.footnote)
                            .foregroundStyle(TrackColors.loss)
                            .multilineTextAlignment(.center)
                            .accessibilityIdentifier("recordingError")
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
            }
            .frame(maxWidth: .infinity)
            .background(Palette.background)
            .navigationTitle("BetterDriver")
        }
        .sheet(isPresented: $choosing) {
            SessionSheet()
                .environmentObject(settings)
                .presentationDetents([.medium, .large])
                .presentationBackground(Palette.background)
        }
        .sheet(isPresented: $adding) {
            NavigationStack { AddEquipmentPage() }
                .environmentObject(settings)
                .presentationBackground(Palette.background)
        }
        .fullScreenCover(isPresented: Binding(get: { recorder.isRecording }, set: { _ in })) {
            TrackScreenView(model: recorder.screen) { recorder.stop() }
        }
        .onAppear { place.find() }
    }
}

/// The 230 point START with the place the phone is at (UI-282 B, as on Android); grey when it cannot start.
private struct StartButton: View {
    let label: String
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Text("START").font(TrackFonts.number(44))
                Text(label).font(TrackFonts.text(15)).lineLimit(1).padding(.horizontal, 24)
            }
            .foregroundStyle(enabled ? Palette.onAccent : Palette.onSurfaceVariant)
            .frame(width: 230, height: 230)
            .background(Circle().fill(enabled ? Palette.accent : Palette.outline))
            .padding(6)
            .overlay(Circle().stroke(enabled ? Palette.accent.opacity(0.25) : Palette.outline, lineWidth: 12))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityIdentifier("startButton")
    }
}

/// The devices the next session uses, one chip each: the receiver, the OBD-II adapters, the sensors and
/// the cameras turned on. A chip names a device; it does not say whether it answers.
private struct DeviceChips: View {
    let setup: Setup

    var body: some View {
        let active = setup.active()
        let names = [active.gnss?.name].compactMap { $0 }
            + active.carData.map(\.name) + active.sensors.map(\.name) + active.cameras.map(\.name)
        if !names.isEmpty {
            ChipFlow(spacing: 8) {
                ForEach(Array(names.enumerated()), id: \.offset) { _, name in
                    Text(name)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(Palette.onBackground)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Palette.outline, lineWidth: 1))
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// Lays chips out in rows, wrapping, as Compose's FlowRow.
private struct ChipFlow: Layout {
    let spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(subviews, width: proposal.width ?? .infinity)
        let width = rows.map { $0.width }.max() ?? 0
        let height = rows.map { $0.height }.reduce(0, +) + spacing * CGFloat(max(rows.count - 1, 0))
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in arrange(subviews, width: bounds.width) {
            var x = bounds.minX
            for i in row.items {
                let size = subviews[i].sizeThatFits(.unspecified)
                subviews[i].place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
            y += row.height + spacing
        }
    }

    private struct Row {
        var items: [Int] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func arrange(_ subviews: Subviews, width: CGFloat) -> [Row] {
        var rows: [Row] = [Row()]
        for i in subviews.indices {
            let size = subviews[i].sizeThatFits(.unspecified)
            let extra = rows[rows.count - 1].items.isEmpty ? size.width : rows[rows.count - 1].width + spacing + size.width
            if extra > width, !rows[rows.count - 1].items.isEmpty {
                rows.append(Row())
            }
            var row = rows[rows.count - 1]
            row.width = row.items.isEmpty ? size.width : row.width + spacing + size.width
            row.height = max(row.height, size.height)
            row.items.append(i)
            rows[rows.count - 1] = row
        }
        return rows
    }
}

/// "Corolla · RaceBox Micro 3241" over "Phone camera on", and Change (UI-130 C, UI-132 B).
private struct ChoiceLine: View {
    let setup: Setup
    let change: () -> Void

    var body: some View {
        let active = setup.active()
        let car = active.car?.name ?? (setup.cars.isEmpty ? "No car added" : "No car")
        let gnss = active.gnss?.name ?? "No GNSS receiver"
        ShellCard {
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(car) · \(gnss)").fontWeight(.semibold).foregroundStyle(Palette.onBackground)
                    DimText(SettingsCore.shared.camerasLine(setup: setup))
                }
                Spacer(minLength: 8)
                Button("Change", action: change).tint(Palette.accent).accessibilityIdentifier("changeSession")
            }
            .padding(.vertical, 8)
        }
    }
}

/// This session (UI-36 A): the car, the GNSS receiver and a switch per camera. The last choice is
/// kept, so on a normal day only START is pressed. A camera turned off here does not record.
struct SessionSheet: View {
    @EnvironmentObject private var settings: SettingsModel
    @Environment(\.dismiss) private var dismiss
    @State private var picking: SessionPick?

    var body: some View {
        let setup = settings.setup
        let active = setup.active()
        NavigationStack {
            ShellPage(title: "This session") {
                ShellCard {
                    let car = active.car?.name ?? (setup.cars.isEmpty ? "Add a car" : "None")
                    ListRow("Car", value: car, divider: false, chevron: true) { picking = .car }
                    ListRow("GNSS", value: active.gnss?.name ?? "None", chevron: true) { picking = .gnss }
                    ForEach(setup.equipment.filter { $0.kindName == KindName.camera }, id: \.id) { cam in
                        ListRow(
                            label: cam.name,
                            supporting: cam.modelName == ModelName.phoneCamera ? nil : "Start by hand",
                            trailing: {
                                Toggle("", isOn: Binding(
                                    get: { settings.setup.session.cameraIds.contains(cam.id) },
                                    set: { on in settings.update { $0.useCamera(id: cam.id, on: on) } }
                                ))
                                .labelsHidden()
                                .tint(Palette.accent)
                            }
                        )
                    }
                }
            }
            .navigationDestination(item: $picking) { pick in
                SessionChoicePage(pick: pick)
            }
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() }.tint(Palette.accent) }
            }
        }
    }
}

struct SessionOption: Identifiable {
    let id: String
    let name: String
}

enum SessionPick: Hashable, Identifiable {
    case car, gnss
    var id: Self { self }
}

/// The car or the GNSS receiver of the next session: a radio list with None last and Add, as Android's
/// choice dialog. Choosing goes back.
private struct SessionChoicePage: View {
    let pick: SessionPick
    @EnvironmentObject private var settings: SettingsModel
    @Environment(\.dismiss) private var dismiss
    @State private var adding = false

    var body: some View {
        let setup = settings.setup
        let options: [SessionOption] = pick == .car
            ? setup.cars.map { SessionOption(id: $0.id, name: $0.name) }
            : setup.equipment.filter { $0.kindName == KindName.gnss }.map { SessionOption(id: $0.id, name: $0.name) }
        let selected = pick == .car ? setup.session.carId : setup.session.gnssId
        ShellPage(title: pick == .car ? "Car" : "GNSS receiver") {
            ShellCard {
                ForEach(Array(options.enumerated()), id: \.element.id) { i, option in
                    radio(option.name, picked: option.id == selected, divider: i > 0) { choose(option.id) }
                }
                radio("None", picked: selected == nil, divider: !options.isEmpty) { choose(nil) }
                AddRow(text: pick == .car ? "Add car" : "Add receiver") { adding = true }
            }
        }
        .sheet(isPresented: $adding) {
            Group {
                if pick == .car {
                    CarEditSheet(carId: nil)
                } else {
                    NavigationStack { AddEquipmentPage() }
                }
            }
            .environmentObject(settings)
            .presentationBackground(Palette.background)
        }
    }

    private func radio(_ text: String, picked: Bool, divider: Bool, action: @escaping () -> Void) -> some View {
        ListRow(label: text, divider: divider, action: action) {
            Image(systemName: picked ? "largecircle.fill.circle" : "circle")
                .foregroundStyle(picked ? Palette.accent : Palette.onSurfaceVariant)
        }
    }

    private func choose(_ id: String?) {
        settings.update { pick == .car ? $0.chooseCar(id: id) : $0.chooseGnss(id: id) }
        dismiss()
    }
}

/// Names the track the phone is at for the START button, from one position: a saved track, else the
/// circuit directory (FBBD-79); nothing elsewhere or without location access.
@MainActor
final class PlaceFinder: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published private(set) var name: String?
    private let manager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    func find() {
        switch manager.authorizationStatus {
        case .notDetermined: manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways: manager.requestLocation()
        default: break
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        guard status == .authorizedWhenInUse || status == .authorizedAlways else { return }
        manager.requestLocation()
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let at = locations.last?.coordinate else { return }
        let tracks = TrackFiles.shared.read(dir: AppFolders.tracks.path)
        let found = TrackFiles.shared.placeAt(
            lat: at.latitude, lon: at.longitude, tracks: tracks, circuits: BundledCircuits.list
        )
        Task { @MainActor in self.name = found }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}
}
