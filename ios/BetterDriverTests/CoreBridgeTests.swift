import XCTest
import BetterDriverCore
@testable import BetterDriver

/// The Kotlin core answers on iOS exactly as on Android (the same cases as TimeFormatTest).
final class CoreBridgeTests: XCTestCase {
    func testVersionIsSemantic() {
        XCTAssertNotNil(CoreBridge.version.range(of: #"^\d+\.\d+\.\d+$"#, options: .regularExpression))
    }

    func testVersionMatchesTheApp() {
        let appVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
        XCTAssertEqual(appVersion, CoreBridge.version)
    }

    func testLapTime() {
        XCTAssertEqual(CoreBridge.lapTime(millis: 112_070), "1:52.07")
        XCTAssertEqual(CoreBridge.lapTime(millis: 59_996), "1:00.00")
        XCTAssertEqual(CoreBridge.lapTime(millis: -5), "0:00.00")
    }

    func testRecorderIsExported() {
        XCTAssertEqual(VideoFiles.shared.liveName(camera: "phone", index: 1, extension: "mp4"), "phone_0001.mp4")
    }

    func testTimingIsExported() {
        // One degree of latitude on the IUGG mean sphere: R * pi / 180.
        let metres = GeoKt.distanceMeters(a: GeoPoint(lat: 0, lon: 0), b: GeoPoint(lat: 1, lon: 0))
        XCTAssertEqual(metres, 111_195.08, accuracy: 0.01)
    }

    func testAnalysisIsExported() {
        XCTAssertEqual(ChannelUnitsKt.normalizedSpeedUnit(unit: " KM/H "), "km/h")
    }

    func testDelta() {
        XCTAssertEqual(CoreBridge.delta(seconds: -0.34), "\u{2212}0.34")
        XCTAssertEqual(CoreBridge.delta(seconds: 0.08), "+0.08")
        XCTAssertEqual(CoreBridge.delta(seconds: 250), "+99.99")
    }
}
