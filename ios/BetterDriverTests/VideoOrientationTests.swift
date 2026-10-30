import AVFoundation
import XCTest
@testable import BetterDriver

/// Phone video is landscape, as the track screen (Arek, 2026-10-30): recorded on a landscape side
/// only, and a file tagged upright still plays landscape with its overlay.
final class VideoOrientationTests: XCTestCase {
    func testTheCameraKeepsOnlyALandscapeSide() {
        XCTAssertEqual(PhoneCamera.landscape(0), 0)
        XCTAssertEqual(PhoneCamera.landscape(180), 180)
        XCTAssertEqual(PhoneCamera.landscape(360), 0)
        XCTAssertEqual(PhoneCamera.landscape(-180), 180)
        XCTAssertNil(PhoneCamera.landscape(90))
        XCTAssertNil(PhoneCamera.landscape(270))
        XCTAssertNil(PhoneCamera.landscape(-90))
    }

    func testPlaybackDropsAnUprightTurnAndKeepsUpsideDown() {
        XCTAssertEqual(DataVideoMaker.landscape(CGAffineTransform(rotationAngle: .pi / 2)), .identity)
        XCTAssertEqual(DataVideoMaker.landscape(CGAffineTransform(rotationAngle: -.pi / 2)), .identity)
        let upsideDown = CGAffineTransform(rotationAngle: .pi)
        XCTAssertEqual(DataVideoMaker.landscape(upsideDown), upsideDown)
        XCTAssertEqual(DataVideoMaker.landscape(.identity), .identity)
    }

    // A file tagged upright playing landscape end to end: DataVideoTests (the demo video is tagged
    // upright and the player says its video is wide).
}
