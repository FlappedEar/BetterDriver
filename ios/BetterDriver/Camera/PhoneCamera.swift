import AVFoundation
import BetterDriverCore
import UIKit

/// The camera line on the track screen's status strip (UI-07, as on Android): "1080p · 60" in green
/// while the camera records, amber when it does not.
struct CameraStatus: Equatable {
    let text: String
    let ok: Bool
    /// Set while the phone records no video because of a fault, said on the track screen's alert
    /// line (FBBD-468, audit 7 Nov BD-T04): an amber camera icon alone was easy to miss while driving.
    var warning: String? = nil

    /// The alert line's text while the camera retries after a fault.
    static let retrying = "Retrying, data still recording"
    /// The alert line's text when the camera cannot record in this session.
    static let off = "Off this session, data still recording"
}

/// Phone video with a session on the iPhone, as the Android phone camera (FBBD-21): the back wide
/// camera in the chosen mode (1080p · 60 by default; a smaller one when the phone cannot record it,
/// as Android's ladder), H.264 with the microphone's sound,
/// into the session's video folder as phone_0001.mp4 and on. Every frame's capture time is known on
/// the session clock from its timestamp, so the file is placed on the session by its frame times
/// (PhoneVideoLog), the same way review and VBO export place Android's files.
///
/// iOS stops the camera while the app is in the background or another app takes it: the file ends
/// there ("interrupted"), and a new file starts when the camera comes back. A camera fault never
/// stops the data recording.
final class PhoneCamera: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureAudioDataOutputSampleBufferDelegate {
    private let mode: PhoneVideoMode
    private let log: PhoneVideoLog
    private let onStatus: (CameraStatus) -> Void
    private let queue = DispatchQueue(label: "com.flappedear.betterdriver.camera", qos: .userInitiated)
    private let capture = AVCaptureSession()
    private let videoOut = AVCaptureVideoDataOutput()
    private let audioOut = AVCaptureAudioDataOutput()
    private var observers: [NSObjectProtocol] = []
    /// Says which way up the phone is held; watched so the next file follows it ([landscape]).
    private var rotationCoordinator: AVCaptureDevice.RotationCoordinator?
    private var rotationWatch: NSKeyValueObservation?
    /// Main queue: [stop] came, possibly while the access prompt was still open.
    private var stopped = false

    // Everything below is used on [queue] only.
    private var running = false
    private var configured = false
    /// The mode the camera got: [mode], or a step down its ladder.
    private var recording = PhoneVideoMode.standard
    private var rotation: CGFloat = 0
    private var hasAudio = false
    private var writer: AVAssetWriter?
    private var videoIn: AVAssetWriterInput?
    private var audioIn: AVAssetWriterInput?
    private var file: String?
    private var fileIndex: Int32 = 0
    private var firstPts: CMTime = .invalid
    private var lastSyncPts: CMTime = .invalid
    /// The current file's frames: taken, dropped, late (BD-01).
    private var counts = PhoneFrameCounts()
    /// When the next file may start after failed ones (FBBD-441): never given up for the session.
    private var retry = PhoneFileRetry()
    /// The current file's start and what it took, for the line logged when it fails (FBBD-441).
    private var fileStartedAt = 0.0
    private var lastVideoPts: CMTime = .invalid
    private var lastAudioPts: CMTime = .invalid
    private var audioAppended = 0
    private var audioRefused = 0

    /// [onStatus] runs on the main queue.
    init(log: PhoneVideoLog, mode: PhoneVideoMode, onStatus: @escaping (CameraStatus) -> Void) {
        self.mode = mode
        self.log = log
        self.onStatus = onStatus
        super.init()
    }

    /// Asks for camera and microphone access the first time, then starts. Call on the main queue.
    func start() {
        AVCaptureDevice.requestAccess(for: .video) { video in
            guard video else {
                AppLog.w(Self.tag, "no camera access: no phone video")
                self.status("No access", ok: false, warning: CameraStatus.off)
                return
            }
            // Sound is optional: without the microphone the video records silent.
            AVCaptureDevice.requestAccess(for: .audio) { audio in
                DispatchQueue.main.async { self.begin(audio: audio) }
            }
        }
    }

    /// Ends the file now, logged before the session ends. Call before the session stops; safe to call twice.
    func stop() {
        // A second call (the recording's end calls it again) returns at once: it would otherwise wait
        // on the main queue for the camera to stop running, which made leaving the recording slow.
        guard !stopped else { return }
        stopped = true
        queue.sync {
            running = false
            finishFile(reason: "user")
        }
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers.removeAll()
        rotationWatch = nil
        rotationCoordinator = nil
        let capture = self.capture
        queue.async { capture.stopRunning() }
    }

    private func begin(audio: Bool) {
        guard !stopped else { return }
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
            // No back camera at all (the simulator; every iPhone has one): amber only, no alert, as before.
            AppLog.w(Self.tag, "no back camera: no phone video")
            status("Not supported", ok: false)
            return
        }
        // Video is always landscape, as the track screen (Arek): the file's orientation (the frames
        // themselves are not turned) is the landscape side the phone is held on, 0° or 180°. Held
        // upright or flat, the last landscape side stays, at first the camera's own (0°): a turn of
        // 90° made the car's video play sideways in a narrow upright frame.
        let coordinator = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: nil)
        let angle = Self.landscape(coordinator.videoRotationAngleForHorizonLevelCapture) ?? 0
        rotationCoordinator = coordinator
        rotationWatch = coordinator.observe(\.videoRotationAngleForHorizonLevelCapture, options: [.new]) { [weak self] c, _ in
            guard let self, let turned = Self.landscape(c.videoRotationAngleForHorizonLevelCapture) else { return }
            self.queue.async { self.rotation = turned }
        }
        let center = NotificationCenter.default
        observers = [
            center.addObserver(forName: AVCaptureSession.wasInterruptedNotification, object: capture, queue: nil) { [weak self] _ in
                self?.interrupted()
            },
            center.addObserver(forName: AVCaptureSession.interruptionEndedNotification, object: capture, queue: nil) { [weak self] _ in
                self?.status("Restarting", ok: false)
            },
            center.addObserver(forName: AVCaptureSession.runtimeErrorNotification, object: capture, queue: nil) { [weak self] _ in
                self?.failed()
            },
        ]
        queue.async {
            self.running = true
            self.rotation = angle
            self.hasAudio = audio
            self.startCapture(device, attempt: 1)
        }
    }

    /// Times the camera is set up before the session goes on without video (FBBD-468, BD-T04).
    static let configureAttempts = 3

    /// Sets the camera up and starts it; a failure that may pass (the camera busy, a lock refused) is
    /// tried again after 2 s, up to [configureAttempts] times. A mode the phone has not is not retried.
    private func startCapture(_ device: AVCaptureDevice, attempt: Int) {
        guard running else { return }
        do {
            try configure(device)
        } catch {
            let unsupported = (error as? CameraError) == .notSupported
            AppLog.w(Self.tag, "camera not set up (attempt \(attempt) of \(Self.configureAttempts)): \(error)")
            unconfigure()
            if !unsupported, attempt < Self.configureAttempts {
                status("Restarting", ok: false, warning: CameraStatus.retrying)
                queue.asyncAfter(deadline: .now() + 2) { self.startCapture(device, attempt: attempt + 1) }
                return
            }
            running = false
            status("Camera error", ok: false, warning: CameraStatus.off)
            return
        }
        capture.startRunning()
    }

    /// Takes off what a failed [configure] added, so it can be tried again.
    private func unconfigure() {
        capture.beginConfiguration()
        capture.inputs.forEach { capture.removeInput($0) }
        capture.outputs.forEach { capture.removeOutput($0) }
        capture.commitConfiguration()
    }

    /// [angle] when it is a landscape side (0° or 180°), else nil.
    static func landscape(_ angle: CGFloat) -> CGFloat? {
        let a = (Int(angle.rounded()) % 360 + 360) % 360
        return a == 0 || a == 180 ? CGFloat(a) : nil
    }

    private func configure(_ device: AVCaptureDevice) throws {
        capture.beginConfiguration()
        defer { capture.commitConfiguration() }
        capture.sessionPreset = .inputPriority
        let input = try AVCaptureDeviceInput(device: device)
        guard capture.canAddInput(input) else { throw CameraError.notSupported }
        capture.addInput(input)
        let pick = mode.ladder.lazy
            .compactMap { m in PhoneVideoMode.format(of: device, for: m).map { ($0, m) } }
            .first
        guard let pick else { throw CameraError.notSupported }
        let (format, got) = pick
        try device.lockForConfiguration()
        device.activeFormat = format
        device.activeVideoMinFrameDuration = CMTime(value: 1, timescale: got.fps)
        device.activeVideoMaxFrameDuration = CMTime(value: 1, timescale: got.fps)
        device.unlockForConfiguration()
        recording = got
        videoOut.alwaysDiscardsLateVideoFrames = false
        videoOut.setSampleBufferDelegate(self, queue: queue)
        guard capture.canAddOutput(videoOut) else { throw CameraError.notSupported }
        capture.addOutput(videoOut)
        // The app sets its own audio session, so the coach's voice keeps going to the headset or the
        // speaker while the microphone records (the capture session's own setting would not allow A2DP).
        capture.automaticallyConfiguresApplicationAudioSession = false
        if hasAudio, Self.recordAudio(), let mic = AVCaptureDevice.default(for: .audio),
           let micInput = try? AVCaptureDeviceInput(device: mic), capture.canAddInput(micInput),
           capture.canAddOutput(audioOut) {
            capture.addInput(micInput)
            audioOut.setSampleBufferDelegate(self, queue: queue)
            capture.addOutput(audioOut)
        } else {
            hasAudio = false
        }
        configured = true
    }

    /// Sound from the phone's microphone, with the coach still heard over Bluetooth, the car or the speaker.
    private static func recordAudio() -> Bool {
        let audio = AVAudioSession.sharedInstance()
        do {
            try audio.setCategory(
                .playAndRecord, mode: .videoRecording,
                options: [.defaultToSpeaker, .allowBluetoothA2DP, .duckOthers]
            )
            try audio.setActive(true)
            return true
        } catch {
            return false
        }
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sample: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard running else { return }
        let pts = CMSampleBufferGetPresentationTimeStamp(sample)
        if output === videoOut {
            // A writer that failed on its own (a full disk, a failed sound sample) ends its file; a
            // later frame starts a new one, after [retry]'s wait (FBBD-441).
            if let writer, writer.status == .failed || writer.status == .cancelled {
                failedFile(input: "writer", frame: sample)
            }
            // A full file is finished and the next starts with this frame (FBBD-468, BD-T02): a killed
            // app loses at most the file it was writing. Off until after Moto Park (PhoneFileLength.on).
            if PhoneFileLength.on, let writer, writer.status == .writing,
               PhoneFileLength.full(startedAt: fileStartedAt, now: Self.seconds(pts)) {
                finishFile(reason: "file_full")
            }
            if writer == nil, running, retry.mayOpen(at: Self.seconds(pts)) { open(at: pts, frame: sample) }
            guard let writer, writer.status == .writing, let videoIn else { return }
            // A frame the writer is not ready for is counted and left out; only a frame in the file
            // may be named by a video_sync (audit 30 Oct BD-01).
            switch counts.frame(ready: videoIn.isReadyForMoreMediaData, append: { videoIn.append(sample) }) {
            case .appended:
                lastVideoPts = pts
                sync(at: pts)
            case .dropped: break
            case .failed: failedFile(input: "video", frame: sample)
            }
        } else if output === audioOut, let writer, writer.status == .writing, let audioIn,
                  CMTimeCompare(pts, firstPts) >= 0, audioIn.isReadyForMoreMediaData {
            if audioIn.append(sample) {
                audioAppended += 1
                lastAudioPts = pts
            } else {
                audioRefused += 1
            }
        }
    }

    /// A frame the camera dropped before it reached the app (it came late): counted for the file's log line.
    func captureOutput(_ output: AVCaptureOutput, didDrop sample: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard output === videoOut, writer != nil else { return }
        counts.late += 1
    }

    /// Starts the next file at the frame captured at [pts]; [frame] gives its size.
    private func open(at pts: CMTime, frame: CMSampleBuffer) {
        let dir = URL(fileURLWithPath: log.videoDir, isDirectory: true)
        // A name no file has yet: AVAssetWriter refuses a file that is there, and one is never overwritten.
        var name: String
        repeat {
            fileIndex += 1
            name = log.fileName(index: fileIndex)
        } while FileManager.default.fileExists(atPath: dir.appendingPathComponent(name).path)
        let url = dir.appendingPathComponent(name)
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let w = try Self.makeWriter(url: url)
            // Settings the writer refuses would also throw in the input: no video then, never a crash.
            let settings = videoSettings(for: frame)
            guard w.canApply(outputSettings: settings, forMediaType: .video) else { throw CameraError.notSupported }
            let video = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
            video.expectsMediaDataInRealTime = true
            video.transform = CGAffineTransform(rotationAngle: rotation * .pi / 180)
            guard w.canAdd(video) else { throw CameraError.notSupported }
            w.add(video)
            var audio: AVAssetWriterInput?
            // Silent rather than a crash when the writer would refuse the microphone's settings.
            if hasAudio, let sound = audioOut.recommendedAudioSettingsForAssetWriter(writingTo: .mp4),
               w.canApply(outputSettings: sound, forMediaType: .audio) {
                let a = AVAssetWriterInput(mediaType: .audio, outputSettings: sound)
                a.expectsMediaDataInRealTime = true
                if w.canAdd(a) {
                    w.add(a)
                    audio = a
                }
            }
            guard w.startWriting() else { throw w.error ?? CameraError.notSupported }
            w.startSession(atSourceTime: pts)
            writer = w
            videoIn = video
            audioIn = audio
            file = name
            firstPts = pts
            lastSyncPts = pts
            counts = PhoneFrameCounts()
            fileStartedAt = Self.seconds(pts)
            lastVideoPts = .invalid
            lastAudioPts = .invalid
            audioAppended = 0
            audioRefused = 0
            log.started(file: name, startMicros: sessionMicros(of: pts).frame, fps: recording.fps)
            AppLog.i(Self.tag, "\(name) writing: fragments \(Self.fragmented ? "on" : "off")")
            // Green at the chosen mode, amber once stepped down (UI-07).
            status(recording.label, ok: recording == mode)
        } catch {
            // Tried again after [retry]'s wait, as a file that failed while recording (FBBD-468, BD-T04);
            // until 1.33.6 this ended the session's video. The data recording carries on.
            writer = nil
            videoIn = nil
            audioIn = nil
            // What this attempt left (a file without frames) goes: the name was free before it.
            try? FileManager.default.removeItem(at: url)
            let now = Self.seconds(pts)
            retry.failed(startedAt: now, now: now)
            AppLog.w(Self.tag, "\(name) not opened: \(error); failed files \(retry.total), next file in \(Int(retry.wait)) s")
            status("Camera error", ok: false, warning: CameraStatus.retrying)
        }
    }

    /// The writer of a session file at [url], as the app records it; the tests make theirs here, so what
    /// they prove holds for the shipped files (FBBD-468, audit 7 Nov BD-T03).
    ///
    /// FBBD-441: fragments are off. The -11800/-16341 failures of 3 Nov (12 files lost in 34 min) came
    /// only after they were turned on (#476, 1 Nov); the drives of 30 Oct without them were clean. A file
    /// cut off by a killed app has no index and does not play; [PhoneFileLength] bounds that loss.
    static func makeWriter(url: URL) throws -> AVAssetWriter {
        let w = try AVAssetWriter(outputURL: url, fileType: .mp4)
        if fragmented { w.movieFragmentInterval = fragmentInterval }
        return w
    }

    /// How often the file is written as a playable fragment: a file cut off by a killed app or a crash
    /// during its finish loses at most this much at its end, instead of all of it (no MP4 index).
    static let fragmentInterval = CMTime(value: 2, timescale: 1)
    /// Whether session files are written in fragments (FBBD-441: off until a drive log clears them).
    static let fragmented = false

    /// The file's video settings, made from the frame's size and the mode's rate: H.264, or HEVC when
    /// the phone records this mode only in HEVC (a 4K or 10-bit format). Not the output's recommended
    /// settings: those throw an Objective-C exception, which Swift cannot catch, for a codec the output
    /// does not offer (the app crashed on the first frame, TestFlight of 2026-10-27).
    private func videoSettings(for frame: CMSampleBuffer) -> [String: Any] {
        let size = CMSampleBufferGetFormatDescription(frame).map(CMVideoFormatDescriptionGetDimensions)
        let offered = videoOut.availableVideoCodecTypesForAssetWriter(writingTo: .mp4)
        return Self.videoSettings(
            codec: !offered.contains(.h264) && offered.contains(.hevc) ? .hevc : .h264,
            width: size?.width ?? recording.size.width,
            height: size?.height ?? recording.size.height,
            fps: recording.fps
        )
    }

    /// [codec] at [width]×[height] and [fps], about 0.15 bit per pixel for H.264 (1080p · 60 ≈ 19 Mbit/s),
    /// two thirds of that for HEVC; a key frame every second.
    static func videoSettings(codec: AVVideoCodecType, width: Int32, height: Int32, fps: Int32) -> [String: Any] {
        let perPixel = codec == .hevc ? 0.1 : 0.15
        let bits = max(Int(Double(width) * Double(height) * Double(fps) * perPixel), 1_000_000)
        var compression: [String: Any] = [
            AVVideoAverageBitRateKey: bits,
            AVVideoExpectedSourceFrameRateKey: Int(fps),
            AVVideoMaxKeyFrameIntervalKey: Int(fps),
        ]
        if codec == .h264 { compression[AVVideoProfileLevelKey] = AVVideoProfileLevelH264HighAutoLevel }
        return [
            AVVideoCodecKey: codec,
            AVVideoWidthKey: Int(width),
            AVVideoHeightKey: Int(height),
            AVVideoCompressionPropertiesKey: compression,
        ]
    }

    /// About once a second: the media recorded so far and the session time of its newest frame.
    private func sync(at pts: CMTime) {
        guard let file, CMTimeGetSeconds(CMTimeSubtract(pts, lastSyncPts)) >= 1 else { return }
        lastSyncPts = pts
        let t = sessionMicros(of: pts)
        log.synced(file: file, atMicros: t.now, mediaMicros: Self.micros(CMTimeSubtract(pts, firstPts)), frameMicros: t.frame)
    }

    /// The session time now, and of the frame captured at [pts] on the capture clock.
    private func sessionMicros(of pts: CMTime) -> (now: Int64, frame: Int64) {
        let clock = capture.synchronizationClock ?? CMClockGetHostTimeClock()
        let now = log.nowMicros()
        let age = Self.micros(CMTimeSubtract(CMClockGetTime(clock), pts))
        return (now, now - max(age, 0))
    }

    private static func micros(_ t: CMTime) -> Int64 {
        let us = CMTimeConvertScale(t, timescale: 1_000_000, method: .roundHalfAwayFromZero)
        return us.value
    }

    private func finishFile(reason: String) {
        guard let writer, let file else { return }
        log.stopped(file: file, reason: reason)
        AppLog.i(Self.tag, "\(file) ended (\(reason)): \(counts.text)")
        // A failed writer is left as it is: its inputs only take marks while it writes.
        if writer.status == .writing {
            videoIn?.markAsFinished()
            audioIn?.markAsFinished()
            // The MP4 gets its index only when finishWriting answers (audit 30 Oct BD-02): until then
            // the session's video is not offered, and its answer is checked and logged.
            let sessionId = log.sessionId
            PhoneVideoFinishing.shared.begin(sessionId: sessionId)
            // STOP or the app going to the background ends the file: iOS must not suspend the app
            // before the MP4 has its index (FBBD-468, audit 7 Nov BD-T08).
            let release = BackgroundTime.shared.hold("Finish \(file)")
            writer.finishWriting {
                if writer.status == .completed {
                    AppLog.i(Self.tag, "\(file) finished")
                } else {
                    AppLog.w(Self.tag, "\(file) not finished: \(Self.describe(writer))")
                }
                PhoneVideoFinishing.shared.end(sessionId: sessionId)
                release()
            }
        } else {
            AppLog.w(Self.tag, "\(file) not finished: \(Self.describe(writer))")
        }
        self.writer = nil
        self.videoIn = nil
        self.audioIn = nil
        self.file = nil
    }

    /// The file failed while recording: it ends, the camera says so, and the next file starts after
    /// [retry]'s wait (FBBD-441). The line says what the file took up to the failure, to find the cause
    /// of the -11800/-16341 failures of 3 Nov: which append failed ([input]), the gap from the last
    /// video frame to [frame], the last sound sample, and the size of [frame].
    private func failedFile(input: String, frame: CMSampleBuffer) {
        let pts = CMSampleBufferGetPresentationTimeStamp(frame)
        let now = Self.seconds(pts)
        let size = CMSampleBufferGetFormatDescription(frame).map(CMVideoFormatDescriptionGetDimensions)
        let gapMs = lastVideoPts.isValid ? Int((now - Self.seconds(lastVideoPts)) * 1000) : -1
        let audioMs = lastAudioPts.isValid ? Int((now - Self.seconds(lastAudioPts)) * 1000) : -1
        if let writer, let file {
            AppLog.w(
                Self.tag,
                "\(file) failed: input=\(input) age_s=\(Int(now - fileStartedAt)) video_gap_ms=\(gapMs) " +
                    "audio_behind_ms=\(audioMs) audio_appended=\(audioAppended) audio_refused=\(audioRefused) " +
                    "frame=\(size.map { "\($0.width)x\($0.height)" } ?? "-") rotation=\(Int(rotation)) " +
                    "\(counts.text) \(Self.describe(writer))"
            )
        }
        finishFile(reason: "error")
        retry.failed(startedAt: fileStartedAt, now: now)
        AppLog.i(Self.tag, "failed files \(retry.total), next file in \(Int(retry.wait)) s")
        status("Camera error", ok: false, warning: CameraStatus.retrying)
    }

    private static func seconds(_ t: CMTime) -> Double {
        let s = CMTimeGetSeconds(t)
        return s.isFinite ? s : 0
    }

    private func interrupted() {
        queue.async {
            self.finishFile(reason: "interrupted")
            if self.running { self.status("Paused", ok: false) }
        }
    }

    /// A runtime error ends the file; the camera is started once more.
    private func failed() {
        queue.async {
            self.finishFile(reason: "error")
            guard self.running, self.configured else { return }
            self.status("Restarting", ok: false)
            self.capture.startRunning()
        }
    }

    private static let tag = "PhoneCamera"

    private static func describe(_ writer: AVAssetWriter) -> String {
        "status=\(writer.status.rawValue) error=\(writer.error.map { String(describing: $0) } ?? "none")"
    }

    private func status(_ text: String, ok: Bool, warning: String? = nil) {
        let report = CameraStatus(text: text, ok: ok, warning: warning)
        DispatchQueue.main.async { self.onStatus(report) }
    }

    private enum CameraError: Error {
        case notSupported
    }
}
