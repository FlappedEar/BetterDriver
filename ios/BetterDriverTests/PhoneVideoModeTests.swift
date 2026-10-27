import AVFoundation
import XCTest
@testable import BetterDriver

/// Phone video modes on the iPhone are Android's (FBBD-88): the same choices, default and fallbacks.
final class PhoneVideoModeTests: XCTestCase {
    func testTheChoicesAreAndroids() {
        XCTAssertEqual(PhoneVideoMode.all.map(\.label), ["4K · 60", "4K · 30", "1080p · 60", "1080p · 30", "720p · 60", "720p · 30"])
        XCTAssertEqual(PhoneVideoMode.named(nil), PhoneVideoMode.standard)
        XCTAssertEqual(PhoneVideoMode.named("2.7K · 60").label, "1080p · 60")
        XCTAssertEqual(PhoneVideoMode.named("4K · 30").label, "4K · 30")
    }

    func testTheLadderKeepsTheFrameRateFirst() {
        XCTAssertEqual(PhoneVideoMode(size: .uhd, fps: 60).ladder.map(\.label), ["4K · 60", "1080p · 60", "720p · 60", "720p · 30"])
        XCTAssertEqual(PhoneVideoMode(size: .fhd, fps: 30).ladder.map(\.label), ["1080p · 30", "720p · 30"])
    }

    /// The camera's own H.264 settings when the phone gives none (the first-frame crash, 1.14 TestFlight).
    func testH264SettingsFromTheFrame() {
        let s = PhoneCamera.h264Settings(width: 1920, height: 1080, fps: 60)
        XCTAssertEqual(s[AVVideoCodecKey] as? AVVideoCodecType, .h264)
        XCTAssertEqual(s[AVVideoWidthKey] as? Int, 1920)
        XCTAssertEqual(s[AVVideoHeightKey] as? Int, 1080)
        let c = s[AVVideoCompressionPropertiesKey] as? [String: Any]
        XCTAssertEqual(c?[AVVideoAverageBitRateKey] as? Int, 18_662_400)
        let writer = try? AVAssetWriter(outputURL: FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).mp4"), fileType: .mp4)
        XCTAssertTrue(writer?.canApply(outputSettings: s, forMediaType: .video) ?? false)
    }
}
