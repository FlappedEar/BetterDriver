import XCTest
import BetterDriverCore
@testable import BetterDriver

/// Settings keeps the Android app's files (FBBD-338): what one SettingsModel saves, the next one reads.
@MainActor
final class SettingsTests: XCTestCase {
    private var base: URL!

    override func setUp() {
        base = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: base)
    }

    func testAFreshInstallHasThePhoneCameraOnly() {
        let model = SettingsModel(base: base)
        XCTAssertEqual(model.setup.equipment.map(\.modelName), [ModelName.phoneCamera])
        XCTAssertEqual(coachLabel(model.coach), "3 focus corners")
    }

    func testEquipmentAndCarsAreSavedAndReadBack() {
        let model = SettingsModel(base: base)
        let raceBox = SettingsCore.shared.model(name: ModelName.raceBox)!
        model.update {
            SettingsCore.shared.addEquipment(setup: $0, model: raceBox, name: "RaceBox Micro 3241", address: "UUID-1", id: "rb")
        }
        model.update { SettingsCore.shared.addCar(setup: $0, name: "Scion FR-S", notes: "6AT", id: "car") }
        let again = SettingsModel(base: base)
        XCTAssertEqual(again.setup, model.setup)
        XCTAssertEqual(again.setup.session.gnssId, "rb")
        XCTAssertEqual(again.setup.session.carId, "car")
        XCTAssertTrue(FileManager.default.fileExists(atPath: base.appendingPathComponent("setup.json").path))
    }

    func testCoachSettingsAreSavedAsAndroidsFile() throws {
        let model = SettingsModel(base: base)
        let polish = SettingsCore.shared.languages.first { $0.name == "Polish" }!
        model.updateCoach { SettingsCore.shared.withLanguage(s: $0, l: polish) }
        model.updateCoach { SettingsCore.shared.withLearningLaps(s: $0, laps: 1) }
        let file = base.appendingPathComponent("coach/settings.properties")
        let text = try String(contentsOf: file, encoding: .utf8)
        XCTAssertTrue(text.contains("language=Polish"))
        XCTAssertTrue(text.contains("learningLaps=1"))
        XCTAssertEqual(SettingsModel(base: base).coach, model.coach)
    }

    func testAnUnreadableSetupIsMovedAside() throws {
        try Data("not a setup".utf8).write(to: base.appendingPathComponent("setup.json"))
        let model = SettingsModel(base: base)
        XCTAssertEqual(model.setup.equipment.count, 1)
        let names = try FileManager.default.contentsOfDirectory(atPath: base.path)
        XCTAssertTrue(names.contains { $0.hasPrefix("setup.json.unreadable-") })
    }

    /// What the iPhone remembered before Settings moves in on the first start (FBBD-338).
    func testAFirstStartTakesTheEarlierChoices() {
        let defaults = UserDefaults(suiteName: "settings-\(UUID().uuidString)")!
        defaults.set(false, forKey: "phoneCamera")
        defaults.set("4K · 30", forKey: "phoneVideoMode")
        defaults.set("6F1A2B3C-0000-4000-8000-000000000001", forKey: "heartRateStrapId")
        defaults.set("Polar H10 1234", forKey: "heartRateStrapName")
        defaults.set("6F1A2B3C-0000-4000-8000-000000000002", forKey: "lastObdAdapterId")
        defaults.set("en", forKey: "coachLanguage")
        let model = SettingsModel(base: base, defaults: defaults)
        XCTAssertFalse(SettingsCore.shared.phoneCameraOn(setup: model.setup))
        XCTAssertEqual(SettingsCore.shared.phoneVideoLabel(setup: model.setup), "4K · 30")
        let strap = model.setup.equipment.first { $0.modelName == ModelName.heartRate }
        XCTAssertEqual(strap?.name, "Polar H10 1234")
        XCTAssertEqual(strap?.address, "6F1A2B3C-0000-4000-8000-000000000001")
        XCTAssertEqual(model.setup.equipment.first { $0.modelName == ModelName.obdBluetooth }?.name, "OBD adapter")
        XCTAssertEqual(model.coach.language.name, "English")
        // Read once: a second start keeps what Settings has.
        defaults.set(true, forKey: "phoneCamera")
        XCTAssertFalse(SettingsCore.shared.phoneCameraOn(setup: SettingsModel(base: base, defaults: defaults).setup))
    }

    /// START records from the receiver chosen in Settings: a RaceBox by its identifier, or the phone's GPS.
    func testStartUsesTheReceiverInSettings() {
        let model = SettingsModel(base: base, defaults: UserDefaults(suiteName: "settings-\(UUID().uuidString)")!)
        XCTAssertEqual(RecordingController.receiver(model.setup), .failure(.noReceiver))
        let raceBox = SettingsCore.shared.model(name: ModelName.raceBox)!
        let id = UUID()
        model.update {
            SettingsCore.shared.addEquipment(setup: $0, model: raceBox, name: "RaceBox Mini S", address: id.uuidString, id: "rb")
        }
        guard case .success(.raceBox(let device)) = RecordingController.receiver(model.setup) else {
            return XCTFail("no RaceBox")
        }
        XCTAssertEqual(device.id, id)
        XCTAssertEqual(device.name, "RaceBox Mini S")
        let gps = SettingsCore.shared.model(name: ModelName.phoneGps)!
        model.update { SettingsCore.shared.addEquipment(setup: $0, model: gps, name: "Phone GPS", address: nil, id: "gps") }
        model.update { $0.chooseGnss(id: "gps") }
        XCTAssertEqual(RecordingController.receiver(model.setup), .success(.phone))
    }

    /// An empty setup.json (a crash while writing on an old build) reads as a fresh install.
    func testAnEmptySetupFileIsAFreshInstall() throws {
        try Data().write(to: base.appendingPathComponent("setup.json"))
        let model = SettingsModel(base: base, defaults: UserDefaults(suiteName: "settings-\(UUID().uuidString)")!)
        XCTAssertEqual(model.setup.equipment.map(\.modelName), [ModelName.phoneCamera])
    }

    /// A RaceBox copied from an Android setup has a MAC address, not an iPhone identifier: START says so.
    func testAnAndroidRaceBoxDoesNotStart() {
        let model = SettingsModel(base: base, defaults: UserDefaults(suiteName: "settings-\(UUID().uuidString)")!)
        let raceBox = SettingsCore.shared.model(name: ModelName.raceBox)!
        model.update {
            SettingsCore.shared.addEquipment(setup: $0, model: raceBox, name: "RB", address: "AA:BB:CC:DD:EE:FF", id: "rb")
        }
        XCTAssertEqual(RecordingController.receiver(model.setup), .failure(.notOnIphone("RB")))
    }
}
