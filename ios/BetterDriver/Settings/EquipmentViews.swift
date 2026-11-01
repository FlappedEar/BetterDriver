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
                    ShellLabel(groupLabel(kind.name))
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
    case ModelName.goPro: "Starts and stops with each session it is turned on for."
    case ModelName.goProFiles: "Start it by hand. Add its videos to the session afterwards."
    case ModelName.cameraFiles:
        "Start it by hand. Add its MP4 videos to the session afterwards; they are lined up by the camera's clock."
    case ModelName.demoTrack:
        "A made-up car drives what Drives says: the Demo circuit, or one of your sessions again. " +
            "Laps, sectors and coach cues without a track day. Its positions are not real data."
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
    @State private var choosingDrive = false
    /** The Demo track's current drive (FBBD-373), read when the page opens and after a pick. */
    @State private var driveLabel = ""

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
                    // Android's RaceBox diagnostics (FBBD-89) and OBD page (FBBD-261).
                    if item.modelName == ModelName.raceBox {
                        NavigationLink { RaceBoxDiagnosticsPage(item: item) } label: {
                            ListRow("Diagnostics", value: "Live", chevron: true)
                        }
                        .buttonStyle(.plain)
                    }
                    if item.kindName == KindName.carData {
                        NavigationLink { ObdPage() } label: {
                            ListRow("Connection and live values", chevron: true)
                        }
                        .buttonStyle(.plain)
                    }
                    // What the Demo track drives (FBBD-235 on Android, FBBD-373).
                    if item.modelName == ModelName.demoTrack {
                        ListRow("Drives", value: driveLabel) { choosingDrive = true }
                            .accessibilityIdentifier("demoDrives")
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
            .sheet(isPresented: $choosingDrive, onDismiss: readDrive) { DemoDrivesSheet() }
            .onAppear(perform: readDrive)
        }
    }

    private func readDrive() {
        driveLabel = DemoTrackCore.shared.chosenLabel(filesDir: AppFolders.base.path)
    }
}

/**
 * What the Demo track drives (FBBD-373), Android's Drives dialog (FBBD-235): the demo circuit first,
 * then Arek's Silesia Ring and Jastrząb laps built into the app and the sessions newest first. A pick
 * chooses it and closes the sheet.
 */
struct DemoDrivesSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var choices: [DemoChoice] = []
    @State private var chosen: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Drives")
                .font(.title3.weight(.semibold))
                .foregroundStyle(Palette.onBackground)
                .padding(.bottom, 8)
            if choices.isEmpty {
                ProgressView().padding(.vertical, 12)
            }
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(Array(choices.enumerated()), id: \.offset) { i, choice in
                        let picked = choice.id == chosen
                        ListRow(
                            label: choice.label,
                            divider: i > 0,
                            action: {
                                DemoTrackCore.shared.choose(filesDir: AppFolders.base.path, id: choice.id)
                                dismiss()
                            },
                            trailing: {
                                Image(systemName: picked ? "largecircle.fill.circle" : "circle")
                                    .foregroundStyle(picked ? Palette.accent : Palette.onSurfaceVariant)
                            }
                        )
                        .accessibilityIdentifier("demoDrive.\(choice.id ?? "circuit")")
                    }
                }
            }
            Button("Cancel") { dismiss() }
                .tint(Palette.accent)
                .padding(.vertical, 12)
        }
        .padding(.horizontal, 20)
        .padding(.top, 22)
        .presentationDetents([.medium, .large])
        .presentationBackground(Palette.surface)
        .task {
            let sessions = AppFolders.sessions.path
            let base = AppFolders.base.path
            // Reads every session's meta: off the main queue.
            let list = await Task.detached { DemoTrackCore.shared.choices(sessionsDir: sessions) }.value
            chosen = DemoTrackCore.shared.current(choices: list, id: DemoTrackCore.shared.chosen(filesDir: base)).id
            choices = list
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

/// Add equipment (UI-34 B): a Bluetooth search first, listing the RaceBoxes, Open GoPro cameras
/// (HERO9 and later), heart-rate monitors and Bluetooth LE OBD-II adapters heard nearby that are not
/// listed yet, then the phone's own GPS and camera when they are not listed. A GoPro is paired first,
/// which needs it in pairing mode, and tells the app which camera it is (FBBD-343), as on Android.
struct AddEquipmentPage: View {
    @EnvironmentObject private var settings: SettingsModel
    @Environment(\.dismiss) private var dismiss
    @StateObject private var scanner = NearbyScanner()
    /// The GoPro being paired, which takes a moment, and why the last pairing failed.
    @State private var pairing: String?
    @State private var pairingError: String?
    @State private var pairingLink: GoProBleLink?
    // A GoPro started by hand is added by generation, picked in a dialog (FBBD-105), as on Android.
    @State private var pickingGoPro = false

    var body: some View {
        let listed = Set(settings.setup.equipment.compactMap(\.address))
        let nearby = scanner.found.values.filter { !listed.contains($0.address) }.sorted { $0.rssi > $1.rssi }
        ShellPage(title: "Add equipment") {
            ShellCard {
                ShellLabel("Found nearby")
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
                            Button(pairing == d.address ? "Pairing…" : "Add") { addNearby(d) }
                                .buttonStyle(.bordered)
                                .tint(Palette.onBackground)
                                .disabled(pairing != nil)
                        }
                    )
                }
            }
            DimText(
                scanner.searching
                    ? "Searching for RaceBox, GoPro HERO9 or later, heart-rate monitors and Bluetooth LE OBD-II " +
                        "adapters. Put a GoPro in pairing mode first."
                    : "Allow Bluetooth for BetterDriver in the iPhone's Settings to search for a RaceBox or GoPro."
            )
            if let pairingError {
                Text(pairingError)
                    .font(.footnote)
                    .foregroundStyle(TrackColors.loss)
                    .accessibilityIdentifier("pairingError")
            }
            let without = SettingsCore.shared.withoutBluetooth(setup: settings.setup)
            if !without.isEmpty {
                ShellLabel("Add without Bluetooth")
                ShellCard {
                    ForEach(Array(without.enumerated()), id: \.element.name) { i, m in
                        ListRow(m.label, value: withoutNote(m.name), divider: i > 0) {
                            if m.name == ModelName.goProFiles {
                                pickingGoPro = true
                            } else {
                                add(m.name, name: m.label, address: nil)
                            }
                        }
                        .accessibilityIdentifier("add.\(m.name)")
                    }
                }
            }
            // The demo receiver in its own group, last, as on Android (UI-79 B, FBBD-373).
            let demos = SettingsCore.shared.tryTheApp(setup: settings.setup)
            if !demos.isEmpty {
                ShellLabel("Try the app")
                ShellCard {
                    ForEach(Array(demos.enumerated()), id: \.element.name) { i, m in
                        ListRow(m.label, value: withoutNote(m.name), divider: i > 0) {
                            add(m.name, name: m.label, address: nil)
                        }
                        .accessibilityIdentifier("add.\(m.name)")
                    }
                }
            }
        }
        .onAppear { scanner.start() }
        .onDisappear { scanner.stop() }
        .confirmationDialog("Which GoPro", isPresented: $pickingGoPro, titleVisibility: .visible) {
            ForEach(GoProCore.shared.fileImportModels, id: \.name) { m in
                Button(GoProCore.shared.choiceLabel(model: m)) {
                    let id = SettingsModel.newId()
                    settings.update { SettingsCore.shared.addGoProFiles(setup: $0, goPro: m, id: id) }
                    dismiss()
                }
            }
            Button("Cancel", role: .cancel) {}
        }
    }

    private func withoutNote(_ model: String) -> String {
        switch model {
        case ModelName.phoneGps: "This phone's own GPS, about 1 position a second"
        case ModelName.goProFiles: "HERO8 or older, MAX"
        case ModelName.cameraFiles: "DJI Action, Insta360, Garmin and others"
        case ModelName.demoTrack: "Laps and coach cues on a made-up circuit"
        case ModelName.obdWifi: "192.168.0.10 : 35000"
        default: "Built in"
        }
    }

    private func addNearby(_ d: NearbyDevice) {
        guard d.model == ModelName.goPro else {
            add(d.model, name: d.name, address: d.address)
            return
        }
        pairing = d.address
        pairingError = nil
        let link = GoProBleLink(address: d.address)
        pairingLink = link
        OpenGoPros.companion.pair(link: link) { paired, error in
            pairing = nil
            pairingLink = nil
            guard let paired else {
                pairingError = error ?? "Pairing failed"
                return
            }
            let id = SettingsModel.newId()
            settings.update {
                SettingsCore.shared.addOpenGoPro(setup: $0, name: d.name, address: d.address, goPro: paired.model, id: id)
            }
            AppLog.i("GoPro", "paired \(paired.modelName) as \(paired.model.name)")
            dismiss()
        }
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
        if rssi >= -60 { return 4 }
        if rssi >= -70 { return 3 }
        if rssi >= -80 { return 2 }
        return rssi >= -90 ? 1 : 0
    }

    var note: String? {
        if model == ModelName.obdBluetooth { return "OBD-II adapter · Bluetooth LE" }
        return model == ModelName.heartRate ? "Heart-rate monitor" : nil
    }
}

/// Listens for RaceBoxes, Open GoPro cameras (service 0xFEA6, as Android's GoProScanner), heart-rate
/// monitors (service 0x180D) and OBD-II adapters (by name, as Android's ObdAdapterNames) while Add
/// equipment is open.
@MainActor
final class NearbyScanner: NSObject, ObservableObject, CBCentralManagerDelegate {
    @Published private(set) var found: [String: NearbyDevice] = [:]
    @Published private(set) var searching = false
    private var central: CBCentralManager?
    /// True while Add equipment is open: a Bluetooth restart resumes the scan only then.
    private var wanted = false

    private static let heartRate = CBUUID(string: "180D")
    private static let goPro = CBUUID(string: GoProUuids.shared.CONTROL_SERVICE)
    private static let obdParts = [
        "obd", "elm327", "vlink", "v-link", "vgate", "icar", "konnwei", "viecar", "veepeak", "carista",
        "kiwi", "unicarscan", "tonwon",
    ]

    func start() {
        wanted = true
        if central == nil { central = CBCentralManager(delegate: self, queue: nil) }
        scan()
    }

    func stop() {
        wanted = false
        central?.stopScan()
        searching = false
    }

    private func scan() {
        guard wanted, let central, central.state == .poweredOn else {
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
        } else if services.contains(NearbyScanner.goPro) {
            model = ModelName.goPro
        } else if services.contains(NearbyScanner.heartRate) {
            model = ModelName.heartRate
        } else if NearbyScanner.obdParts.contains(where: { name.lowercased().contains($0) }) {
            model = ModelName.obdBluetooth
        } else {
            return
        }
        let device = NearbyDevice(
            model: model, name: name.isEmpty ? (model == ModelName.goPro ? "GoPro" : "Heart-rate monitor") : name,
            address: peripheral.identifier.uuidString, rssi: RSSI.intValue
        )
        MainActor.assumeIsolated { found[device.address] = device }
    }
}
