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

/// A car's page (UI-133 B), titled with its name; Edit opens its name and notes. The next session's
/// car lists the OBD-II adapters that read it; another car offers to become the next session's.
struct CarPage: View {
    let id: String
    let showsBack: Bool
    @EnvironmentObject private var settings: SettingsModel
    @State private var editing = false

    var body: some View {
        let setup = settings.setup
        if let car = setup.car(id: id) {
            let chosen = setup.session.carId == car.id
            ShellPage(title: car.name, large: !showsBack) {
                let line = [car.notes, chosen ? "next session" : ""].filter { !$0.isEmpty }
                if !line.isEmpty { DimText(line.joined(separator: " · ")) }
                if chosen {
                    let adapters = setup.equipment.filter { $0.kindName == KindName.carData }
                    ShellCard {
                        if adapters.isEmpty {
                            ListRow("OBD-II adapter", value: "None added", divider: false)
                        }
                        ForEach(Array(adapters.enumerated()), id: \.element.id) { i, a in
                            ListRow(a.name, value: "Read while recording", divider: i > 0, supporting: "OBD-II adapter")
                        }
                    }
                    DimText("The adapter reads the car in every session while it is listed. Add one in Settings › Equipment.")
                } else {
                    DimText("Live values show on the next session's car: the adapter reads the car it is plugged into.")
                    ShellTonalButton(text: "Use for the next session") { settings.update { $0.chooseCar(id: car.id) } }
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Edit") { editing = true }.tint(Palette.accent)
                }
            }
            .sheet(isPresented: $editing) { CarEditSheet(carId: car.id) }
        }
    }
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
