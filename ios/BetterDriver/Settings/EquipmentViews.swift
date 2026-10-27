import BetterDriverCore
import CoreBluetooth
import SwiftUI

/// Settings › Equipment (UI-33 B, UI-138 B): everything added, grouped by kind as on Android, and one
/// Add. A fresh install has only the phone camera.
struct EquipmentPage: View {
    @EnvironmentObject private var settings: SettingsModel

    var body: some View {
        ShellPage(title: "Equipment") {
            ForEach(SettingsCore.shared.groupOrder, id: \.name) { kind in
                let items = settings.setup.equipment.filter { $0.kindName == kind.name }
                if !items.isEmpty {
                    SectionLabel(groupLabel(kind.name))
                    ShellCard {
                        ForEach(Array(items.enumerated()), id: \.element.id) { i, item in
                            NavigationLink { EquipmentItemPage(id: item.id) } label: {
                                ListRow(item.name, divider: i > 0, supporting: kindLabel(item.kindName), chevron: true)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            ShellCard {
                NavigationLink { AddEquipmentPage() } label: {
                    Text("+ Add equipment")
                        .fontWeight(.semibold)
                        .foregroundStyle(Palette.accent)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, 12)
                }
                .accessibilityIdentifier("addEquipment")
            }
            DimText("Tap an item to rename or remove it.")
        }
    }
}

func groupLabel(_ kind: String) -> String {
    switch kind {
    case KindName.gnss: "GNSS receivers"
    case KindName.carData: "Car data"
    case KindName.sensor: "Sensors"
    default: "Cameras"
    }
}

func kindLabel(_ kind: String) -> String {
    switch kind {
    case KindName.gnss: "GNSS"
    case KindName.camera: "Camera"
    case KindName.sensor: "Sensor"
    default: "Car data"
    }
}

/// What a model does, under its details, as on Android.
private func modelNote(_ model: String) -> String {
    switch model {
    case ModelName.raceBox: "Records position, speed and G for each session it is chosen for."
    case ModelName.phoneGps:
        "Records position and speed from this phone's own GPS, about one position a second, for each session " +
            "it is chosen for. A RaceBox gives 25 and exact lap times."
    case ModelName.heartRate: "Records your heart rate with every session while it is listed."
    case ModelName.obdBluetooth, ModelName.obdWifi:
        "Reads the car in every session while it is listed. The Car page shows its values."
    case ModelName.phoneCamera: "Records video with each session it is turned on for."
    default: "Records position and speed for each session it is chosen for."
    }
}

/// One item (UI-33 B): its name (renamed in a dialog), model, how it is reached, the video mode of a
/// camera the app sets (UI-39), and Remove.
struct EquipmentItemPage: View {
    let id: String
    @EnvironmentObject private var settings: SettingsModel
    @Environment(\.dismiss) private var dismiss
    @State private var renaming = false
    @State private var newName = ""
    @State private var choosingVideo = false

    var body: some View {
        if let item = settings.setup.equipment(id: id) {
            ShellPage(title: item.name) {
                ShellCard {
                    ListRow("Name", value: item.name, divider: false) {
                        newName = item.name
                        renaming = true
                    }
                    if item.kindName == KindName.carData {
                        ListRow("Kind", value: "Car data")
                    } else {
                        ListRow("Model", value: item.modelLabel)
                    }
                    if item.address != nil {
                        ListRow("Bluetooth", value: "Bluetooth LE")
                    }
                    if let mode = item.videoMode {
                        ListRow("Video", value: mode.label) { choosingVideo = true }
                    }
                }
                DimText(modelNote(item.modelName))
                RemoveButton(text: "Remove") {
                    dismiss()
                    settings.update { $0.removeEquipment(id: id) }
                }
            }
            .alert("Rename", isPresented: $renaming) {
                TextField("Name", text: $newName)
                Button("Save") { settings.update { $0.renameEquipment(id: id, name: newName) } }
                Button("Cancel", role: .cancel) {}
            }
            .sheet(isPresented: $choosingVideo) { VideoModeSheet(item: item) }
        }
    }
}

/// Choose a camera's video mode (UI-39): one list, best first, the default marked.
struct VideoModeSheet: View {
    let item: Equipment
    @EnvironmentObject private var settings: SettingsModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Video · \(item.name)")
                .font(.title3.weight(.semibold))
                .foregroundStyle(Palette.onBackground)
                .padding(.bottom, 8)
            ForEach(Array(item.videoModes.enumerated()), id: \.element.label) { i, mode in
                let picked = settings.setup.equipment(id: item.id)?.videoMode == mode
                ListRow(
                    label: mode.label,
                    value: mode == item.defaultVideo ? "Recommended" : nil,
                    divider: i > 0,
                    action: {
                        settings.update { $0.chooseVideo(id: item.id, mode: mode) }
                        dismiss()
                    },
                    trailing: {
                        Image(systemName: picked ? "largecircle.fill.circle" : "circle")
                            .foregroundStyle(picked ? Palette.accent : Palette.onSurfaceVariant)
                    }
                )
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 20)
        .padding(.top, 22)
        .presentationDetents([.medium])
        .presentationBackground(Palette.surface)
    }
}

/// Add equipment (UI-34 B): a Bluetooth search first, listing the RaceBoxes, heart-rate monitors and
/// Bluetooth LE OBD-II adapters heard nearby that are not listed yet, then the phone's own GPS and
/// camera when they are not listed.
struct AddEquipmentPage: View {
    @EnvironmentObject private var settings: SettingsModel
    @Environment(\.dismiss) private var dismiss
    @StateObject private var scanner = NearbyScanner()

    var body: some View {
        let listed = Set(settings.setup.equipment.compactMap(\.address))
        let nearby = scanner.found.values.filter { !listed.contains($0.address) }.sorted { $0.rssi > $1.rssi }
        ShellPage(title: "Add equipment") {
            ShellCard {
                SectionLabel("Found nearby")
                if nearby.isEmpty {
                    HStack(spacing: 12) {
                        if scanner.searching { ProgressView() }
                        Text(scanner.searching ? "Searching…" : "Not searching").foregroundStyle(Palette.onBackground)
                    }
                    .padding(.vertical, 12)
                }
                ForEach(Array(nearby.enumerated()), id: \.element.address) { i, d in
                    ListRow(
                        label: d.name,
                        divider: i > 0,
                        supporting: d.note,
                        trailing: {
                            SignalBars(bars: d.bars)
                            Button("Add") { add(d.model, name: d.name, address: d.address) }
                                .buttonStyle(.bordered)
                                .tint(Palette.onBackground)
                        }
                    )
                }
            }
            DimText(
                scanner.searching
                    ? "Searching for RaceBox, heart-rate monitors and Bluetooth LE OBD-II adapters."
                    : "Allow Bluetooth for BetterDriver in the iPhone's Settings to search for a RaceBox."
            )
            let without = SettingsCore.shared.withoutBluetooth.filter { m in
                !settings.setup.equipment.contains { $0.modelName == m.name }
            }
            if !without.isEmpty {
                SectionLabel("Add without Bluetooth")
                ShellCard {
                    ForEach(Array(without.enumerated()), id: \.element.name) { i, m in
                        ListRow(m.label, value: withoutNote(m.name), divider: i > 0) {
                            add(m.name, name: m.label, address: nil)
                        }
                    }
                }
            }
        }
        .onAppear { scanner.start() }
        .onDisappear { scanner.stop() }
    }

    private func withoutNote(_ model: String) -> String {
        model == ModelName.phoneGps ? "This phone's own GPS, about 1 position a second" : "Built in"
    }

    private func add(_ model: String, name: String, address: String?) {
        guard let m = SettingsCore.shared.model(name: model) else { return }
        let id = SettingsModel.newId()
        settings.update { SettingsCore.shared.addEquipment(setup: $0, model: m, name: name, address: address, id: id) }
        dismiss()
    }
}

/// Signal strength as 0 to 4 bars.
struct SignalBars: View {
    let bars: Int

    var body: some View {
        HStack(alignment: .bottom, spacing: 2) {
            ForEach(0..<4, id: \.self) { i in
                RoundedRectangle(cornerRadius: 1)
                    .fill(i < bars ? Palette.onBackground : Palette.outline)
                    .frame(width: 3, height: CGFloat(5 + 3 * i))
            }
        }
    }
}

/// A Bluetooth LE device heard while adding equipment; [address] is the iPhone's identifier for it.
struct NearbyDevice {
    let model: String
    let name: String
    let address: String
    let rssi: Int

    /// The thresholds of the Android pairing screen.
    var bars: Int {
        switch rssi {
        case -60...: 4
        case -70...: 3
        case -80...: 2
        case -90...: 1
        default: 0
        }
    }

    var note: String? {
        switch model {
        case ModelName.obdBluetooth: "OBD-II adapter · Bluetooth LE"
        case ModelName.heartRate: "Heart-rate monitor"
        default: nil
        }
    }
}

/// Listens for RaceBoxes, heart-rate monitors (service 0x180D) and OBD-II adapters (by name, as
/// Android's ObdAdapterNames) while Add equipment is open.
@MainActor
final class NearbyScanner: NSObject, ObservableObject, CBCentralManagerDelegate {
    @Published private(set) var found: [String: NearbyDevice] = [:]
    @Published private(set) var searching = false
    private var central: CBCentralManager?

    private static let heartRate = CBUUID(string: "180D")
    private static let obdParts = [
        "obd", "elm327", "vlink", "v-link", "vgate", "icar", "konnwei", "viecar", "veepeak", "carista",
        "kiwi", "unicarscan", "tonwon",
    ]

    func start() {
        if central == nil { central = CBCentralManager(delegate: self, queue: nil) }
        scan()
    }

    func stop() {
        central?.stopScan()
        searching = false
    }

    private func scan() {
        guard let central, central.state == .poweredOn else {
            searching = false
            return
        }
        central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
        searching = true
    }

    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        MainActor.assumeIsolated { scan() }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any], rssi RSSI: NSNumber
    ) {
        let name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? peripheral.name ?? ""
        let services = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []
        let model: String
        if FoundRaceBox.companion.isRaceBox(name: name) {
            model = ModelName.raceBox
        } else if services.contains(NearbyScanner.heartRate) {
            model = ModelName.heartRate
        } else if NearbyScanner.obdParts.contains(where: { name.lowercased().contains($0) }) {
            model = ModelName.obdBluetooth
        } else {
            return
        }
        let device = NearbyDevice(
            model: model, name: name.isEmpty ? "Heart-rate monitor" : name,
            address: peripheral.identifier.uuidString, rssi: RSSI.intValue
        )
        MainActor.assumeIsolated { found[device.address] = device }
    }
}
