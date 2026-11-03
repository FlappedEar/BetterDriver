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

/// FBBD-441: the phone camera never gives up after failed files; it backs off while they keep failing.
final class PhoneFileRetryTests: XCTestCase {
    func testTheCameraKeepsStartingFilesAndBacksOffWhileTheyFail() {
        var retry = PhoneFileRetry()
        XCTAssertTrue(retry.mayOpen(at: 0))
        // The 3 Nov pattern: files failing after 10 s to 4 min.
        retry.failed(startedAt: 0, now: 30)
        XCTAssertEqual(retry.wait, 0)
        XCTAssertTrue(retry.mayOpen(at: 30), "the first file after a failure starts at once")
        retry.failed(startedAt: 30, now: 40)
        XCTAssertEqual(retry.wait, 2)
        XCTAssertFalse(retry.mayOpen(at: 41))
        XCTAssertTrue(retry.mayOpen(at: 42))
        retry.failed(startedAt: 42, now: 45)
        XCTAssertEqual(retry.wait, 4)
        retry.failed(startedAt: 49, now: 50)
        retry.failed(startedAt: 58, now: 59)
        retry.failed(startedAt: 75, now: 76)
        retry.failed(startedAt: 108, now: 109)
        XCTAssertEqual(retry.wait, PhoneFileRetry.longestWait, "the wait stops growing at 30 s")
        XCTAssertTrue(retry.mayOpen(at: 139), "a fourth, tenth, any file still starts")
        // A file that recorded for a minute ends the run: the next failure restarts at once.
        retry.failed(startedAt: 139, now: 139 + PhoneFileRetry.steadySeconds)
        XCTAssertEqual(retry.inARow, 1)
        XCTAssertEqual(retry.wait, 0)
        XCTAssertEqual(retry.total, 8)
    }
}
