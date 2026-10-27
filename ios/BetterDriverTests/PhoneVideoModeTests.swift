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

    /// The camera's own settings for every mode in the list, H.264 and HEVC, which the writer takes
    /// (the first-frame crash of the 2026-10-27 TestFlight).
    func testVideoSettingsForEveryMode() {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).mp4")
        guard let writer = try? AVAssetWriter(outputURL: url, fileType: .mp4) else { return XCTFail("no writer") }
        for mode in PhoneVideoMode.all {
            for codec in [AVVideoCodecType.h264, .hevc] {
                let s = PhoneCamera.videoSettings(codec: codec, width: mode.size.width, height: mode.size.height, fps: mode.fps)
                XCTAssertEqual(s[AVVideoCodecKey] as? AVVideoCodecType, codec)
                XCTAssertEqual(s[AVVideoWidthKey] as? Int, Int(mode.size.width))
                XCTAssertTrue(writer.canApply(outputSettings: s, forMediaType: .video), "\(mode.label) \(codec.rawValue)")
            }
        }
        let fhd = PhoneCamera.videoSettings(codec: .h264, width: 1920, height: 1080, fps: 60)
        XCTAssertEqual((fhd[AVVideoCompressionPropertiesKey] as? [String: Any])?[AVVideoAverageBitRateKey] as? Int, 18_662_400)
    }
}
