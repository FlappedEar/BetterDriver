import XCTest
import BetterDriverCore
@testable import BetterDriver

/// The links held open until the app closes (Arek 2026-11-05) are exactly the devices a session
/// would start with the choice at START, and the START sheet's GPS pick holds against a new signal.
@MainActor
final class HeldConnectionsTests: XCTestCase {
    private var base: URL!

    private let strapA = "11111111-1111-1111-1111-111111111111"
    private let strapB = "22222222-2222-2222-2222-222222222222"
    private let bridgeA = "33333333-3333-3333-3333-333333333333"
    private let obdA = "44444444-4444-4444-4444-444444444444"
    private let obdB = "55555555-5555-5555-5555-555555555555"
    private let raceBox = UUID(uuidString: "77777777-7777-7777-7777-777777777777")!

    override func setUp() {
        base = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: base)
    }

    private func add(_ setup: Setup, _ model: String, _ name: String, _ address: String, _ id: String) -> Setup {
        SettingsCore.shared.addEquipment(setup: setup, model: SettingsCore.shared.model(name: model)!, name: name, address: address, id: id)
    }

    /// Arek's Sunday iPhone: two straps (808S added last), the RCTPMS bridge, vLinker and Konnwei.
    private func sunday() -> SettingsModel {
        let model = SettingsModel(base: base)
        model.update { add($0, ModelName.heartRate, "Polar 8054-1", strapA, "hrA") }
        model.update { add($0, ModelName.heartRate, "808S 0031300", strapB, "hrB") }
        model.update { add($0, ModelName.tpmsBridge, "RCTPMS", bridgeA, "tpms") }
        model.update { add($0, ModelName.obdBluetooth, "vLinker MC+", obdA, "obdA") }
        model.update { add($0, ModelName.obdBluetooth, "Konnwei", obdB, "obdB") }
        return model
    }

    private func ids(_ devices: [UUID: String]) -> Set<String> {
        Set(devices.keys.map(\.uuidString))
    }

    func testTheHeldDevicesAreTheOnesASessionStarts() {
        let model = sunday()
        model.update { $0.chooseAtStart(kind: StartKind.carData, id: "obdA", on: true) }
        let held = HeldConnections.devices(model.setup, gnss: (raceBox, "RaceBox Micro 3242703553"))
        XCTAssertEqual(ids(held), [raceBox.uuidString, strapB, bridgeA, obdA])
        XCTAssertEqual(held[raceBox], "RaceBox Micro 3242703553")
    }

    func testAStrapPickedAtStartIsHeldInPlaceOfTheOther() {
        let model = sunday()
        model.update { $0.chooseAtStart(kind: StartKind.heartRate, id: "hrA", on: true) }
        let held = ids(HeldConnections.devices(model.setup, gnss: nil))
        XCTAssertTrue(held.contains(strapA))
        XCTAssertFalse(held.contains(strapB), "the strap not chosen is let go")
    }

    func testNoneAtStartHoldsNothingOfThatKind() {
        let model = sunday()
        model.update { $0.chooseAtStart(kind: StartKind.heartRate, id: nil, on: true) }
        model.update { $0.chooseAtStart(kind: StartKind.tpms, id: nil, on: true) }
        model.update { $0.chooseAtStart(kind: StartKind.carData, id: nil, on: true) }
        XCTAssertTrue(HeldConnections.devices(model.setup, gnss: nil).isEmpty)
    }

    func testOnlyBluetoothReceiversAreHeld() {
        let device = FoundRaceBoxDevice(id: raceBox, name: "RaceBox Micro 3242703553", rssi: -60)
        XCTAssertEqual(HeldConnections.bluetoothDevice(.raceBox(device))?.id, raceBox)
        XCTAssertNil(HeldConnections.bluetoothDevice(.phone))
        XCTAssertNil(HeldConnections.bluetoothDevice(.simulated))
        XCTAssertNil(HeldConnections.bluetoothDevice(.demoTrack))
    }

    /// A listed Bluetooth LE GPS picked at START is let go once it is removed from Equipment.
    func testARemovedListedReceiverIsNoLongerHeld() {
        let model = sunday()
        let gps = "88888888-8888-8888-8888-888888888888"
        model.update { add($0, ModelName.lnsGnss, "BLE GPS", gps, "gps") }
        let item = model.setup.equipment.first { $0.id == "gps" }!
        HeldConnections.shared.choose(.listed(item))
        XCTAssertEqual(HeldConnections.chosenGnss(model.setup)?.id.uuidString, gps)
        model.update { $0.removeEquipment(id: "gps") }
        XCTAssertNil(HeldConnections.chosenGnss(model.setup))
        HeldConnections.shared.choose(.phone)
        XCTAssertNil(HeldConnections.chosenGnss(model.setup))
    }

    /// The START sheet's GPS pick came undone with the next advertisement's signal (Arek 2026-11-05).
    func testAPickedRaceBoxStaysPickedAsItsSignalChanges() {
        let picked = Receiver.raceBox(FoundRaceBoxDevice(id: raceBox, name: "RaceBox Micro 3242703553", rssi: -60))
        let next = Receiver.raceBox(FoundRaceBoxDevice(id: raceBox, name: "RaceBox Micro 3242703553", rssi: -71))
        XCTAssertEqual(picked, next)
        let other = Receiver.raceBox(FoundRaceBoxDevice(id: UUID(), name: "RaceBox Micro 3559201985", rssi: -60))
        XCTAssertNotEqual(picked, other)
    }
}
