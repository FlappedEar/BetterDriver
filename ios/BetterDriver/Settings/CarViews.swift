import BetterDriverCore
import SwiftUI

/// Settings › Cars: names and notes only (UI-135 B). The next session's car first and says so;
/// tapping a car opens its page (UI-133 B). No car comes with the app.
struct CarsPage: View {
    @EnvironmentObject private var settings: SettingsModel
    @State private var adding = false

    var body: some View {
        let chosen = settings.setup.session.carId
        let cars = settings.setup.cars.sorted { ($0.id == chosen ? 0 : 1) < ($1.id == chosen ? 0 : 1) }
        ShellPage(title: "Cars") {
            if cars.isEmpty {
                DimText("No cars yet. Add the car you drive; its name goes into each session and its export.")
            }
            ShellCard {
                ForEach(Array(cars.enumerated()), id: \.element.id) { i, car in
                    NavigationLink { CarPage(id: car.id, showsBack: true) } label: {
                        ListRow(
                            car.name, value: car.id == chosen ? "Next session" : nil, divider: i > 0,
                            supporting: car.notes.isEmpty ? nil : car.notes, chevron: true
                        )
                    }
                    .buttonStyle(.plain)
                }
                AddRow(text: "Add car", divider: !cars.isEmpty) { adding = true }
            }
            ShellCard { ListRow("Units", value: "km/h, °C", divider: false) }
        }
        .sheet(isPresented: $adding) { CarEditSheet(carId: nil) }
    }
}

/// The Car tab (UI-136 B): the next session's car's page, or the cars to choose from, or Add car.
struct CarHome: View {
    @EnvironmentObject private var settings: SettingsModel
    @State private var adding = false

    var body: some View {
        NavigationStack {
            let setup = settings.setup
            if let id = setup.session.carId, setup.car(id: id) != nil {
                CarPage(id: id, showsBack: false)
            } else {
                ShellPage(title: "Car", large: true) {
                    if setup.cars.isEmpty {
                        DimText("No cars yet. Add the car you drive; its name goes into each session and its export.")
                        ShellTonalButton(text: "Add car") { adding = true }
                    } else {
                        DimText("No car is chosen for the next session. Pick one to see its live values.")
                        ShellCard {
                            ForEach(Array(setup.cars.enumerated()), id: \.element.id) { i, car in
                                ListRow(car.name, value: "Use", divider: i > 0) {
                                    settings.update { $0.chooseCar(id: car.id) }
                                }
                            }
                            AddRow(text: "Add car") { adding = true }
                        }
                    }
                }
            }
        }
        .sheet(isPresented: $adding) { CarEditSheet(carId: nil) }
    }
}

/// A car's page (UI-133 B, FBBD-344 UI-300 A), titled with its name; Edit opens its name and notes. On
/// one page: the next session's car shows the adapter's state and live values; then the driver's own
/// parameters, then what the car offers at its OBD port from the last scan. Another car offers to
/// become the next session's. A tablet only reviews: the page is the car's name and notes.
struct CarPage: View {
    let id: String
    let showsBack: Bool
    @EnvironmentObject private var settings: SettingsModel
    @EnvironmentObject private var recorder: RecordingController
    @StateObject private var obd = ObdPageModel()
    @State private var editing = false

    var body: some View {
        let setup = settings.setup
        if let car = setup.car(id: id) {
            let chosen = setup.session.carId == car.id && !Device.isTablet
            ShellPage(title: car.name, large: !showsBack) {
                let line = [car.notes, chosen ? "next session" : ""].filter { !$0.isEmpty }
                if !line.isEmpty { DimText(line.joined(separator: " · ")) }
                if Device.isTablet {
                    DimText(CarPage.tabletNote)
                } else {
                    recordingContent(chosen: chosen)
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Edit") { editing = true }.tint(Palette.accent)
                }
            }
            .sheet(isPresented: $editing) { CarEditSheet(carId: car.id) }
            .onAppear { if chosen { obd.appear(recorder) } }
            .onDisappear { obd.disappear() }
            .onChange(of: chosen) { _, now in if now { obd.appear(recorder) } else { obd.disappear() } }
        }
    }

    /// The recording phone's part: the adapter's live values, the driver's own parameters and what the
    /// car offers at its OBD port. (By id: setup's and obd's CarProfile share a name in Swift.)
    @ViewBuilder
    private func recordingContent(chosen: Bool) -> some View {
        if let car = settings.setup.car(id: id) {
            if chosen {
                ObdContent(model: obd, showsScan: false)
            } else {
                DimText("Live values show on the next session's car: the adapter reads the car it is plugged into.")
                ShellTonalButton(text: "Use for the next session") { settings.update { $0.chooseCar(id: car.id) } }
            }
            ShellLabel("Your parameters")
            ShellCard {
                let readings = chosen ? obd.status?.readings ?? [] : []
                ForEach(Array(car.parameters.enumerated()), id: \.element.key) { i, p in
                    let reading = readings.first { $0.id == OwnChannels.shared.id(key: p.key) }
                    NavigationLink { ParameterSteps(carId: car.id, key: p.key, start: ParameterDraft.companion.of(p: p)) } label: {
                        ListRow(
                            p.name, value: reading.map { ObdPageText.shared.value(reading: $0) } ?? (p.unit.isEmpty ? "–" : p.unit),
                            divider: i > 0,
                            supporting: CarPageCore.shared.parameterSource(p: p), chevron: true
                        )
                    }
                    .buttonStyle(.plain)
                }
                NavigationLink { ParameterSteps(carId: car.id, key: nil, start: ParameterDraft.companion.empty()) } label: {
                    AddRowLabel(text: "Add parameter", divider: !car.parameters.isEmpty)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("addParameter")
            }
            if car.parameters.isEmpty {
                DimText(
                    "A value the app does not read: a CAN id or an OBD request with a formula. Recorded in every "
                        + "session with this car and in its VBO export."
                )
            }
            CarOffers(carId: car.id, chosen: chosen, obd: obd)
        }
    }
}

extension CarPage {
    /// A tablet's car page: the phone that records reads the car (as Android's TabletCarNote).
    static let tabletNote = "The phone that records reads this car: its adapter, live values and parameters are set "
        + "up there. Here its sessions are reviewed, with every value recorded."
}

/// What the car offers at its OBD port (FBBD-344): the PIDs and CAN ids of the last scan, and Scan car
/// (UI-303 B), which runs one on the car link only when tapped.
private struct CarOffers: View {
    let carId: String
    let chosen: Bool
    @ObservedObject var obd: ObdPageModel
    @EnvironmentObject private var settings: SettingsModel

    var body: some View {
        ShellLabel("What the car offers")
        let last = settings.setup.car(id: carId)?.scan
        ShellCard {
            if let last {
                let core = CarPageCore.shared
                NavigationLink { CarPidsPage(carId: carId) } label: {
                    ListRow(
                        "OBD-II", divider: false,
                        supporting: "\(core.reportedPids(scan: last).count) PIDs reported · scanned \(scanTime(last.atMs))",
                        chevron: true
                    )
                }
                .buttonStyle(.plain)
                NavigationLink { CarCanPage(carId: carId) } label: {
                    ListRow(
                        "CAN bus",
                        supporting: last.canHeard ? "\(last.canIds.count) ids heard" : "Not heard: \(core.noCanReason(scan: last))",
                        chevron: true
                    )
                }
                .buttonStyle(.plain)
            } else {
                ListRow(
                    "Not scanned yet", divider: false,
                    supporting: "Scan car lists the OBD-II PIDs it reports and the CAN ids at its port."
                )
            }
        }
        if chosen {
            ShellTonalButton(
                text: obd.scanning ? "Scanning…" : (last == nil ? "Scan car" : "Scan again"),
                enabled: obd.canScan && !obd.scanning
            ) { obd.scan() }
            .accessibilityIdentifier("scanCar")
            if let note = obd.scanNote { DimText(note) }
            DimText("Takes about 15 s with the engine on.")
        } else {
            DimText("Only the next session's car can be scanned: the adapter reads the car it is plugged into.")
        }
    }
}

/// When a scan ran, short: "today 14:02" or "28 Oct 14:02".
func scanTime(_ atMs: Int64, now: Date = Date()) -> String {
    let at = Date(timeIntervalSince1970: Double(atMs) / 1_000)
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_GB")
    f.dateFormat = "HH:mm"
    let time = f.string(from: at)
    if Calendar.current.isDate(at, inSameDayAs: now) { return "today \(time)" }
    f.dateFormat = "d MMM"
    return "\(f.string(from: at)) \(time)"
}

/// Adding or editing a car (UI-35): name and notes. [carId] is nil when adding. (An id, not the
/// car: setup's and obd's CarProfile share a name, so Swift sees one of them renamed.)
struct CarEditSheet: View {
    let carId: String?
    @EnvironmentObject private var settings: SettingsModel
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var notes = ""

    var body: some View {
        NavigationStack {
            ShellPage(title: carId == nil ? "Add car" : "Edit car") {
                TextField("Name", text: $name)
                    .textFieldStyle(.roundedBorder)
                    .accessibilityIdentifier("carName")
                TextField("Notes", text: $notes, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                DimText("The name goes into each session and its VBO export.")
                Button {
                    save()
                } label: {
                    Text("Save")
                        .fontWeight(.semibold)
                        .foregroundStyle(Palette.onAccent)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(Palette.accent.opacity(name.trimmingCharacters(in: .whitespaces).isEmpty ? 0.4 : 1),
                                    in: Capsule())
                }
                .buttonStyle(.plain)
                .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                if let carId {
                    if settings.setup.session.carId != carId {
                        ShellTonalButton(text: "Use for the next session") {
                            settings.update { $0.chooseCar(id: carId) }
                            dismiss()
                        }
                    }
                    RemoveButton(text: "Remove car") {
                        settings.update { $0.removeCar(id: carId) }
                        dismiss()
                    }
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
        }
        .onAppear {
            let car = carId.flatMap { settings.setup.car(id: $0) }
            name = car?.name ?? ""
            notes = car?.notes ?? ""
        }
        .presentationBackground(Palette.background)
    }

    private func save() {
        if let carId {
            settings.update { SettingsCore.shared.updateCar(setup: $0, id: carId, name: name, notes: notes) }
        } else {
            let id = SettingsModel.newId()
            settings.update { SettingsCore.shared.addCar(setup: $0, name: name, notes: notes, id: id) }
        }
        dismiss()
    }
}
