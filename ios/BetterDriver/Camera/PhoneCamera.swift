import AVFoundation
import BetterDriverCore
import UIKit

/// The camera line on the track screen's status strip (UI-07, as on Android): "1080p · 60" in green
/// while the camera records, amber when it does not.
struct CameraStatus: Equatable {
    let text: String
    let ok: Bool
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
    private var failures = 0
    private static let maxFailures = 3

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
                self.status("No access", ok: false)
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
            do {
                try self.configure(device)
            } catch {
                self.running = false
                self.status("Camera error", ok: false)
                return
            }
            self.capture.startRunning()
        }
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
            // A writer that failed on its own (a full disk, a failed sound sample) ends its file; the
            // next frame starts a new one, up to [maxFailures] times in a session.
            if let writer, writer.status == .failed || writer.status == .cancelled {
                failedFile()
            }
            if writer == nil, running { open(at: pts, frame: sample) }
            guard let writer, writer.status == .writing, let videoIn else { return }
            if videoIn.isReadyForMoreMediaData, !videoIn.append(sample) {
                failedFile()
                return
            }
            sync(at: pts)
        } else if output === audioOut, let writer, writer.status == .writing, let audioIn,
                  CMTimeCompare(pts, firstPts) >= 0, audioIn.isReadyForMoreMediaData {
            _ = audioIn.append(sample)
        }
    }

    /// Starts the next file at the frame captured at [pts]; [frame] gives its size.
    private func open(at pts: CMTime, frame: CMSampleBuffer) {
        let dir = URL(fileURLWithPath: log.videoDir, isDirectory: true)
        fileIndex += 1
        let name = log.fileName(index: fileIndex)
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let w = try AVAssetWriter(outputURL: dir.appendingPathComponent(name), fileType: .mp4)
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
            log.started(file: name, startMicros: sessionMicros(of: pts).frame, fps: recording.fps)
            // Green at the chosen mode, amber once stepped down (UI-07).
            status(recording.label, ok: recording == mode)
        } catch {
            // No video for the rest of this session; the data recording carries on.
            writer = nil
            running = false
            status("Camera error", ok: false)
        }
    }

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
        // A failed writer is left as it is: its inputs only take marks while it writes.
        if writer.status == .writing {
            videoIn?.markAsFinished()
            audioIn?.markAsFinished()
            writer.finishWriting {}
        }
        self.writer = nil
        self.videoIn = nil
        self.audioIn = nil
        self.file = nil
    }

    /// The file failed while recording: it ends, the camera says so, and after [maxFailures] there is no more video.
    private func failedFile() {
        finishFile(reason: "error")
        failures += 1
        if failures >= Self.maxFailures { running = false }
        status("Camera error", ok: false)
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

    private func status(_ text: String, ok: Bool) {
        let report = CameraStatus(text: text, ok: ok)
        DispatchQueue.main.async { self.onStatus(report) }
    }

    private enum CameraError: Error {
        case notSupported
    }
}
