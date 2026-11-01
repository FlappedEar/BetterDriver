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

    /// [defaults] holds what the iPhone remembered before it had Settings; the first start moves it in.
    init(
        base: URL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0],
        defaults: UserDefaults = .standard
    ) {
        self.base = base
        let hadCoach = FileManager.default.fileExists(atPath: SettingsModel.coachFile(base).path)
        let saved = SettingsModel.setupStore(base).load()
        setup = saved ?? SettingsCore.shared.freshSetup(id: SettingsModel.newId())
        coach = SettingsModel.coachStore(base).read()
        fullBrightness = defaults.object(forKey: Keys.brightness) as? Bool ?? true
        gnssSound = defaults.bool(forKey: Keys.gnssSound)
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
        // A Wi-Fi OBD-II adapter that is never there, for the car data page's test (FBBD-261).
        if ProcessInfo.processInfo.arguments.contains("-obdWifi"),
           !setup.equipment.contains(where: { $0.modelName == ModelName.obdWifi }),
           let wifi = SettingsCore.shared.model(name: ModelName.obdWifi) {
            update {
                SettingsCore.shared.addEquipment(
                    setup: $0, model: wifi, name: "Wi-Fi OBD-II adapter", address: "192.168.0.10:35000", id: "test-obd-wifi"
                )
            }
        }
        // A RaceBox that is never there, for the diagnostics screen's test (FBBD-371).
        if ProcessInfo.processInfo.arguments.contains("-raceBoxListed"),
           !setup.equipment.contains(where: { $0.modelName == ModelName.raceBox }),
           let raceBox = SettingsCore.shared.model(name: ModelName.raceBox) {
            update {
                SettingsCore.shared.addEquipment(
                    setup: $0, model: raceBox, name: "RaceBox Micro 0000000000",
                    address: "00000000-0000-0000-0000-000000000371", id: "test-racebox"
                )
            }
        }
        #endif
        // Once, also over a setup.json from a build before this move (TestFlight 1.15.0): after it,
        // Settings › Equipment is the one list, so an item removed there stays removed.
        if !defaults.bool(forKey: Keys.earlierChoicesMoved) {
            update { SettingsModel.earlierChoices($0, defaults) }
            defaults.set(true, forKey: Keys.earlierChoicesMoved)
        }
        // Once: a setup saved before FBBD-349, when every session also tried the Wi-Fi adapter unlisted,
        // gets it listed, so car data keeps coming as before; removed in Equipment it is not used again.
        if !defaults.bool(forKey: Keys.wifiObdListed) {
            if saved != nil { update { SettingsCore.shared.withEarlierWifiObd(setup: $0, id: SettingsModel.newId()) } }
            defaults.set(true, forKey: Keys.wifiObdListed)
        }
        if !hadCoach { updateCoach { SettingsModel.earlierLanguage($0, defaults) } }
    }

    /// The OBD-II adapter and heart-rate strap the iPhone found by itself before it had Settings
    /// (FBBD-333), as Settings items.
    private static func earlierChoices(_ setup: Setup, _ defaults: UserDefaults) -> Setup {
        let core = SettingsCore.shared
        var next = setup
        let found = [
            (ModelName.obdBluetooth, "lastObdAdapterId", "lastObdAdapterName", "OBD adapter"),
            (ModelName.heartRate, "heartRateStrapId", "heartRateStrapName", "Heart rate"),
        ]
        for (model, idKey, nameKey, fallback) in found {
            guard let id = defaults.string(forKey: idKey), let m = core.model(name: model),
                  !next.equipment.contains(where: { $0.address == id }) else { continue }
            let name = defaults.string(forKey: nameKey).flatMap { $0.isEmpty ? nil : $0 } ?? fallback
            next = core.addEquipment(setup: next, model: m, name: name, address: id, id: newId())
        }
        return next
    }

    /// The coach's language as the iPhone picked it before Settings: as saved, else the phone's.
    private static func earlierLanguage(_ coach: CoachSettings, _ defaults: UserDefaults) -> CoachSettings {
        let saved = defaults.string(forKey: "coachLanguage")
        let polish = saved.map { $0 == "pl" } ?? (Locale.preferredLanguages.first?.hasPrefix("pl") == true)
        guard polish, let pl = SettingsCore.shared.languages.first(where: { $0.name == "Polish" }) else { return coach }
        return SettingsCore.shared.withLanguage(s: coach, l: pl)
    }

    /// Applies a change to the setup and saves it.
    func update(_ change: (Setup) -> Setup) {
        let next = change(setup)
        guard next != setup else { return }
        setup = next
        save { try SettingsModel.setupStore(base).save(setup: next) }
    }

    func updateCoach(_ change: (CoachSettings) -> CoachSettings) {
        let next = change(coach)
        guard next != coach else { return }
        coach = next
        save { try SettingsModel.coachStore(base).write(settings: next) }
    }

    /// Reads setup.json and the coach settings again, after a restore changed them (FBBD-372).
    func reload() {
        setup = SettingsModel.loadSetup(base: base)
        coach = SettingsModel.coachStore(base).read()
    }

    /// A new item's id, as Android gives them.
    static func newId() -> String { UUID().uuidString.lowercased() }

    // Files.

    private static func setupFile(_ base: URL) -> URL { base.appendingPathComponent("setup.json") }

    /// setup.json through the store Android uses (FBBD-359): same file, same checks, same atomic write.
    private static func setupStore(_ base: URL) -> SetupStore { SetupStore(file: setupFile(base).path) }

    /// coach/settings.properties through the store Android uses (FBBD-359).
    private static func coachStore(_ base: URL) -> CoachSettingsStore { CoachSettingsStore(file: coachFile(base).path) }

    private static func coachFile(_ base: URL) -> URL {
        base.appendingPathComponent("coach", isDirectory: true).appendingPathComponent("settings.properties")
    }

    /// The saved setup; a fresh install's (the phone camera only) when there is none. A file this build
    /// cannot read is moved aside, never overwritten, as on Android (the shared SetupStore does both).
    private static func loadSetup(base: URL) -> Setup {
        setupStore(base).load() ?? SettingsCore.shared.freshSetup(id: newId())
    }

    /// Runs a store's write; one that fails is logged and the next change tries again.
    private func save(_ write: () throws -> Void) {
        do {
            try write()
        } catch {
            AppLog.w("Settings", "settings not saved: \(error.localizedDescription)")
        }
    }

    private enum Keys {
        static let brightness = "trackFullBrightness"
        static let gnssSound = "trackGnssSound"
        static let earlierChoicesMoved = "earlierChoicesMoved"
        static let wifiObdListed = "wifiObdListed"
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
    static let tpmsBridge = "TPMS_BRIDGE"
    static let obdBluetooth = "OBD_BLUETOOTH"
    static let obdWifi = "OBD_WIFI"
    static let goPro = "GOPRO"
    static let goProFiles = "GOPRO_FILES"
    static let cameraFiles = "CAMERA_FILES"
    static let demoTrack = "DEMO_TRACK"
}

enum KindName {
    static let gnss = "GNSS"
    static let camera = "CAMERA"
    static let sensor = "SENSOR"
    static let carData = "CAR_DATA"
}
