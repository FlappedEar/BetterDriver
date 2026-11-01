import AVFoundation
import XCTest
@testable import BetterDriver

/// Audit 30 Oct BD-01 and BD-02: the phone camera's frame counts and its MP4s still being finished.
final class PhoneVideoFinishingTests: XCTestCase {
    /// A writer that is not ready (overloaded) gets no frame and no video_sync; the drop is counted.
    func testAFrameTheWriterIsNotReadyForIsDroppedAndNotSynced() {
        var counts = PhoneFrameCounts()
        var appends = 0
        let append: () -> Bool = {
            appends += 1
            return true
        }
        XCTAssertEqual(counts.frame(ready: true, append: append), .appended)
        XCTAssertEqual(counts.frame(ready: false, append: append), .dropped)
        XCTAssertEqual(counts.frame(ready: false, append: append), .dropped)
        XCTAssertEqual(counts.frame(ready: true, append: { false }), .failed)
        XCTAssertEqual(appends, 1, "nothing is appended while the writer is not ready")
        XCTAssertEqual(counts, PhoneFrameCounts(captured: 4, appended: 1, dropped: 2, late: 0))
        XCTAssertEqual(counts.text, "captured=4 appended=1 dropped=2 late=0")
    }

    /// The session's video is not offered while a file finishes; its last file posts a session change.
    func testASessionIsBusyUntilItsLastFileFinishes() {
        let finishing = PhoneVideoFinishing()
        let changed = expectation(forNotification: .sessionsChanged, object: nil)
        finishing.begin(sessionId: "a")
        finishing.begin(sessionId: "a")
        finishing.begin(sessionId: "b")
        XCTAssertTrue(finishing.busy(sessionId: "a"))
        XCTAssertFalse(finishing.end(sessionId: "a"))
        XCTAssertTrue(finishing.busy(sessionId: "a"))
        XCTAssertTrue(finishing.end(sessionId: "a"))
        XCTAssertFalse(finishing.busy(sessionId: "a"))
        XCTAssertTrue(finishing.busy(sessionId: "b"))
        wait(for: [changed], timeout: 5)
        XCTAssertFalse(finishing.busy(sessionId: "never"))
    }

    /// FBBD-350: the app is killed while a file is still being written or finished. The file as it is
    /// on disk then (copied before finishWriting) must still open and play up to its last fragment;
    /// the finished file plays whole.
    func testAFileCutOffBeforeItsFinishStillPlays() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("phone_0001.mp4")
        let fps: Int32 = 30
        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        writer.movieFragmentInterval = PhoneCamera.fragmentInterval
        let settings = PhoneCamera.videoSettings(codec: .h264, width: 320, height: 180, fps: fps)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
        input.expectsMediaDataInRealTime = true
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: 320,
            kCVPixelBufferHeightKey as String: 180,
        ])
        XCTAssertTrue(writer.canAdd(input))
        writer.add(input)
        XCTAssertTrue(writer.startWriting(), String(describing: writer.error))
        writer.startSession(atSourceTime: .zero)
        // 6 s of frames, three fragments' worth.
        for i in 0..<(6 * Int(fps)) {
            var waited = 0
            while !input.isReadyForMoreMediaData, waited < 5_000 {
                usleep(1_000)
                waited += 1
            }
            let pool = try XCTUnwrap(adaptor.pixelBufferPool)
            var buffer: CVPixelBuffer?
            XCTAssertEqual(CVPixelBufferPoolCreatePixelBuffer(nil, pool, &buffer), kCVReturnSuccess)
            let frame = try XCTUnwrap(buffer)
            CVPixelBufferLockBaseAddress(frame, [])
            if let base = CVPixelBufferGetBaseAddress(frame) {
                memset(base, Int32(i % 255), CVPixelBufferGetDataSize(frame))
            }
            CVPixelBufferUnlockBaseAddress(frame, [])
            XCTAssertTrue(adaptor.append(frame, withPresentationTime: CMTime(value: CMTimeValue(i), timescale: fps)))
        }
        // The app is killed here: what is on disk now is all a killed app leaves. The writer writes its
        // fragments on its own queue, so the copy is retried until one holds them (or 15 s pass).
        let cut = dir.appendingPathComponent("cut.mp4")
        var cutSeconds = 0.0
        for _ in 0..<30 where cutSeconds < 2 {
            try? FileManager.default.removeItem(at: cut)
            try FileManager.default.copyItem(at: url, to: cut)
            cutSeconds = await Self.videoSeconds(cut)
            if cutSeconds < 2 { try await Task.sleep(nanoseconds: 500_000_000) }
        }
        XCTAssertGreaterThanOrEqual(cutSeconds, 2, "a file cut off before its finish plays at least its first fragment")

        await writer.finishWriting()
        XCTAssertEqual(writer.status, .completed, String(describing: writer.error))
        let whole = await Self.videoSeconds(url)
        XCTAssertEqual(whole, 6, accuracy: 0.2, "the finished file plays whole")
    }

    /// The playable length of the video track in [url]; 0 when it does not open.
    private static func videoSeconds(_ url: URL) async -> Double {
        let asset = AVURLAsset(url: url)
        guard let track = try? await asset.loadTracks(withMediaType: .video).first,
              let range = try? await track.load(.timeRange) else { return 0 }
        let seconds = CMTimeGetSeconds(range.duration)
        return seconds.isFinite ? seconds : 0
    }
}
