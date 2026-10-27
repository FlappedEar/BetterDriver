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
}
