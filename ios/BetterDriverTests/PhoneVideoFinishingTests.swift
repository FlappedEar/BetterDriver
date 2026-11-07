import AVFoundation
import UIKit
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

    /// FBBD-350, FBBD-468 (audit 7 Nov BD-T03): the app is killed while a file is still being written.
    /// The writer is made as the app makes it (PhoneCamera.makeWriter), so this proves what the shipped
    /// files do: with fragments, the file as it is on disk then plays up to its last fragment; without
    /// them (FBBD-441, the shipped setting) it does not play at all, which [PhoneFileLength] bounds.
    /// The finished file plays whole either way.
    func testAFileCutOffBeforeItsFinishAsTheAppWritesIt() async throws {
        let dir = try Self.folder()
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("phone_0001.mp4")
        let file = try File(url: url, at: .zero)
        for i in 0..<(6 * Int(Self.fps)) { try file.append(frame: i) }
        // The app is killed here: what is on disk now is all a killed app leaves. The writer writes on
        // its own queue, so the copy is taken again until it plays or 15 s pass.
        let cut = dir.appendingPathComponent("cut.mp4")
        let cutSeconds = try await Self.cutOff(url, to: cut, enough: 2)
        if PhoneCamera.fragmented {
            XCTAssertGreaterThanOrEqual(cutSeconds, 2, "a fragmented file cut off before its finish plays its first fragment")
        } else {
            XCTAssertEqual(cutSeconds, 0, "without fragments a file cut off before its finish does not play")
        }
        await file.writer.finishWriting()
        XCTAssertEqual(file.writer.status, .completed, String(describing: file.writer.error))
        let whole = await Self.videoSeconds(url)
        XCTAssertEqual(whole, 6, accuracy: 0.2, "the finished file plays whole")
    }

    /// FBBD-468 (audit 7 Nov BD-T02): files end when full and the next starts at the following frame,
    /// as PhoneCamera does, with the full file finishing while the next one records. Killed in the
    /// fourth file, the app keeps the three before it whole: what is lost is at most one file's length.
    /// A 2 s file stands for the app's 5 min.
    func testFullFilesBoundWhatAKilledAppLoses() async throws {
        XCTAssertEqual(PhoneFileLength.seconds, 300)
        XCTAssertFalse(PhoneFileLength.full(startedAt: 10, now: 309.9))
        XCTAssertTrue(PhoneFileLength.full(startedAt: 10, now: 310))
        let dir = try Self.folder()
        defer { try? FileManager.default.removeItem(at: dir) }
        let limit = 2.0
        var finished: [(URL, Task<AVAssetWriter.Status, Never>)] = []
        var current: File?
        var startedAt = 0.0
        for i in 0..<(7 * Int(Self.fps)) {
            let pts = CMTime(value: CMTimeValue(i), timescale: Self.fps)
            if let file = current, PhoneFileLength.full(startedAt: startedAt, now: pts.seconds, limit: limit) {
                // Finished on its own, as finishFile does: the next file does not wait for it.
                let writer = file.writer
                file.markFinished()
                finished.append((file.url, Task { await writer.finishWriting(); return writer.status }))
                current = nil
            }
            if current == nil {
                current = try File(url: dir.appendingPathComponent(String(format: "phone_%04d.mp4", finished.count + 1)), at: pts)
                startedAt = pts.seconds
            }
            try current?.append(frame: i)
        }
        let killed = try XCTUnwrap(current)
        let cut = dir.appendingPathComponent("cut.mp4")
        let cutSeconds = try await Self.cutOff(killed.url, to: cut, enough: 0.5)
        XCTAssertEqual(finished.count, 3)
        var kept = 0.0
        for (url, task) in finished {
            let status = await task.value
            XCTAssertEqual(status, .completed, url.lastPathComponent)
            let seconds = await Self.videoSeconds(url)
            XCTAssertEqual(seconds, limit, accuracy: 0.2, "\(url.lastPathComponent) plays whole")
            kept += seconds
        }
        XCTAssertEqual(kept, 6, accuracy: 0.3, "every full file is kept")
        XCTAssertEqual(kept + cutSeconds, 7, accuracy: limit + 0.3, "at most one file's length is lost")
        if !PhoneCamera.fragmented { XCTAssertEqual(cutSeconds, 0, "the file being written when killed does not play") }
        await killed.writer.finishWriting()
    }

    static let fps: Int32 = 30

    /// One file written as PhoneCamera writes it, starting at the frame at [at].
    final class File {
        let url: URL
        let writer: AVAssetWriter
        private let input: AVAssetWriterInput
        private let adaptor: AVAssetWriterInputPixelBufferAdaptor

        init(url: URL, at start: CMTime) throws {
            self.url = url
            writer = try PhoneCamera.makeWriter(url: url)
            let settings = PhoneCamera.videoSettings(codec: .h264, width: 320, height: 180, fps: PhoneVideoFinishingTests.fps)
            input = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
            input.expectsMediaDataInRealTime = true
            adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: 320,
                kCVPixelBufferHeightKey as String: 180,
            ])
            XCTAssertTrue(writer.canAdd(input))
            writer.add(input)
            XCTAssertTrue(writer.startWriting(), String(describing: writer.error))
            writer.startSession(atSourceTime: start)
        }

        /// No more frames, as finishFile marks the inputs.
        func markFinished() { input.markAsFinished() }

        /// Frame [i] of the session, at i / fps.
        func append(frame i: Int) throws {
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
            let pts = CMTime(value: CMTimeValue(i), timescale: PhoneVideoFinishingTests.fps)
            XCTAssertTrue(adaptor.append(frame, withPresentationTime: pts), String(describing: writer.error))
        }
    }

    private static func folder() throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// The playable length of a copy of [url] as it is on disk, taken again until it plays [enough]
    /// seconds or 15 s pass (the writer writes on its own queue).
    private static func cutOff(_ url: URL, to cut: URL, enough: Double) async throws -> Double {
        var seconds = 0.0
        // Without fragments nothing that plays comes before the finish: a few looks are enough.
        for _ in 0..<(PhoneCamera.fragmented ? 30 : 3) where seconds < enough {
            try? FileManager.default.removeItem(at: cut)
            try FileManager.default.copyItem(at: url, to: cut)
            seconds = await videoSeconds(cut)
            if seconds < enough { try await Task.sleep(nanoseconds: 500_000_000) }
        }
        return seconds
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

/// FBBD-468 (audit 7 Nov BD-T08): a file's finish holds background time until finishWriting answers.
final class BackgroundTimeTests: XCTestCase {
    private final class Fake {
        var begun: [String] = []
        var ended: [UIBackgroundTaskIdentifier] = []
        var expire: (() -> Void)?
        lazy var time = BackgroundTime(
            begin: { name, expired in
                XCTAssertTrue(Thread.isMainThread)
                self.begun.append(name)
                self.expire = expired
                return UIBackgroundTaskIdentifier(rawValue: self.begun.count)
            },
            end: { id in
                XCTAssertTrue(Thread.isMainThread)
                self.ended.append(id)
            }
        )
    }

    private func drainMain() {
        let done = expectation(description: "main queue")
        DispatchQueue.main.async { DispatchQueue.main.async { done.fulfill() } }
        wait(for: [done], timeout: 5)
    }

    func testTheTaskEndsOnceWhenTheFinishAnswers() {
        let fake = Fake()
        let release = fake.time.hold("Finish phone_0001.mp4")
        drainMain()
        XCTAssertEqual(fake.begun, ["Finish phone_0001.mp4"])
        XCTAssertTrue(fake.ended.isEmpty, "held until the finish answers")
        DispatchQueue.global().async { release() }
        let answered = expectation(description: "ended")
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.2) { answered.fulfill() }
        wait(for: [answered], timeout: 5)
        drainMain()
        XCTAssertEqual(fake.ended, [UIBackgroundTaskIdentifier(rawValue: 1)])
        release()
        drainMain()
        XCTAssertEqual(fake.ended.count, 1, "a second answer ends nothing more")
    }

    func testTheTaskEndsWhenTheTimeIsUpAndNotAgainAtTheAnswer() {
        let fake = Fake()
        let release = fake.time.hold("Finish phone_0002.mp4")
        drainMain()
        fake.expire?()
        XCTAssertEqual(fake.ended, [UIBackgroundTaskIdentifier(rawValue: 1)])
        release()
        drainMain()
        XCTAssertEqual(fake.ended.count, 1)
    }

    func testAFinishThatAnswersAtOnceStillEndsItsTask() {
        let fake = Fake()
        fake.time.hold("Finish phone_0003.mp4")()
        drainMain()
        XCTAssertEqual(fake.begun.count, fake.ended.count, "every task begun is ended")
    }
}
