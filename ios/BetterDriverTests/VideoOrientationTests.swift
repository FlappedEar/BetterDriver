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

    /// A file tagged upright, as the 2026-10-30 drive's, makes a landscape composition: the overlay
    /// is drawn at that size, so its tiles have the full width.
    func testAFileTaggedUprightPlaysLandscape() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("upright-\(UUID().uuidString).mp4")
        defer { try? FileManager.default.removeItem(at: url) }
        XCTAssertTrue(DataVideoDemo.write(url, seconds: 1, upright: true))
        let source = try await XCTUnwrap(AVURLAsset(url: url).loadTracks(withMediaType: .video).first)
        let tagged = try await source.load(.preferredTransform)
        XCTAssertEqual(tagged.b, 1, accuracy: 0.001)

        let composition = AVMutableComposition()
        let track = try XCTUnwrap(composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid))
        let range = try await source.load(.timeRange)
        try track.insertTimeRange(range, of: source, at: .zero)
        track.preferredTransform = DataVideoMaker.landscape(tagged)
        // As DataVideoMaker.make builds the overlay's composition.
        let video = AVMutableVideoComposition(asset: composition) { $0.finish(with: $0.sourceImage, context: nil) }
        XCTAssertEqual(video.renderSize, CGSize(width: 640, height: 360))
    }
}
