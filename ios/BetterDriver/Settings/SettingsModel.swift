import BetterDriverCore
import Foundation

/// What is set up on this phone (FBBD-338), kept in the Android app's files under Application Support:
/// setup.json (equipment, cars and the next session's choice, FBBD-83) and coach/settings.properties
/// (the spoken coach, FBBD-98), plus the track screen's two switches. Every change is saved at once.
@MainActor
final class SettingsModel: ObservableObject {
    static let shared = SettingsModel()

    @Published private(set) var setup: Setup
    @Published private(set) var coach: CoachSettings
    /// Track screen at full brightness (on by default) and a sound when GNSS is lost (off), as on Android.
    @Published var fullBrightness: Bool {
        didSet { UserDefaults.standard.set(fullBrightness, forKey: Keys.brightness) }
    }
    @Published var gnssSound: Bool {
        didSet { UserDefaults.standard.set(gnssSound, forKey: Keys.gnssSound) }
    }

    private let base: URL

    init(base: URL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]) {
        self.base = base
        setup = SettingsModel.loadSetup(base: base)
        let coachText = try? String(contentsOf: SettingsModel.coachFile(base), encoding: .utf8)
        coach = SettingsCore.shared.coachSettings(text: coachText)
        fullBrightness = UserDefaults.standard.object(forKey: Keys.brightness) as? Bool ?? true
        gnssSound = UserDefaults.standard.bool(forKey: Keys.gnssSound)
        #if DEBUG
        // An Open GoPro camera that is never there, for the smoke test's start and stop (FBBD-343).
        if ProcessInfo.processInfo.arguments.contains("-openGoPro"),
           !setup.equipment.contains(where: { $0.modelName == ModelName.goPro }) {
            update {
                SettingsCore.shared.addOpenGoPro(
                    setup: $0, name: "GoPro 0000", address: "00000000-0000-0000-0000-000000000043",
                    goPro: .hero11Black, id: "test-open-gopro"
                )
            }
        }
        #endif
    }

    /// Applies a change to the setup and saves it.
    func update(_ change: (Setup) -> Setup) {
        let next = change(setup)
        guard next != setup else { return }
        setup = next
        write(SettingsCore.shared.encodeSetup(setup: next), to: SettingsModel.setupFile(base))
    }

    func updateCoach(_ change: (CoachSettings) -> CoachSettings) {
        let next = change(coach)
        guard next != coach else { return }
        coach = next
        write(SettingsCore.shared.coachText(settings: next), to: SettingsModel.coachFile(base))
    }

    /// A new item's id, as Android gives them.
    static func newId() -> String { UUID().uuidString.lowercased() }

    // Files.

    private static func setupFile(_ base: URL) -> URL { base.appendingPathComponent("setup.json") }

    private static func coachFile(_ base: URL) -> URL {
        base.appendingPathComponent("coach", isDirectory: true).appendingPathComponent("settings.properties")
    }

    /// The saved setup; a fresh install's (the phone camera only) when there is none. A file this build
    /// cannot read is moved aside, never overwritten, as on Android.
    private static func loadSetup(base: URL) -> Setup {
        let file = setupFile(base)
        if let text = try? String(contentsOf: file, encoding: .utf8) {
            if let setup = SettingsCore.shared.decodeSetup(text: text) { return setup }
            let aside = file.path + ".unreadable-\(Int64(Date().timeIntervalSince1970 * 1_000))"
            try? FileManager.default.moveItem(atPath: file.path, toPath: aside)
        }
        return SettingsCore.shared.freshSetup(id: newId())
    }

    private func write(_ text: String, to url: URL) {
        do {
            try FileManager.default.createDirectory(
                at: url.deletingLastPathComponent(), withIntermediateDirectories: true
            )
            // Written to a temporary file and moved over the old one, so a crash leaves one or the other.
            try Data(text.utf8).write(to: url, options: .atomic)
        } catch {
            AppLog.w("Settings", "settings not saved: \(error.localizedDescription)")
        }
    }

    private enum Keys {
        static let brightness = "trackFullBrightness"
        static let gnssSound = "trackGnssSound"
    }
}

/// Kotlin enums by name: their Swift case names are generated, the names are the Android ones.
extension Equipment {
    var kindName: String { kind.name }
    var modelName: String { model.name }
}

enum ModelName {
    static let raceBox = "RACEBOX"
    static let phoneGps = "PHONE_GPS"
    static let phoneCamera = "PHONE_CAMERA"
    static let heartRate = "HEART_RATE"
    static let obdBluetooth = "OBD_BLUETOOTH"
    static let obdWifi = "OBD_WIFI"
    static let goPro = "GOPRO"
    static let goProFiles = "GOPRO_FILES"
    static let cameraFiles = "CAMERA_FILES"
}

enum KindName {
    static let gnss = "GNSS"
    static let camera = "CAMERA"
    static let sensor = "SENSOR"
    static let carData = "CAR_DATA"
}
