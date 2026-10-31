import AVFoundation
import BetterDriverCore
import CoreBluetooth
import CoreLocation
import SwiftUI
import UIKit

/// First run, as Android's SetupScreen (UI-13 option B, FBBD-371): one checklist page, steps in any
/// order, only permissions needed to finish. The steps, their texts and what "done" means are the
/// shared core's (FirstRunSetup); this page asks iOS for the permissions and draws the rows. The
/// iPhone opens on it until Done is tapped, and again when location or Bluetooth was taken back;
/// the iPad only reviews and never opens on it (Android's tablet the same). Settings › App › Run
/// setup again shows it on both.
struct FirstRunPage: View {
    @EnvironmentObject private var settings: SettingsModel
    @StateObject private var permissions = FirstRunPermissions()
    @State private var addingCar = false
    @State private var path: [FirstRunStepKind] = []
    @Environment(\.scenePhase) private var phase
    let onDone: () -> Void

    var body: some View {
        let core = FirstRunSetup.shared
        let steps = core.steps(setup: settings.setup, permissions: permissions.state)
        let canFinish = core.canFinish(steps: steps)
        NavigationStack(path: $path) {
            VStack(spacing: 0) {
                ShellPage(title: core.TITLE, large: true) {
                    DimText(core.summary(done: Int32(steps.filter(\.done).count), total: Int32(steps.count)))
                        .accessibilityIdentifier("setup.summary")
                    ForEach(steps, id: \.kind) { step in
                        FirstRunRow(step: step) { act(step.kind) }
                    }
                }
                VStack(spacing: 6) {
                    Button(action: onDone) {
                        Text(core.DONE)
                            .font(.headline)
                            .foregroundStyle(canFinish ? Palette.onAccent : Palette.onSurfaceVariant)
                            .frame(maxWidth: .infinity, minHeight: 56)
                            .background(canFinish ? Palette.accent : Palette.surfaceVariant, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(!canFinish)
                    .accessibilityIdentifier("setup.done")
                    if !canFinish { DimText(core.PERMISSIONS_FIRST) }
                }
                .padding(16)
                .background(Palette.background)
            }
            .background(Palette.background)
            .navigationDestination(for: FirstRunStepKind.self) { kind in
                if kind == .gnss {
                    AddEquipmentPage()
                } else if kind == .cameras {
                    EquipmentPage()
                } else {
                    CarsPage()
                }
            }
        }
        .sheet(isPresented: $addingCar) { CarEditSheet(carId: nil) }
        // Picks up grants made in the Settings app.
        .onChange(of: phase) { _, now in if now == .active { permissions.refresh() } }
        .onAppear { permissions.refresh() }
    }

    private func act(_ kind: FirstRunStepKind) {
        if kind == .permissions {
            permissions.allow()
        } else if kind == .car && settings.setup.cars.isEmpty {
            addingCar = true
        } else {
            path.append(kind)
        }
    }
}

/// One step: the dot, the title, what is set up, what stays off, and its button, as Android's SetupRow.
private struct FirstRunRow: View {
    let step: FirstRunStep
    let action: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            StatusDot(ready: step.done)
            VStack(alignment: .leading, spacing: 2) {
                Text(step.title).fontWeight(.semibold).foregroundStyle(Palette.onBackground)
                DimText(step.detail)
                ForEach(step.notes, id: \.self) { note in
                    DimText(note, color: Palette.warning).padding(.top, 4)
                }
            }
            Spacer(minLength: 8)
            if let label = step.action {
                Button(action: action) {
                    Text(label)
                        .fontWeight(.medium)
                        .foregroundStyle(Palette.onBackground)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(Palette.surfaceVariant, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("setup.action.\(step.title)")
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("setup.step.\(step.title)")
    }
}

/// What iOS has granted, for the permissions step: location and Bluetooth are needed (the phone's
/// GPS, a RaceBox and the other Bluetooth equipment); camera and microphone are optional (phone
/// video). iOS asks each once: after a "Don't Allow" only the Settings app can grant, so the button
/// opens it.
@MainActor
final class FirstRunPermissions: NSObject, ObservableObject, CLLocationManagerDelegate, CBCentralManagerDelegate {
    @Published private(set) var state = PermissionsState(requiredGranted: false, allGranted: false, blocked: false, denied: [])
    private let location = CLLocationManager()
    private var central: CBCentralManager?
    private var asking = false

    override init() {
        super.init()
        location.delegate = self
        refresh()
    }

    /// Location, Bluetooth, camera and microphone granted; used at launch to decide on the checklist.
    static var requiredGranted: Bool {
        let place = CLLocationManager().authorizationStatus
        return (place == .authorizedWhenInUse || place == .authorizedAlways) && CBManager.authorization == .allowedAlways
    }

    func refresh() {
        let place = location.authorizationStatus
        let placeOk = place == .authorizedWhenInUse || place == .authorizedAlways
        let bluetooth = CBManager.authorization
        let camera = AVCaptureDevice.authorizationStatus(for: .video)
        let mic = AVCaptureDevice.authorizationStatus(for: .audio)
        let required = placeOk && bluetooth == .allowedAlways
        let all = required && camera == .authorized && mic == .authorized
        var denied: [PermissionLine] = []
        if place == .denied || place == .restricted { denied.append(.location) }
        if bluetooth == .denied || bluetooth == .restricted { denied.append(.nearbyDevices) }
        if camera == .denied || camera == .restricted { denied.append(.camera) }
        if mic == .denied || mic == .restricted { denied.append(.microphone) }
        // Nothing left that iOS would still ask: only Settings can grant the rest.
        let undetermined = place == .notDetermined || bluetooth == .notDetermined
            || camera == .notDetermined || mic == .notDetermined
        state = PermissionsState(requiredGranted: required, allGranted: all, blocked: !all && !undetermined, denied: denied)
    }

    /// Asks for each permission not answered yet, one after another, as Android's one dialog does.
    func allow() {
        if state.blocked {
            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            return
        }
        asking = true
        next()
    }

    private func next() {
        guard asking else { return }
        if location.authorizationStatus == .notDetermined {
            location.requestWhenInUseAuthorization()
        } else if CBManager.authorization == .notDetermined {
            // Creating a central manager is what shows iOS's Bluetooth question.
            if central == nil { central = CBCentralManager(delegate: self, queue: nil) }
        } else if AVCaptureDevice.authorizationStatus(for: .video) == .notDetermined {
            AVCaptureDevice.requestAccess(for: .video) { _ in Task { @MainActor in self.answered() } }
        } else if AVCaptureDevice.authorizationStatus(for: .audio) == .notDetermined {
            AVCaptureDevice.requestAccess(for: .audio) { _ in Task { @MainActor in self.answered() } }
        } else {
            asking = false
        }
        refresh()
    }

    private func answered() {
        refresh()
        next()
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in
            // The delegate is also called once when it is set, before any answer.
            if manager.authorizationStatus != .notDetermined { self.answered() } else { self.refresh() }
        }
    }

    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Task { @MainActor in
            if CBManager.authorization != .notDetermined { self.answered() }
        }
    }
}

/// Whether the checklist is up: at launch on an iPhone until Done (FirstRunSetup.opensOnSetup, as
/// Android's start screen), and from Settings › App › Run setup again on both. An install from
/// before this page (TestFlight 1.24.0 and earlier) counts as set up: its settings are there.
@MainActor
final class FirstRunGate: ObservableObject {
    static let shared = FirstRunGate()

    @Published var showing = false
    private let defaults: UserDefaults
    private static let doneKey = "setupDone"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// Call once at launch, before Settings reads or writes setup.json.
    func decideAtLaunch() {
        if defaults.object(forKey: Self.doneKey) == nil {
            let setupFile = AppFolders.base.appendingPathComponent("setup.json")
            let earlier = FileManager.default.fileExists(atPath: setupFile.path)
                || defaults.bool(forKey: "earlierChoicesMoved")
            defaults.set(earlier, forKey: Self.doneKey)
        }
        let tablet = UIDevice.current.userInterfaceIdiom == .pad
        showing = !Self.skippedForTests && FirstRunSetup.shared.opensOnSetup(
            tablet: tablet, setupDone: defaults.bool(forKey: Self.doneKey),
            requiredGranted: FirstRunPermissions.requiredGranted
        )
    }

    /// Settings › App › Run setup again.
    func runAgain() {
        defaults.set(false, forKey: Self.doneKey)
        showing = true
    }

    func done() {
        defaults.set(true, forKey: Self.doneKey)
        showing = false
    }

    /// The UI tests start past the checklist, as a set-up phone, unless they test it (-firstRun).
    private static var skippedForTests: Bool {
        #if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        let testing = ["-uiTest", "-freshInstall", "-demoSessions"].contains { arguments.contains($0) }
        return testing && !arguments.contains("-firstRun")
        #else
        return false
        #endif
    }
}
