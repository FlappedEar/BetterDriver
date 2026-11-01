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
        // The one-time Wi-Fi adapter move (FBBD-349) is done in the standard defaults, so a second
        // SettingsModel over the same files reads what the first saved; its own tests use their own.
        UserDefaults.standard.set(true, forKey: "wifiObdListed")
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: base)
    }

    func testAFreshInstallHasThePhoneCameraOnly() {
        let model = SettingsModel(base: base)
        XCTAssertEqual(model.setup.equipment.map(\.modelName), [ModelName.phoneCamera])
        XCTAssertEqual(coachLabel(model.coach), "3 focus corners")
    }

    /// Each car keeps its own tiles (FBBD-345), saved in setup.json as on Android.
    func testEachCarsTilesAreSavedAndReadBack() {
        let model = SettingsModel(base: base)
        model.update { SettingsCore.shared.addCar(setup: $0, name: "Scion FR-S", notes: "", id: "frs") }
        model.update { SettingsCore.shared.addCar(setup: $0, name: "Corolla", notes: "", id: "corolla") }
        model.update { TrackTiles.shared.choose(setup: $0, carId: "frs", second: false, slot: 2, id: "obd_coolant_temp") }
        let again = SettingsModel(base: base)
        XCTAssertEqual(TrackTiles.shared.ids(setup: again.setup, carId: "frs", second: false)[2], "obd_coolant_temp")
        XCTAssertEqual(TrackTiles.shared.ids(setup: again.setup, carId: "corolla", second: false)[2], "")
        again.update { TrackTiles.shared.reset(setup: $0, carId: "frs") }
        XCTAssertEqual(TrackTiles.shared.ids(setup: again.setup, carId: "frs", second: false), TrackScreenModel.defaultTiles)
    }

    func testACarsOwnParameterIsSavedAndReadBack() {
        let model = SettingsModel(base: base)
        model.update { SettingsCore.shared.addCar(setup: $0, name: "Scion FR-S", notes: "", id: "frs") }
        let draft = ParameterDraft(
            source: .can, canId: "0D4", header: "", request: "", equation: "A*256+B", unit: "rpm", fast: false,
            name: "Wheel speed"
        )
        model.update { CarPageCore.shared.saveParameter(setup: $0, carId: "frs", key: nil, draft: draft) }
        let again = SettingsModel(base: base)
        let saved = again.setup.car(id: "frs")?.parameters.first
        XCTAssertEqual(saved?.name, "Wheel speed")
        XCTAssertEqual(saved?.canId, 0x0D4)
        XCTAssertEqual(saved.map { CarPageCore.shared.parameterSource(p: $0) }, "CAN 0D4 · A*256+B")
        again.update { CarPageCore.shared.removeParameter(setup: $0, carId: "frs", key: saved?.key ?? "") }
        XCTAssertEqual(again.setup.car(id: "frs")?.parameters.count, 0)
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
        defaults.set("6F1A2B3C-0000-4000-8000-000000000001", forKey: "heartRateStrapId")
        defaults.set("Polar H10 1234", forKey: "heartRateStrapName")
        defaults.set("6F1A2B3C-0000-4000-8000-000000000002", forKey: "lastObdAdapterId")
        defaults.set("en", forKey: "coachLanguage")
        let model = SettingsModel(base: base, defaults: defaults)
        let strap = model.setup.equipment.first { $0.modelName == ModelName.heartRate }
        XCTAssertEqual(strap?.name, "Polar H10 1234")
        XCTAssertEqual(strap?.address, "6F1A2B3C-0000-4000-8000-000000000001")
        XCTAssertEqual(model.setup.equipment.first { $0.modelName == ModelName.obdBluetooth }?.name, "OBD adapter")
        XCTAssertEqual(model.coach.language.name, "English")
        // Read once: a second start keeps what Settings has.
        defaults.set("Other strap", forKey: "heartRateStrapName")
        let again = SettingsModel(base: base, defaults: defaults).setup
        XCTAssertEqual(again.equipment.first { $0.modelName == ModelName.heartRate }?.name, "Polar H10 1234")
    }

    /// A setup.json from TestFlight 1.15.0 still takes the earlier adapter once; removed in Settings it
    /// stays removed, so Equipment is the one list the session uses (audit BD-10).
    func testTheEarlierAdapterMovesInOnceAndStaysRemoved() throws {
        let earlier = SettingsModel(base: base, defaults: UserDefaults(suiteName: "settings-\(UUID().uuidString)")!)
        XCTAssertEqual(earlier.setup.equipment.count, 1)
        let defaults = UserDefaults(suiteName: "settings-\(UUID().uuidString)")!
        defaults.set("6F1A2B3C-0000-4000-8000-000000000002", forKey: "lastObdAdapterId")
        let model = SettingsModel(base: base, defaults: defaults)
        let adapter = try XCTUnwrap(model.setup.equipment.first { $0.modelName == ModelName.obdBluetooth })
        model.update { $0.removeEquipment(id: adapter.id) }
        let again = SettingsModel(base: base, defaults: defaults)
        XCTAssertNil(again.setup.equipment.first { $0.modelName == ModelName.obdBluetooth })
    }

    /// An empty setup.json (a crash while writing on an old build) reads as a fresh install.
    func testAnEmptySetupFileIsAFreshInstall() throws {
        try Data().write(to: base.appendingPathComponent("setup.json"))
        let model = SettingsModel(base: base, defaults: UserDefaults(suiteName: "settings-\(UUID().uuidString)")!)
        XCTAssertEqual(model.setup.equipment.map(\.modelName), [ModelName.phoneCamera])
    }

    /// Only what Settings › Equipment lists records (FBBD-349, audit BD-10): the adapters and the strap
    /// a session starts with, and none once they are removed.
    func testARemovedAdapterOrStrapIsNotUsedInTheNextSession() throws {
        let defaults = UserDefaults(suiteName: "settings-\(UUID().uuidString)")!
        let model = SettingsModel(base: base, defaults: defaults)
        let core = SettingsCore.shared
        let obd = try XCTUnwrap(core.model(name: ModelName.obdBluetooth))
        let wifi = try XCTUnwrap(core.model(name: ModelName.obdWifi))
        let strap = try XCTUnwrap(core.model(name: ModelName.heartRate))
        let adapterId = "6F1A2B3C-0000-4000-8000-000000000002"
        let strapId = "6F1A2B3C-0000-4000-8000-000000000001"
        model.update { core.addEquipment(setup: $0, model: obd, name: "vLinker MC+", address: adapterId, id: "obd") }
        model.update { core.addEquipment(setup: $0, model: wifi, name: "Wi-Fi OBD-II adapter", address: nil, id: "wifi") }
        model.update { core.addEquipment(setup: $0, model: strap, name: "Polar H10 1234", address: strapId, id: "hr") }
        let listed = ObdLink.adapters(model.setup)
        XCTAssertEqual(listed.bluetooth.map(\.id.uuidString), [adapterId])
        XCTAssertEqual(listed.wifi.map { "\($0.host):\($0.port)" }, ["192.168.0.10:35000"])
        XCTAssertEqual(RecordingController.strap(model.setup.active())?.name, "Polar H10 1234")

        model.update { $0.removeEquipment(id: "obd") }
        XCTAssertTrue(ObdLink.adapters(model.setup).bluetooth.isEmpty)
        XCTAssertTrue(ObdLink.anyListed(model.setup), "the Wi-Fi adapter is still listed")
        model.update { $0.removeEquipment(id: "wifi") }
        model.update { $0.removeEquipment(id: "hr") }
        let next = SettingsModel(base: base, defaults: defaults).setup
        XCTAssertFalse(ObdLink.anyListed(next))
        XCTAssertNil(RecordingController.strap(next.active()))
    }

    /// A setup saved before FBBD-349, when every session tried the Wi-Fi adapter unlisted, gets it listed
    /// once; removed, it stays removed. A fresh install gets none.
    func testAnEarlierSetupGetsTheWifiAdapterOnce() throws {
        let fresh = SettingsModel(base: base, defaults: UserDefaults(suiteName: "settings-\(UUID().uuidString)")!)
        XCTAssertFalse(ObdLink.anyListed(fresh.setup))
        fresh.update { SettingsCore.shared.addCar(setup: $0, name: "Scion FR-S", notes: "", id: "car") }
        let defaults = UserDefaults(suiteName: "settings-\(UUID().uuidString)")!
        let model = SettingsModel(base: base, defaults: defaults)
        let item = try XCTUnwrap(model.setup.equipment.first { $0.modelName == ModelName.obdWifi })
        XCTAssertEqual(item.address, "192.168.0.10:35000")
        model.update { $0.removeEquipment(id: item.id) }
        let again = SettingsModel(base: base, defaults: defaults)
        XCTAssertFalse(ObdLink.anyListed(again.setup))
    }

}
