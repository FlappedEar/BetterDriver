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
    @State private var camera = true
    @State private var videoMode = PhoneVideoMode.standard
    @State private var pinned = DrivePinned(focus: nil, lastTime: nil)

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 10) {
                    StartButton(place: place.name) { picking = true }
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
                        picking = true
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 16)
                .frame(maxWidth: 700)
                .frame(maxWidth: .infinity)
            }
            .background(Palette.background)
            .navigationTitle("Drive")
            .navigationBarTitleDisplayMode(.large)
        }
        .sheet(isPresented: $picking) {
            ReceiverSheet(lastUsed: recorder.lastReceiverName, camera: $camera, videoMode: $videoMode) { receiver in
                recorder.phoneCamera = camera
                recorder.phoneVideoMode = videoMode
                picking = false
                recorder.start(receiver)
            }
            // Full height in the UI test, so every row and the camera toggle are on screen.
            .presentationDetents(UITestMode.on ? [.large] : [.medium, .large])
            // On the iPad a tap beside the sheet closes it; a UI test's taps must not.
            .interactiveDismissDisabled(UITestMode.on)
            .presentationBackground(Palette.surface)
        }
        .fullScreenCover(isPresented: Binding(get: { recorder.isRecording }, set: { _ in })) {
            TrackScreenView(model: recorder.screen) { recorder.stop() }
        }
        .onAppear {
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
    }
}

/// The sheet at START (UI-285 B): RaceBoxes in range as they advertise, the one used last on top,
/// and the phone's own GPS; then the cameras, each with its switch as on Android's "This session".
struct ReceiverSheet: View {
    @EnvironmentObject private var settings: SettingsModel
    let lastUsed: String
    @Binding var camera: Bool
    @Binding var videoMode: PhoneVideoMode
    let onPick: (Receiver) -> Void

    @State private var found: [UUID: FoundRaceBoxDevice] = [:]
    @State private var scanner: RaceBoxScanner?
    @State private var modes: [PhoneVideoMode] = [PhoneVideoMode.standard]

    private var devices: [FoundRaceBoxDevice] {
        found.values.sorted { a, b in
            if (a.name == lastUsed) != (b.name == lastUsed) { return a.name == lastUsed }
            return a.rssi > b.rssi
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("GPS receiver for this session")
                .font(.title3.weight(.semibold))
                .padding(.bottom, 6)
                // On the title only: on the whole sheet it would replace every row's own id.
                .accessibilityIdentifier("receiverSheet")
            // The UI test's receiver, on top where the sheet's first height shows it.
            if UITestMode.on {
                row("Simulated GPS", "UI test", ok: true) { onPick(.simulated) }
                    .accessibilityIdentifier("receiver.simulated")
            }
            ForEach(devices) { device in
                row(device.name, device.name == lastUsed ? "Nearby · last used" : "Nearby", ok: true) {
                    onPick(.raceBox(device))
                }
            }
            if found.isEmpty {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("Looking for RaceBoxes").foregroundStyle(Palette.onSurfaceVariant)
                }
                .padding(.vertical, 12)
            }
            row("Phone GPS only", "1 Hz", ok: false) { onPick(.phone) }
            // The Demo track once added in Settings › Equipment, with what it drives (FBBD-373).
            if settings.setup.equipment.contains(where: { $0.modelName == ModelName.demoTrack }) {
                row("Demo track", DemoTrackCore.shared.chosenLabel(filesDir: AppFolders.base.path), ok: true) {
                    onPick(.demoTrack)
                }
                .accessibilityIdentifier("receiver.demoTrack")
            }
            // Phone video with the session (FBBD-21), as the phone camera in Android's equipment list.
            Toggle("Phone camera video", isOn: $camera)
                .tint(Palette.accent)
                .padding(.vertical, 12)
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
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 20)
        .padding(.top, 22)
        .foregroundStyle(Palette.onBackground)
        .onAppear {
            let supported = PhoneVideoMode.supported()
            modes = supported.contains(videoMode) ? supported : supported + [videoMode]
            let s = RaceBoxScanner { device in found[device.id] = device }
            scanner = s
            s.start()
        }
        .onDisappear { scanner?.stop() }
    }

    private func row(_ name: String, _ detail: String, ok: Bool, pick: @escaping () -> Void) -> some View {
        Button(action: pick) {
            HStack(spacing: 10) {
                Circle().fill(ok ? TrackColors.gain : Palette.onSurfaceVariant).frame(width: 9, height: 9)
                Text(name)
                Spacer()
                Text(detail).font(.footnote).foregroundStyle(Palette.onSurfaceVariant)
            }
            .padding(.vertical, 12)
            .contentShape(Rectangle())
            .overlay(alignment: .top) { Rectangle().fill(Palette.outline).frame(height: 1) }
        }
        .buttonStyle(.plain)
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
