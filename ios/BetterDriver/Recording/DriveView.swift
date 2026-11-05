import BetterDriverCore
import CoreLocation
import SwiftUI

/// The Drive tab on the iPhone (UI-282 B; cards since UI-308 A, FBBD-421), as Android's Drive: a large
/// title, the amber START ring with the place the phone is at, then Pinned cards in the session
/// summary's style: what to work on next time here and how the last day here went (each only with
/// data, worked out by the shared core), and the devices this session uses. START opens the sheet of
/// receivers nearby (UI-285 B); picking one starts the session and the track screen.
struct DriveView: View {
    @EnvironmentObject private var recorder: RecordingController
    @EnvironmentObject private var settings: SettingsModel
    @StateObject private var place = PlaceFinder()
    @State private var picking = false
    /// The receiver picked in START's sheet: the session starts once the sheet has gone (FBBD-456).
    @State private var pendingStart: Receiver?
    /// The track screen is on screen; follows the recording, and START brings it back if it was dropped.
    @State private var trackShown = false
    /// START and Change taps, for the UI tests' failure text (FBBD-456).
    @State private var taps = 0
    @State private var camera = true
    @State private var videoMode = PhoneVideoMode.standard
    @State private var pinned = DrivePinned(focus: nil, lastTime: nil)

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 10) {
                    StartButton(place: place.name, state: "taps \(taps), sheet \(picking ? "asked" : "not asked"), track \(trackShown ? "shown" : "not shown"), \(recorder.isRecording ? "recording" : "not recording")") { openSheet("START") }
                        .padding(.vertical, 4)
                    if let error = recorder.error {
                        Text(error)
                            .font(.footnote)
                            .foregroundStyle(TrackColors.loss)
                            .multilineTextAlignment(.center)
                            .accessibilityIdentifier("recordingError")
                    }
                    HealthSectionTitle("Pinned").padding(.top, 4)
                    if let focus = pinned.focus { NextSessionHereCard(focus: focus, track: place.name) }
                    if let last = pinned.lastTime { LastTimeHereCard(last: last) }
                    ThisSessionCard(setup: settings.setup, receiver: recorder.lastReceiverName, phoneCamera: camera) {
                        openSheet("Change")
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 16)
                .frame(maxWidth: 700)
                .frame(maxWidth: .infinity)
            }
            .background(Palette.background)
            // The track screen on another view than START's sheet: one view presenting both can leave
            // the sheet unable to open again after a session (FBBD-456).
            .fullScreenCover(isPresented: $trackShown) {
                TrackScreenView(model: recorder.screen) { recorder.stop() }
            }
            .navigationTitle("Drive")
            .navigationBarTitleDisplayMode(.large)
        }
        // The session starts once the sheet has gone, so the track screen is never presented while the
        // sheet is still going away, which can drop it (FBBD-456).
        .sheet(isPresented: $picking, onDismiss: startPicked) {
            ReceiverSheet(lastUsed: recorder.lastReceiverName, camera: $camera, videoMode: $videoMode) { receiver in
                recorder.phoneCamera = camera
                recorder.phoneVideoMode = videoMode
                pendingStart = receiver
                picking = false
            }
            // Full height in the UI test, so every row and the camera toggle are on screen.
            .presentationDetents(UITestMode.on ? [.large] : [.medium, .large])
            // On the iPad a tap beside the sheet closes it; a UI test's taps must not.
            .interactiveDismissDisabled(UITestMode.on)
            .presentationBackground(Palette.surface)
        }
        // A session that ends leaves no sheet asked for, so START opens it again (FBBD-456).
        .onChange(of: recorder.isRecording) { _, recording in
            if !recording { picking = false }
            trackShown = recording
        }
        .onAppear {
            trackShown = recorder.isRecording
            camera = recorder.phoneCamera
            videoMode = recorder.phoneVideoMode
            // The UI test runs without location access and its prompt.
            if !UITestMode.on { place.find() }
        }
        // Read again at a new place and after each session, off the main thread (it reads the sessions' laps).
        .task(id: "\(place.trackId ?? "")|\(place.name ?? "")|\(recorder.isRecording)") {
            guard !recorder.isRecording else { return }
            let (id, name) = (place.trackId, place.name)
            pinned = await Task.detached(priority: .utility) {
                ReviewBridge.library.drivePinned(trackId: id, track: name)
            }.value
        }
    }

    /// Starts the session picked in START's sheet, now that the sheet has gone.
    private func startPicked() {
        guard let receiver = pendingStart else { return }
        pendingStart = nil
        recorder.start(receiver)
        // The track screen follows the recording; set here too in case the change came before this view saw it.
        trackShown = recorder.isRecording
    }

    /// Opens START's sheet. When the sheet is already asked for but not on screen (a session ended
    /// while it was going away), it is asked for once more, so a tap never does nothing (FBBD-456).
    private func openSheet(_ from: String) {
        taps += 1
        AppLog.i("Recording", "\(from) tapped: sheet \(picking ? "already asked, asked again" : "asked"), recording \(recorder.isRecording)")
        // Recording with the track screen not on screen: START brings it back instead of the sheet.
        if recorder.isRecording {
            AppLog.w("Recording", "track screen was not on screen while recording: shown again")
            trackShown = false
            DispatchQueue.main.async { trackShown = true }
            return
        }
        guard picking else {
            picking = true
            return
        }
        picking = false
        DispatchQueue.main.async { picking = true }
    }
}

/// "Next session here": the corners the last session here left to work on, and their time a lap.
private struct NextSessionHereCard: View {
    let focus: DriveFocus
    let track: String?

    var body: some View {
        HealthCard(title: "Next session here", icon: .coach, trailing: track) {
            HealthValue(value: DrivePinnedKt.focusCornersText(focus: focus), size: 22)
            HealthDetail(DrivePinnedKt.focusDetailText(focus: focus))
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("driveNextSession")
    }
}

/// "Last time here": the last day's best lap in purple, and each session's best as a bar.
private struct LastTimeHereCard: View {
    let last: LastTimeHere

    var body: some View {
        HealthCard(title: "Last time here", icon: .laps, trailing: driveDayLabel(last.epochDay)) {
            HealthValueRow {
                HealthValue(value: CoreBridge.lapTime(millis: last.bestMillis), color: Palette.best)
                HealthDetail(DrivePinnedKt.lastTimeDetailText(last: last))
            } chart: {
                HealthMiniBars(values: last.sessionBests.map { Double(truncating: $0) })
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("driveLastTime")
    }
}

/// "Today", "Yesterday", else "Sun 19 Oct", as Android's day label, for a day counted from 1970 here.
private func driveDayLabel(_ epochDay: Int64) -> String {
    let now = Date()
    let offset = Int64(TimeZone.current.secondsFromGMT(for: now))
    let today = Int64(floor(Double(Int64(now.timeIntervalSince1970) + offset) / 86_400))
    if epochDay == today { return "Today" }
    if epochDay == today - 1 { return "Yesterday" }
    let format = DateFormatter()
    format.timeZone = TimeZone(identifier: "UTC")
    format.setLocalizedDateFormatFromTemplate("EEE d MMM")
    return format.string(from: Date(timeIntervalSince1970: Double(epochDay) * 86_400))
}

/// The devices this session uses (UI-308 A): how many and their names, and the cameras, with Change,
/// which opens START's sheet, where iOS picks the receiver and the cameras. Names, not whether they answer.
private struct ThisSessionCard: View {
    @EnvironmentObject private var recorder: RecordingController
    let setup: Setup
    let receiver: String
    let phoneCamera: Bool
    let change: () -> Void

    var body: some View {
        let names = deviceNames
        HealthCard(title: "This session", icon: .device, trailing: setup.active().car?.name ?? "No car") {
            HStack(alignment: .center, spacing: 10) {
                VStack(alignment: .leading, spacing: 2) {
                    HealthValue(value: "\(names.count)", unit: SessionDevicesKt.devicesUnit(count: Int32(names.count)))
                    if !names.isEmpty { HealthDetail(names.joined(separator: " · ")) }
                    HealthDetail(SessionDevicesKt.camerasLine(cameras: cameras))
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if !recorder.isRecording {
                    Button("Change", action: change)
                        .foregroundStyle(Palette.accent)
                        .accessibilityIdentifier("driveChange")
                }
            }
        }
        .accessibilityIdentifier("driveThisSession")
    }

    /// The shared list, with the receiver used last and the phone camera when START's sheet turned it on.
    private var deviceNames: [String] {
        var names = SessionDevicesKt.sessionDeviceNames(setup: setup, receiver: receiver.isEmpty ? nil : receiver)
        if phoneCamera && !names.contains("Phone camera") { names.append("Phone camera") }
        return names
    }

    /// The phone camera as START's sheet sets it, then each Open GoPro camera with its switch.
    private var cameras: [CameraSwitch] {
        [CameraSwitch(name: "Phone camera", on: phoneCamera)] +
            OpenGoPros.companion.listed(setup: setup).map { CameraSwitch(name: $0.name, on: $0.on) }
    }
}

/// The 170 point amber START ring with the place the phone is at (UI-308 A, as on Android).
private struct StartButton: View {
    let place: String?
    /// Taps, sheet and track screen state, as the button's value for the UI tests (FBBD-456).
    let state: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Text("START").font(TrackFonts.number(38))
                if let place {
                    Text(place).font(TrackFonts.text(14)).lineLimit(1).padding(.horizontal, 20)
                }
            }
            .foregroundStyle(Palette.onAccent)
            .frame(width: 154, height: 154)
            .background(Circle().fill(Palette.accent))
            .padding(8)
            .overlay(Circle().stroke(Palette.accent.opacity(0.25), lineWidth: 8).padding(4))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("startButton")
        .accessibilityValue(state)
    }
}

/// The list at START (FBBD-446, UI-311 A, as Android's): the car, then each group in the same rows. GPS
/// receiver: RaceBoxes in range as they advertise (the one used last on top and picked), the other
/// receivers listed in Settings › Equipment and the phone's own GPS, one of them; the cameras, each with
/// its switch; then car data, heart rate and tyres from the shared list (StartList), one of each or
/// None. The choice is kept for the next session (UI-313 A). Start session starts with the picked receiver.
struct ReceiverSheet: View {
    @EnvironmentObject private var settings: SettingsModel
    let lastUsed: String
    @Binding var camera: Bool
    @Binding var videoMode: PhoneVideoMode
    let onPick: (Receiver) -> Void

    @State private var found: [UUID: FoundRaceBoxDevice] = [:]
    @State private var scanner: RaceBoxScanner?
    @State private var modes: [PhoneVideoMode] = [PhoneVideoMode.standard]
    @State private var picked: Receiver?
    /// The RaceBoxes the app holds connected (HeldConnections).
    @State private var heldIds: Set<UUID> = []

    private var devices: [FoundRaceBoxDevice] {
        found.values.sorted { a, b in
            if (a.name == lastUsed) != (b.name == lastUsed) { return a.name == lastUsed }
            return a.rssi > b.rssi
        }
    }

    /// Car data, heart rate and tyres: the groups the shared list has an item in.
    private var sensorGroups: [StartGroup] {
        StartListKt.startGroups(setup: settings.setup).filter {
            $0.kind == StartKind.carData || $0.kind == StartKind.heartRate || $0.kind == StartKind.tpms
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("This session")
                        .font(.title3.weight(.semibold))
                        .padding(.bottom, 6)
                        // On the title only: on the whole sheet it would replace every row's own id.
                        .accessibilityIdentifier("receiverSheet")
                    carRow
                    groupTitle("GPS receiver")
                    receivers
                    groupTitle("Cameras")
                    cameras
                    ForEach(sensorGroups, id: \.kind) { group in
                        groupTitle(group.title)
                        ForEach(group.rows, id: \.id) { row in
                            pickRow(row.name, row.detail, on: row.on) { choose(group.kind, row.id) }
                                .accessibilityIdentifier("start.\(group.kind.name).\(row.name)")
                        }
                        pickRow("None", "", on: group.noneOn) { choose(group.kind, nil) }
                            .accessibilityIdentifier("start.\(group.kind.name).none")
                    }
                }
            }
            Button {
                if let picked { onPick(picked) }
            } label: {
                Text("Start session")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(RoundedRectangle(cornerRadius: 14).fill(picked == nil ? Palette.outline : Palette.accent))
                    .foregroundStyle(Palette.onAccent)
            }
            .buttonStyle(.plain)
            .disabled(picked == nil)
            .padding(.top, 10)
            .accessibilityIdentifier("startSession")
        }
        .padding(.horizontal, 20)
        .padding(.top, 22)
        .padding(.bottom, 12)
        .foregroundStyle(Palette.onBackground)
        .onAppear {
            let supported = PhoneVideoMode.supported()
            modes = supported.contains(videoMode) ? supported : supported + [videoMode]
            let s = RaceBoxScanner { device in
                found[device.id] = device
                // The RaceBox used last is picked as it comes into range, as the list's top row.
                if picked == nil, device.name == lastUsed { picked = .raceBox(device) }
            }
            scanner = s
            s.start()
            // A listed receiver used last is picked at once (UI-313 A).
            if picked == nil { picked = lastNotRaceBox }
        }
        .onDisappear { scanner?.stop() }
        // A RaceBox the app holds connected no longer advertises: the held link lists it (Arek 2026-11-05).
        .onReceive(HeldConnections.shared.$connected) { held in
            for (id, name) in held where FoundRaceBox.companion.isRaceBox(name: name) {
                heldIds.insert(id)
                if found[id] == nil { found[id] = FoundRaceBoxDevice(id: id, name: name, rssi: 0) }
                if picked == nil, name == lastUsed, let device = found[id] { picked = .raceBox(device) }
            }
        }
        // The receiver picked is connected and held from the moment it is picked.
        .onChange(of: picked) { _, receiver in
            if let receiver { HeldConnections.shared.choose(receiver) }
        }
    }

    /// Under a RaceBox's name: connected (held by the app) or in range, and whether it was used last.
    private func rowDetail(_ device: FoundRaceBoxDevice) -> String {
        let state = heldIds.contains(device.id) ? "Connected" : "In range"
        return device.name == lastUsed ? "\(state) · last used" : state
    }

    /// The car, chosen as in Settings › Cars.
    private var carRow: some View {
        let cars = settings.setup.cars
        let current = settings.setup.active().car?.name ?? (cars.isEmpty ? "No car added" : "None")
        return Menu {
            ForEach(cars, id: \.id) { car in
                Button(car.name) { settings.update { $0.chooseCar(id: car.id) } }
            }
            Button("None") { settings.update { $0.chooseCar(id: nil) } }
        } label: {
            HStack {
                Text("Car")
                Spacer()
                Text(current).foregroundStyle(Palette.onSurfaceVariant)
            }
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("start.car")
    }

    /// The receiver from Settings › Equipment used last (the recorder keeps a RaceBox's or a listed
    /// receiver's name); a RaceBox is picked only once it is in range.
    private var lastNotRaceBox: Receiver? {
        if lastUsed.isEmpty { return nil }
        guard let item = settings.setup.equipment.first(where: { ListedReceivers.models.contains($0.modelName) && $0.name == lastUsed }) else { return nil }
        return .listed(item)
    }

    @ViewBuilder
    private var receivers: some View {
        // The UI test's receiver, on top where the sheet's first height shows it.
        if UITestMode.on {
            pickRow("Simulated GPS", "UI test", on: picked == .simulated) { picked = .simulated }
                .accessibilityIdentifier("receiver.simulated")
        }
        ForEach(devices) { device in
            pickRow(device.name, rowDetail(device), on: picked == .raceBox(device)) {
                picked = .raceBox(device)
            }
        }
        if found.isEmpty {
            HStack(spacing: 10) {
                ProgressView()
                Text("Looking for RaceBoxes").foregroundStyle(Palette.onSurfaceVariant)
            }
            .padding(.vertical, 12)
        }
        // Wi-Fi GPS, Bluetooth LE GPS and RaceChrono DIY receivers added in Settings › Equipment (FBBD-374).
        ForEach(settings.setup.equipment.filter { ListedReceivers.models.contains($0.modelName) }, id: \.id) { item in
            pickRow(item.name, ListedReceivers.detail(item), on: picked == .listed(item)) { picked = .listed(item) }
                .accessibilityIdentifier("receiver.\(item.modelName)")
        }
        pickRow("Phone GPS only", "1 Hz", on: picked == .phone) { picked = .phone }
        // The Demo track once added in Settings › Equipment, with what it drives (FBBD-373).
        if settings.setup.equipment.contains(where: { $0.modelName == ModelName.demoTrack }) {
            pickRow("Demo track", DemoTrackCore.shared.chosenLabel(filesDir: AppFolders.base.path), on: picked == .demoTrack) {
                picked = .demoTrack
            }
            .accessibilityIdentifier("receiver.demoTrack")
        }
    }

    @ViewBuilder
    private var cameras: some View {
        // Phone video with the session (FBBD-21), as the phone camera in Android's equipment list.
        Toggle(isOn: $camera) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Phone camera")
                Text(videoMode.label).font(.footnote).foregroundStyle(Palette.onSurfaceVariant)
            }
        }
        .tint(Palette.accent)
        .padding(.vertical, 8)
        .overlay(alignment: .top) { Rectangle().fill(Palette.outline).frame(height: 1) }
        .accessibilityIdentifier("phoneCameraToggle")
        if camera {
            // Only modes this phone records, as Android's video dialog (FBBD-88).
            HStack {
                Text("Video")
                Spacer()
                // The chosen mode is listed from the first draw, before onAppear reads what the phone records.
                Picker("Video", selection: $videoMode) {
                    ForEach(modes.contains(videoMode) ? modes : modes + [videoMode]) { Text($0.label).tag($0) }
                }
                .pickerStyle(.menu)
                .tint(Palette.accent)
            }
            .padding(.vertical, 6)
            .accessibilityIdentifier("phoneVideoMode")
        }
        // Each Open GoPro camera starts and stops with the session while on (FBBD-343).
        ForEach(OpenGoPros.companion.listed(setup: settings.setup), id: \.id) { goPro in
            Toggle(goPro.name, isOn: Binding(
                get: { goPro.on },
                set: { on in settings.update { SettingsCore.shared.useCamera(setup: $0, id: goPro.id, on: on) } }
            ))
            .tint(Palette.accent)
            .padding(.vertical, 12)
            .overlay(alignment: .top) { Rectangle().fill(Palette.outline).frame(height: 1) }
            .accessibilityIdentifier("goProToggle")
        }
    }

    private func choose(_ kind: StartKind, _ id: String?) {
        // A Kotlin extension of a Kotlin class is a method of that class in Swift.
        settings.update { $0.chooseAtStart(kind: kind, id: id, on: true) }
    }

    private func groupTitle(_ text: String) -> some View {
        Text(text.uppercased())
            .font(.caption.weight(.semibold))
            .foregroundStyle(Palette.onSurfaceVariant)
            .padding(.top, 14)
            .padding(.bottom, 2)
    }

    /// One row of a one-of group: the name, what it is or whether it is in range, and its radio.
    private func pickRow(_ name: String, _ detail: String, on: Bool, pick: @escaping () -> Void) -> some View {
        Button(action: pick) {
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(name)
                    if !detail.isEmpty {
                        Text(detail).font(.footnote).foregroundStyle(Palette.onSurfaceVariant)
                    }
                }
                Spacer()
                Image(systemName: on ? "largecircle.fill.circle" : "circle")
                    .foregroundStyle(on ? Palette.accent : Palette.onSurfaceVariant)
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
            .overlay(alignment: .top) { Rectangle().fill(Palette.outline).frame(height: 1) }
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// Names the track the phone is at for the START button, from one position: a saved track, else the
/// circuit directory (FBBD-79); nothing elsewhere or without location access.
@MainActor
final class PlaceFinder: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published private(set) var name: String?
    /// The saved track the phone is at, for Drive's Pinned cards (FBBD-421); nil at none.
    @Published private(set) var trackId: String?
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
        let id = TrackFiles.shared.savedTrackIdAt(lat: at.latitude, lon: at.longitude, tracks: tracks)
        Task { @MainActor in
            self.name = found
            self.trackId = id
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}
}

/// The receivers other than the RaceBox that a session can record from on the iPhone (FBBD-374), as
/// listed in Settings › Equipment. Bluetooth Classic ones (NMEA over SPP, VBOX Sport) cannot connect
/// on iOS, and the Dragy is not ported yet.
enum ListedReceivers {
    static let models: Set<String> = [ModelName.tcpGnss, ModelName.lnsGnss, ModelName.diyGnss]

    static func detail(_ item: Equipment) -> String {
        switch item.modelName {
        case ModelName.tcpGnss: "Wi-Fi · \(item.address ?? "")"
        case ModelName.diyGnss: "RaceChrono DIY"
        default: "Bluetooth LE"
        }
    }
}
