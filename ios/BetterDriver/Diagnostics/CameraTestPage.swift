import AVFoundation
import BetterDriverCore
import SwiftUI
import UIKit

/// Where the camera test stands, as Android's CameraTestState (FBBD-278): the part that runs, when it
/// ends, and each part's camera result once it has one.
struct CameraTestStatus: Equatable {
    enum Phase: Equatable { case running, done, stopped, failed }

    var phase: Phase
    var part: Int
    var partEnds: Date = .distantPast
    var first: CameraTestPart?
    var second: CameraTestPart?
    var note: String?
}

/// The camera test on the iPhone (FBBD-371), Android's plan of 2026-10-21 (FBBD-278): the back camera
/// at 1080p · 60 twice in a row with the same settings, sound included when the microphone is
/// allowed, 12 minutes each. Part 1 writes the MP4 as a session does; part 2 takes the same frames
/// and writes no file. Once a minute and at the end of each part the log gets the camera's rate and
/// the frames it skipped, in the shared lines (CameraTestText) Android writes; part 1's video is
/// deleted at the end. No session records meanwhile.
final class CameraTestRun: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureAudioDataOutputSampleBufferDelegate {
    private let queue = DispatchQueue(label: "com.flappedear.betterdriver.cameratest", qos: .userInitiated)
    private let capture = AVCaptureSession()
    private let videoOut = AVCaptureVideoDataOutput()
    private let audioOut = AVCaptureAudioDataOutput()
    private let dir = FileManager.default.temporaryDirectory.appendingPathComponent("camera-test", isDirectory: true)
    private let onStatus: (CameraTestStatus) -> Void
    private let text = CameraTestText.shared
    private var observers: [NSObjectProtocol] = []

    // Everything below is used on [queue] only.
    private var status = CameraTestStatus(phase: .running, part: 1)
    private var results: [CameraTestPart?] = [nil, nil]
    private var hasAudio = false
    private var configured = false
    private var over = false
    private var stopReason: String?
    private var writer: AVAssetWriter?
    private var videoIn: AVAssetWriterInput?
    private var audioIn: AVAssetWriterInput?
    private var firstPts: CMTime = .invalid
    private var lastPts: CMTime = .invalid
    private var frames: Int64 = 0
    private var skipped: Int64 = 0
    private var minuteTimer: DispatchSourceTimer?
    private var endTimer: DispatchSourceTimer?
    private var minutes = 0
    /// A part takes frames: between parts none counts, and no file opens.
    private var partRunning = false

    private static let mode = PhoneVideoMode.standard

    /// [onStatus] runs on the main queue.
    init(onStatus: @escaping (CameraTestStatus) -> Void) {
        self.onStatus = onStatus
        super.init()
    }

    /// Starts the test. Call on the main queue.
    func start() {
        // No camera (the simulator) or no 1080p · 60: the test cannot run, without asking for access.
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let format = PhoneVideoMode.format(of: device, for: Self.mode) else {
            queue.async { self.end(.failed, "this phone has no 1080p · 60 camera") }
            return
        }
        AVCaptureDevice.requestAccess(for: .video) { video in
            guard video else {
                self.queue.async { self.end(.failed, "camera access is off") }
                return
            }
            AVCaptureDevice.requestAccess(for: .audio) { audio in
                self.queue.async { self.begin(device: device, format: format, audio: audio) }
            }
        }
        let center = NotificationCenter.default
        observers = [
            center.addObserver(forName: AVCaptureSession.wasInterruptedNotification, object: capture, queue: nil) { [weak self] _ in
                self?.stop("the camera was interrupted")
            },
            center.addObserver(forName: AVCaptureSession.runtimeErrorNotification, object: capture, queue: nil) { [weak self] _ in
                self?.queue.async { self?.partEnded(error: "camera error") }
            },
        ]
    }

    /// Ends the test early; the running part finishes its file first.
    func stop(_ reason: String) {
        queue.async {
            guard !self.over, self.stopReason == nil else { return }
            self.stopReason = reason
            AppLog.i(self.text.TAG, self.text.stopping(reason: reason))
            self.partEnded(error: nil)
        }
    }

    private func begin(device: AVCaptureDevice, format: AVCaptureDevice.Format, audio: Bool) {
        guard !over else { return }
        do {
            capture.beginConfiguration()
            defer { capture.commitConfiguration() }
            capture.sessionPreset = .inputPriority
            let input = try AVCaptureDeviceInput(device: device)
            guard capture.canAddInput(input) else { throw TestError.notSupported }
            capture.addInput(input)
            try device.lockForConfiguration()
            device.activeFormat = format
            device.activeVideoMinFrameDuration = CMTime(value: 1, timescale: Self.mode.fps)
            device.activeVideoMaxFrameDuration = CMTime(value: 1, timescale: Self.mode.fps)
            device.unlockForConfiguration()
            videoOut.alwaysDiscardsLateVideoFrames = false
            videoOut.setSampleBufferDelegate(self, queue: queue)
            guard capture.canAddOutput(videoOut) else { throw TestError.notSupported }
            capture.addOutput(videoOut)
            capture.automaticallyConfiguresApplicationAudioSession = false
            if audio, Self.recordAudio(), let mic = AVCaptureDevice.default(for: .audio),
               let micInput = try? AVCaptureDeviceInput(device: mic), capture.canAddInput(micInput),
               capture.canAddOutput(audioOut) {
                capture.addInput(micInput)
                audioOut.setSampleBufferDelegate(self, queue: queue)
                capture.addOutput(audioOut)
                hasAudio = true
            }
            configured = true
        } catch {
            AppLog.w(text.TAG, "camera test: no camera: \(error.localizedDescription)")
            end(.failed, "the camera could not be opened")
            return
        }
        capture.startRunning()
        startPart(1)
    }

    private func startPart(_ n: Int) {
        guard !over else { return }
        status.part = n
        frames = 0
        skipped = 0
        firstPts = .invalid
        lastPts = .invalid
        minutes = 0
        partRunning = true
        let bitrate = PhoneCamera.videoSettings(codec: .h264, width: 1920, height: 1080, fps: Self.mode.fps)
            .compressionBitrate
        AppLog.i(text.TAG, text.partStarted(part: Int32(n), mode: Self.mode.label, bitrate: bitrate, sound: hasAudio))
        let length = Double(text.PART_MINUTES) * 60
        status.partEnds = Date().addingTimeInterval(length)
        endTimer = timer(after: length, repeating: nil) { [weak self] in self?.partEnded(error: nil) }
        minuteTimer = timer(after: 60, repeating: 60) { [weak self] in self?.logMinute() }
        report()
    }

    private func timer(after: Double, repeating: Double?, _ action: @escaping () -> Void) -> DispatchSourceTimer {
        let t = DispatchSource.makeTimerSource(queue: queue)
        if let repeating { t.schedule(deadline: .now() + after, repeating: repeating) } else { t.schedule(deadline: .now() + after) }
        t.setEventHandler(handler: action)
        t.resume()
        return t
    }

    private func current() -> CameraTestPart? {
        guard firstPts.isValid, lastPts.isValid else { return nil }
        let seconds = CMTimeGetSeconds(CMTimeSubtract(lastPts, firstPts))
        // The frames after the first over the time between the first and the last.
        return CameraTestPart.companion.of(frames: frames - 1, skipped: skipped, seconds: seconds)
    }

    /// The part's rate so far, once a minute, as a session's video logs its frames.
    private func logMinute() {
        minutes += 1
        AppLog.i(
            text.TAG,
            "camera test part \(status.part) minute \(minutes): \(current()?.text ?? "no frames")" +
                (status.part == 1 ? " · file \(writer?.status == .writing ? "writing" : "not writing")" : "")
        )
        report()
    }

    /// The part ended, as planned, stopped or on an error: the next part, or the end of the test.
    private func partEnded(error: String?) {
        guard !over else { return }
        partRunning = false
        endTimer?.cancel()
        minuteTimer?.cancel()
        let n = status.part
        let result = current()
        results[n - 1] = result
        finishFile()
        AppLog.i(text.TAG, text.partEnded(part: Int32(n), result: result, error: error))
        if let error {
            end(.failed, "part \(n): \(error)")
        } else if let stopReason {
            end(.stopped, stopReason)
        } else if n < Int(text.PARTS) {
            report()
            queue.asyncAfter(deadline: .now() + 1) { self.startPart(n + 1) }
        } else {
            end(.done, nil)
        }
    }

    private func end(_ phase: CameraTestStatus.Phase, _ note: String?) {
        guard !over else { return }
        over = true
        endTimer?.cancel()
        minuteTimer?.cancel()
        finishFile()
        if configured { capture.stopRunning() }
        let name: String
        switch phase {
        case .done: name = "Done"
        case .stopped: name = "Stopped"
        case .failed: name = "Failed"
        case .running: name = "Running"
        }
        AppLog.i(text.TAG, text.ended(status: name, first: results[0], second: results[1], note: note))
        try? FileManager.default.removeItem(at: dir)
        status.phase = phase
        status.note = note
        report()
        let observers = self.observers
        DispatchQueue.main.async { observers.forEach { NotificationCenter.default.removeObserver($0) } }
    }

    private func report() {
        var s = status
        let now = status.phase == .running ? current() : nil
        s.first = results[0] ?? (status.part == 1 ? now : nil)
        s.second = results[1] ?? (status.part == 2 ? now : nil)
        let onStatus = self.onStatus
        DispatchQueue.main.async { onStatus(s) }
    }

    // MARK: Frames

    func captureOutput(_ output: AVCaptureOutput, didOutput sample: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard !over, partRunning else { return }
        let pts = CMSampleBufferGetPresentationTimeStamp(sample)
        if output === videoOut {
            if !firstPts.isValid { firstPts = pts }
            lastPts = pts
            frames += 1
            guard CameraTestText.shared.withMp4(part: Int32(status.part)) else { return }
            if writer == nil { open(at: pts, frame: sample) }
            guard let writer, writer.status == .writing, let videoIn else { return }
            // A frame the file is not ready for is skipped, as in a session.
            if !videoIn.isReadyForMoreMediaData || !videoIn.append(sample) { skipped += 1 }
        } else if output === audioOut, let writer, writer.status == .writing, let audioIn,
                  CMTimeCompare(pts, firstPts) >= 0, audioIn.isReadyForMoreMediaData {
            _ = audioIn.append(sample)
        }
    }

    /// A frame the camera dropped before it reached the app: skipped.
    func captureOutput(_ output: AVCaptureOutput, didDrop sample: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard output === videoOut, !over, partRunning else { return }
        skipped += 1
    }

    /// Part 1's MP4, with the settings a session's file gets (PhoneCamera.videoSettings).
    private func open(at pts: CMTime, frame: CMSampleBuffer) {
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let w = try AVAssetWriter(outputURL: dir.appendingPathComponent("part1.mp4"), fileType: .mp4)
            guard let description = CMSampleBufferGetFormatDescription(frame) else { throw TestError.notSupported }
            let dims = CMVideoFormatDescriptionGetDimensions(description)
            let settings = PhoneCamera.videoSettings(codec: .h264, width: dims.width, height: dims.height, fps: Self.mode.fps)
            guard w.canApply(outputSettings: settings, forMediaType: .video) else { throw TestError.notSupported }
            let video = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
            video.expectsMediaDataInRealTime = true
            guard w.canAdd(video) else { throw TestError.notSupported }
            w.add(video)
            if hasAudio, let sound = audioOut.recommendedAudioSettingsForAssetWriter(writingTo: .mp4),
               w.canApply(outputSettings: sound, forMediaType: .audio) {
                let a = AVAssetWriterInput(mediaType: .audio, outputSettings: sound)
                a.expectsMediaDataInRealTime = true
                if w.canAdd(a) {
                    w.add(a)
                    audioIn = a
                }
            }
            guard w.startWriting() else { throw w.error ?? TestError.notSupported }
            w.startSession(atSourceTime: pts)
            writer = w
            videoIn = video
        } catch {
            AppLog.w(text.TAG, "camera test part 1: the MP4 could not start: \(error.localizedDescription)")
            partEnded(error: "the MP4 could not start")
        }
    }

    private func finishFile() {
        guard let writer else { return }
        if writer.status == .writing {
            videoIn?.markAsFinished()
            audioIn?.markAsFinished()
            writer.finishWriting { [dir] in try? FileManager.default.removeItem(at: dir) }
        }
        self.writer = nil
        videoIn = nil
        audioIn = nil
    }

    private static func recordAudio() -> Bool {
        let audio = AVAudioSession.sharedInstance()
        do {
            try audio.setCategory(.playAndRecord, mode: .videoRecording, options: [.defaultToSpeaker, .allowBluetoothA2DP])
            try audio.setActive(true)
            return true
        } catch {
            return false
        }
    }

    private enum TestError: Error { case notSupported }
}

private extension Dictionary where Key == String, Value == Any {
    /// The video bitrate in the settings PhoneCamera.videoSettings makes.
    var compressionBitrate: Int64 {
        let props = self[AVVideoCompressionPropertiesKey] as? [String: Any]
        return (props?[AVVideoAverageBitRateKey] as? NSNumber)?.int64Value ?? 0
    }
}

/// Holds the test while its page is open (the page keeps the screen on while it runs).
@MainActor
final class CameraTestModel: ObservableObject {
    @Published private(set) var status: CameraTestStatus?
    @Published private(set) var starting = false
    private var run: CameraTestRun?

    func start() {
        guard run == nil else { return }
        starting = true
        let r = CameraTestRun { [weak self] s in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.starting = false
                self.status = s
                UIApplication.shared.isIdleTimerDisabled = s.phase == .running
                if s.phase != .running { self.run = nil }
            }
        }
        run = r
        r.start()
    }

    func stop(_ reason: String) { run?.stop(reason) }

    func leave() {
        run?.stop("the page was closed")
        UIApplication.shared.isIdleTimerDisabled = false
    }
}

/// Settings › Diagnostics › Camera test, as on Android (FBBD-278, UI-150 A, UI-151 A, UI-152 A; iOS
/// FBBD-371): the start page, then the part, its progress and the camera's rate per part, at the end
/// both parts side by side and Share log, where the full timing is.
struct CameraTestPage: View {
    @StateObject private var model = CameraTestModel()
    @EnvironmentObject private var recorder: RecordingController
    @Environment(\.scenePhase) private var phase
    @State private var now = Date()
    @State private var busy = false
    @State private var problem: String?
    private let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()
    private let text = CameraTestText.shared

    var body: some View {
        ShellPage(title: "Camera test") {
            if let s = model.status, s.phase == .running {
                running(s)
            } else if let s = model.status {
                ended(s)
            } else {
                start
            }
        }
        .onReceive(tick) { now = $0 }
        .onDisappear { model.leave() }
        .onChange(of: phase) { _, p in if p == .background { model.stop("the app went to the background") } }
    }

    private var start: some View {
        let minutes = Int(text.PART_MINUTES)
        let camera = AVCaptureDevice.authorizationStatus(for: .video) != .denied
        let sound = AVCaptureDevice.authorizationStatus(for: .audio) != .denied
        return VStack(alignment: .leading, spacing: 12) {
            DimText(
                "Records 1080p · 60 twice with the same settings, \(sound ? "sound included" : "without sound"). " +
                    "Part 1 writes the MP4 as normal. Part 2 takes the same frames but writes no file. " +
                    "Every step is timed in the log."
            )
            ShellCard {
                ListRow("Part 1 · with MP4", value: "\(minutes) min", divider: false)
                ListRow("Part 2 · without MP4", value: "\(minutes) min")
                ListRow("Total", value: "\(minutes * Int(text.PARTS)) min")
            }
            DimText("Leave the phone in its mount, screen on. No session is recorded meanwhile; part 1's video is deleted afterwards.")
            if !camera { DimText("The camera permission is off.", color: Palette.loss) }
            if !sound { DimText("The microphone permission is off, so neither part records sound.") }
            if recorder.isRecording { DimText("A session is recording. Stop it first.", color: Palette.loss) }
            Button { model.start() } label: {
                Text(model.starting ? "Starting…" : "Start test").fontWeight(.semibold).frame(maxWidth: .infinity).padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
            .tint(Palette.accent)
            .foregroundStyle(Palette.onAccent)
            .disabled(!camera || model.starting || recorder.isRecording)
            .accessibilityIdentifier("cameraTest.start")
        }
    }

    private func running(_ s: CameraTestStatus) -> some View {
        let total = Double(text.PART_MINUTES) * 60
        let left = min(max(s.partEnds.timeIntervalSince(now), 0), total)
        return VStack(alignment: .leading, spacing: 12) {
            ShellLabel("Part \(s.part) of \(text.PARTS) · \(text.partName(part: Int32(s.part)))")
            ProgressView(value: 1 - left / total).tint(Palette.accent)
            DimText(text.left(millis: Int64(left * 1000)))
            ShellCard {
                if let first = s.first {
                    ListRow(s.part == 1 ? "Part 1 so far" : "Part 1 · with MP4", divider: false, supporting: first.text)
                }
                if s.part == 2, let second = s.second { ListRow("Part 2 so far", supporting: second.text) }
            }
            Button { model.stop("stopped on the test page") } label: {
                Text("Stop test").foregroundStyle(Palette.loss).frame(maxWidth: .infinity).padding(.vertical, 6)
            }
            .buttonStyle(.bordered)
            .tint(Palette.loss)
            .accessibilityIdentifier("cameraTest.stop")
        }
    }

    private func ended(_ s: CameraTestStatus) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            ShellLabel(s.phase == .done ? "Done" : s.phase == .stopped ? "Stopped" : "Could not run")
                .accessibilityIdentifier("cameraTest.result")
            ShellCard {
                ListRow("Part 1 · with MP4", divider: false, supporting: s.first?.text ?? "no result")
                ListRow("Part 2 · without MP4", supporting: s.second?.text ?? "no result")
            }
            if let note = s.note { DimText(note.prefix(1).uppercased() + note.dropFirst() + ".") }
            DimText("The full timing is in the log. Share it from Diagnostics.")
            Button { share() } label: {
                Text(busy ? "Preparing…" : "Share log").fontWeight(.semibold).frame(maxWidth: .infinity).padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
            .tint(Palette.accent)
            .foregroundStyle(Palette.onAccent)
            if let problem { DimText(problem, color: Palette.loss) }
        }
    }

    private func share() {
        guard !busy else { return }
        busy = true
        problem = nil
        Task {
            let result = await Task.detached { Result(catching: { try LogShare.prepare() }) }.value
            busy = false
            switch result {
            case .success(let zip): LogShare.share(zip)
            case .failure(let error):
                AppLog.w("CameraTest", "could not prepare the log: \(error.localizedDescription)")
                problem = "Could not prepare the log: \(error.localizedDescription)"
            }
        }
    }
}
