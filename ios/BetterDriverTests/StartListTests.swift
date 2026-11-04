import XCTest
import BetterDriverCore
@testable import BetterDriver

/// The list at START (FBBD-446, FBBD-447) decides what the iPhone records: each device chosen there
/// is the one RecordingController starts, None starts nothing of that kind, and the choice is kept
/// for the next session (UI-313 A).
@MainActor
final class StartListTests: XCTestCase {
    private var base: URL!

    private let strapA = "11111111-1111-1111-1111-111111111111"
    private let strapB = "22222222-2222-2222-2222-222222222222"
    private let bridgeA = "33333333-3333-3333-3333-333333333333"
    private let obdA = "44444444-4444-4444-4444-444444444444"
    private let obdB = "55555555-5555-5555-5555-555555555555"
    private let goPro = "66666666-6666-6666-6666-666666666666"

    override func setUp() {
        base = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        UserDefaults.standard.set(true, forKey: "wifiObdListed")
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: base)
    }

    private func add(_ setup: Setup, _ model: String, _ name: String, _ address: String, _ id: String) -> Setup {
        SettingsCore.shared.addEquipment(setup: setup, model: SettingsCore.shared.model(name: model)!, name: name, address: address, id: id)
    }

    /// Two straps (8054-1 and 808S), a tyre bridge, two OBD adapters and a GoPro, as Arek's Equipment.
    private func fullSetup() -> SettingsModel {
        let model = SettingsModel(base: base)
        model.update { add($0, ModelName.heartRate, "Polar 8054-1", strapA, "hrA") }
        model.update { add($0, ModelName.heartRate, "808S 0031300", strapB, "hrB") }
        model.update { add($0, ModelName.tpmsBridge, "RCTPMS", bridgeA, "tpms") }
        model.update { add($0, ModelName.obdBluetooth, "vLinker MC+", obdA, "obdA") }
        model.update { add($0, ModelName.obdBluetooth, "Konnwei", obdB, "obdB") }
        model.update { add($0, ModelName.goPro, "GoPro HERO11", goPro, "gp") }
        return model
    }

    private func group(_ setup: Setup, _ kind: StartKind) -> StartGroup? {
        StartListKt.startGroups(setup: setup).first { $0.kind == kind }
    }

    func testEveryListedKindHasAGroupOnTheList() {
        let setup = fullSetup().setup
        let kinds = StartListKt.startGroups(setup: setup).map(\.kind)
        XCTAssertTrue(kinds.contains(StartKind.camera))
        XCTAssertTrue(kinds.contains(StartKind.carData))
        XCTAssertTrue(kinds.contains(StartKind.heartRate))
        XCTAssertTrue(kinds.contains(StartKind.tpms))
        XCTAssertEqual(group(setup, StartKind.heartRate)?.rows.map(\.name), ["Polar 8054-1", "808S 0031300"])
    }

    func testTheChosenStrapIsTheOneRecorded() {
        let model = fullSetup()
        // The strap added last is chosen as it is added (FBBD-445).
        XCTAssertEqual(RecordingController.strap(model.setup.active())?.id.uuidString, strapB)
        model.update { $0.chooseAtStart(kind: StartKind.heartRate, id: "hrA", on: true) }
        XCTAssertEqual(RecordingController.strap(model.setup.active())?.id.uuidString, strapA)
        XCTAssertEqual(group(model.setup, StartKind.heartRate)?.rows.filter(\.on).map(\.id), ["hrA"])
        model.update { $0.chooseAtStart(kind: StartKind.heartRate, id: "hrB", on: true) }
        XCTAssertEqual(RecordingController.strap(model.setup.active())?.id.uuidString, strapB)
    }

    func testNoneStartsNoStrap() {
        let model = fullSetup()
        model.update { $0.chooseAtStart(kind: StartKind.heartRate, id: nil, on: true) }
        XCTAssertNil(RecordingController.strap(model.setup.active()))
        XCTAssertEqual(group(model.setup, StartKind.heartRate)?.noneOn, true)
        // The other kinds still record.
        XCTAssertNotNil(RecordingController.bridge(model.setup.active()))
        XCTAssertTrue(ObdLink.anyListed(model.setup))
    }

    func testTyresOnAndOff() {
        let model = fullSetup()
        XCTAssertEqual(RecordingController.bridge(model.setup.active())?.id.uuidString, bridgeA)
        model.update { $0.chooseAtStart(kind: StartKind.tpms, id: nil, on: true) }
        XCTAssertNil(RecordingController.bridge(model.setup.active()))
        XCTAssertNotNil(RecordingController.strap(model.setup.active()))
        model.update { $0.chooseAtStart(kind: StartKind.tpms, id: "tpms", on: true) }
        XCTAssertEqual(RecordingController.bridge(model.setup.active())?.id.uuidString, bridgeA)
    }

    func testCarDataOneAdapterOrNone() {
        let model = fullSetup()
        // Before a choice every listed adapter is tried.
        XCTAssertEqual(ObdLink.adapters(model.setup).bluetooth.map { $0.id.uuidString }, [obdA, obdB])
        model.update { $0.chooseAtStart(kind: StartKind.carData, id: "obdB", on: true) }
        XCTAssertEqual(ObdLink.adapters(model.setup).bluetooth.map { $0.id.uuidString }, [obdB])
        model.update { $0.chooseAtStart(kind: StartKind.carData, id: nil, on: true) }
        XCTAssertFalse(ObdLink.anyListed(model.setup))
        XCTAssertNotNil(RecordingController.strap(model.setup.active()))
    }

    /// The car data page outside a session reaches every listed adapter, even with None at START.
    func testTheCarDataPageSeesEveryAdapterWhateverStartChose() {
        let model = fullSetup()
        model.update { $0.chooseAtStart(kind: StartKind.carData, id: nil, on: true) }
        let page = model.setup.chooseCarData(id: nil)
        XCTAssertEqual(ObdLink.adapters(page).bluetooth.map { $0.id.uuidString }, [obdA, obdB])
        XCTAssertFalse(ObdLink.anyListed(model.setup), "the session still reads none")
    }

    func testAGoProOnAndOff() {
        let model = fullSetup()
        model.update { $0.chooseAtStart(kind: StartKind.camera, id: "gp", on: false) }
        XCTAssertFalse(model.setup.active().cameras.map(\.id).contains("gp"))
        model.update { $0.chooseAtStart(kind: StartKind.camera, id: "gp", on: true) }
        XCTAssertTrue(model.setup.active().cameras.map(\.id).contains("gp"))
    }

    func testEverythingOffStillLeavesAReceiverToRecordWith() {
        let model = fullSetup()
        model.update { $0.chooseAtStart(kind: StartKind.heartRate, id: nil, on: true) }
        model.update { $0.chooseAtStart(kind: StartKind.tpms, id: nil, on: true) }
        model.update { $0.chooseAtStart(kind: StartKind.carData, id: nil, on: true) }
        model.update { $0.chooseAtStart(kind: StartKind.camera, id: "gp", on: false) }
        let active = model.setup.active()
        XCTAssertNil(RecordingController.strap(active))
        XCTAssertNil(RecordingController.bridge(active))
        XCTAssertFalse(ObdLink.anyListed(model.setup))
        XCTAssertFalse(active.cameras.map(\.id).contains("gp"))
    }

    /// UI-313 A: the next session starts with the last choice, read back from setup.json.
    func testTheChoiceIsKeptForTheNextSession() {
        let model = fullSetup()
        model.update { $0.chooseAtStart(kind: StartKind.heartRate, id: "hrA", on: true) }
        model.update { $0.chooseAtStart(kind: StartKind.tpms, id: nil, on: true) }
        model.update { $0.chooseAtStart(kind: StartKind.carData, id: "obdA", on: true) }
        let again = SettingsModel(base: base)
        XCTAssertEqual(RecordingController.strap(again.setup.active())?.id.uuidString, strapA)
        XCTAssertNil(RecordingController.bridge(again.setup.active()))
        XCTAssertEqual(ObdLink.adapters(again.setup).bluetooth.map { $0.id.uuidString }, [obdA])
    }
}
