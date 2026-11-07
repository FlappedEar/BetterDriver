import XCTest
@testable import BetterDriver

/// How long phone video fits (FBBD-467, audit BD-T06), from the bitrate the camera sets.
final class VideoStorageTests: XCTestCase {
    private let fhd60 = PhoneVideoMode(size: .fhd, fps: 60)
    private let fhd30 = PhoneVideoMode(size: .fhd, fps: 30)

    /// 1080p · 60 at 18.66 Mbit/s and the sound: about 2.1 GB per 15 minutes, as the audit worked out.
    func testAMinuteOfEachModeIsItsBitrateAndSound() {
        XCTAssertEqual(VideoStorage.bytesPerMinute(fhd60), (18_662_400 + 192_000) * 60 / 8)
        XCTAssertEqual(Double(VideoStorage.bytesPerMinute(fhd60) * 15) / 1e9, 2.12, accuracy: 0.01)
        XCTAssertEqual(Double(VideoStorage.bytesPerMinute(fhd30) * 15) / 1e9, 1.07, accuracy: 0.01)
        XCTAssertGreaterThan(VideoStorage.bytesPerMinute(PhoneVideoMode(size: .uhd, fps: 60)), VideoStorage.bytesPerMinute(fhd60) * 3)
    }

    func testTheStartLineSaysHowLongTheModeFits() {
        let roomy = VideoStorage.startLine(freeBytes: 412_000_000_000, mode: fhd30)
        XCTAssertEqual(roomy.text, "412 GB free · about 96 h of video")
        XCTAssertFalse(roomy.short)
        let tight = VideoStorage.startLine(freeBytes: 3_000_000_000, mode: fhd60)
        XCTAssertEqual(tight.text, "3 GB free · about 21 min of video")
        XCTAssertTrue(tight.short, "under two 15-minute sessions is amber")
    }

    func testTheTrackScreenWarnsUnderTenMinutesLeft() {
        let minute = VideoStorage.bytesPerMinute(fhd60)
        XCTAssertNil(VideoStorage.warning(freeBytes: minute * 10, mode: fhd60))
        XCTAssertEqual(VideoStorage.warning(freeBytes: minute * 10 - 1, mode: fhd60), "About 9 min of video left")
        XCTAssertEqual(VideoStorage.warning(freeBytes: -5, mode: fhd60), "About 0 min of video left")
    }
}
