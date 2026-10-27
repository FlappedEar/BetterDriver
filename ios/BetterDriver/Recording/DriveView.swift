import BetterDriverCore
import CoreLocation
import SwiftUI

/// The Drive tab on the iPhone (UI-282 B): the Android app's amber START under a large title. START
/// opens the sheet of receivers nearby (UI-285 B); picking one starts the session and the track screen.
struct DriveView: View {
    @EnvironmentObject private var recorder: RecordingController
    @StateObject private var place = PlaceFinder()
    @State private var picking = false
    @State private var camera = true
    @State private var videoMode = PhoneVideoMode.standard

    var body: some View {
        NavigationStack {
            VStack(spacing: 18) {
                Spacer()
                StartButton(place: place.name) { picking = true }
                if !recorder.lastReceiverName.isEmpty {
                    Chip(text: recorder.lastReceiverName)
                }
                if let error = recorder.error {
                    Text(error)
                        .font(.footnote)
                        .foregroundStyle(TrackColors.loss)
                        .multilineTextAlignment(.center)
                        .accessibilityIdentifier("recordingError")
                }
                Spacer()
            }
            .padding(.horizontal, 18)
            .frame(maxWidth: .infinity)
            .background(Palette.background)
            .navigationTitle("Drive")
        }
        .sheet(isPresented: $picking) {
            ReceiverSheet(lastUsed: recorder.lastReceiverName, camera: $camera, videoMode: $videoMode) { receiver in
                recorder.phoneCamera = camera
                recorder.phoneVideoMode = videoMode
                picking = false
                recorder.start(receiver)
            }
            .presentationDetents([.medium, .large])
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
    }
}

/// The 230 point amber START with the place the phone is at (UI-282 B, as on Android).
private struct StartButton: View {
    let place: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Text("START").font(TrackFonts.number(44))
                if let place {
                    Text(place).font(TrackFonts.text(15)).lineLimit(1).padding(.horizontal, 24)
                }
            }
            .foregroundStyle(Palette.onAccent)
            .frame(width: 230, height: 230)
            .background(Circle().fill(Palette.accent))
            .padding(6)
            .overlay(Circle().stroke(Palette.accent, lineWidth: 2))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("startButton")
    }
}

private struct Chip: View {
    let text: String

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(Palette.onSurfaceVariant).frame(width: 8, height: 8)
            Text(text).font(.footnote)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 7)
        .background(Palette.surface, in: Capsule())
        .foregroundStyle(Palette.onBackground)
    }
}

/// The sheet at START (UI-285 B): RaceBoxes in range as they advertise, the one used last on top,
/// and the phone's own GPS.
struct ReceiverSheet: View {
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
            if UITestMode.on {
                row("Simulated GPS", "UI test", ok: true) { onPick(.simulated) }
                    .accessibilityIdentifier("receiver.simulated")
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
                    Picker("Video", selection: $videoMode) {
                        ForEach(modes) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.menu)
                    .tint(Palette.accent)
                }
                .padding(.vertical, 6)
                .accessibilityIdentifier("phoneVideoMode")
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
        .accessibilityIdentifier("receiverSheet")
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
