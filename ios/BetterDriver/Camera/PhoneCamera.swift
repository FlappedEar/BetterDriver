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
/// camera at 1080p and 60 fps (30 when the phone cannot do 60), H.264 with the microphone's sound,
/// into the session's video folder as phone_0001.mp4 and on. Every frame's capture time is known on
/// the session clock from its timestamp, so the file is placed on the session by its frame times
/// (PhoneVideoLog), the same way review and VBO export place Android's files.
///
/// iOS stops the camera while the app is in the background or another app takes it: the file ends
/// there ("interrupted"), and a new file starts when the camera comes back. A camera fault never
/// stops the data recording.
final class PhoneCamera: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureAudioDataOutputSampleBufferDelegate {
    static let preferredFps: Int32 = 60
    static let fallbackFps: Int32 = 30

    private let log: PhoneVideoLog
    private let onStatus: (CameraStatus) -> Void
    private let queue = DispatchQueue(label: "com.flappedear.betterdriver.camera", qos: .userInitiated)
    private let capture = AVCaptureSession()
    private let videoOut = AVCaptureVideoDataOutput()
    private let audioOut = AVCaptureAudioDataOutput()
    private var observers: [NSObjectProtocol] = []
    /// Main queue: [stop] came, possibly while the access prompt was still open.
    private var stopped = false

    // Everything below is used on [queue] only.
    private var running = false
    private var configured = false
    private var fps = PhoneCamera.preferredFps
    private var rotation: CGFloat = 0
    private var hasAudio = false
    private var writer: AVAssetWriter?
    private var videoIn: AVAssetWriterInput?
    private var audioIn: AVAssetWriterInput?
    private var file: String?
    private var fileIndex: Int32 = 0
    private var firstPts: CMTime = .invalid
    private var lastSyncPts: CMTime = .invalid

    /// [onStatus] runs on the main queue.
    init(log: PhoneVideoLog, onStatus: @escaping (CameraStatus) -> Void) {
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
        stopped = true
        queue.sync {
            running = false
            finishFile(reason: "user")
        }
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers.removeAll()
        let capture = self.capture
        queue.async { capture.stopRunning() }
    }

    private func begin(audio: Bool) {
        guard !stopped else { return }
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
            status("Not supported", ok: false)
            return
        }
        // The angle that keeps the horizon level for how the phone is held now, as the file's
        // orientation (the frames themselves are not turned).
        let angle = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: nil).videoRotationAngleForHorizonLevelCapture
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

    private func configure(_ device: AVCaptureDevice) throws {
        capture.beginConfiguration()
        defer { capture.commitConfiguration() }
        capture.sessionPreset = .inputPriority
        let input = try AVCaptureDeviceInput(device: device)
        guard capture.canAddInput(input) else { throw CameraError.notSupported }
        capture.addInput(input)
        let (format, rate) = try Self.format(of: device)
        try device.lockForConfiguration()
        device.activeFormat = format
        device.activeVideoMinFrameDuration = CMTime(value: 1, timescale: rate)
        device.activeVideoMaxFrameDuration = CMTime(value: 1, timescale: rate)
        device.unlockForConfiguration()
        fps = rate
        videoOut.alwaysDiscardsLateVideoFrames = false
        videoOut.setSampleBufferDelegate(self, queue: queue)
        guard capture.canAddOutput(videoOut) else { throw CameraError.notSupported }
        capture.addOutput(videoOut)
        if hasAudio, let mic = AVCaptureDevice.default(for: .audio),
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

    /// The 1080p format at 60 fps, else at 30.
    private static func format(of device: AVCaptureDevice) throws -> (AVCaptureDevice.Format, Int32) {
        let fullHd = device.formats.filter { f in
            let d = CMVideoFormatDescriptionGetDimensions(f.formatDescription)
            return d.width == 1920 && d.height == 1080
        }
        for rate in [preferredFps, fallbackFps] {
            if let f = fullHd.first(where: { $0.videoSupportedFrameRateRanges.contains { $0.maxFrameRate >= Double(rate) } }) {
                return (f, rate)
            }
        }
        throw CameraError.notSupported
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sample: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard running else { return }
        let pts = CMSampleBufferGetPresentationTimeStamp(sample)
        if output === videoOut {
            if writer == nil { open(at: pts) }
            guard let writer, writer.status == .writing, let videoIn else { return }
            if videoIn.isReadyForMoreMediaData, !videoIn.append(sample) {
                finishFile(reason: "error")
                status("Camera error", ok: false)
                return
            }
            sync(at: pts)
        } else if output === audioOut, let writer, writer.status == .writing, let audioIn,
                  CMTimeCompare(pts, firstPts) >= 0, audioIn.isReadyForMoreMediaData {
            _ = audioIn.append(sample)
        }
    }

    /// Starts the next file at the frame captured at [pts].
    private func open(at pts: CMTime) {
        let dir = URL(fileURLWithPath: log.videoDir, isDirectory: true)
        fileIndex += 1
        let name = log.fileName(index: fileIndex)
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let w = try AVAssetWriter(outputURL: dir.appendingPathComponent(name), fileType: .mp4)
            let video = AVAssetWriterInput(
                mediaType: .video,
                outputSettings: videoOut.recommendedVideoSettings(forVideoCodecType: .h264, assetWriterOutputFileType: .mp4)
            )
            video.expectsMediaDataInRealTime = true
            video.transform = CGAffineTransform(rotationAngle: rotation * .pi / 180)
            guard w.canAdd(video) else { throw CameraError.notSupported }
            w.add(video)
            var audio: AVAssetWriterInput?
            if hasAudio {
                let a = AVAssetWriterInput(
                    mediaType: .audio,
                    outputSettings: audioOut.recommendedAudioSettingsForAssetWriter(writingTo: .mp4)
                )
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
            log.started(file: name, startMicros: sessionMicros(of: pts).frame, fps: fps)
            status("1080p · \(fps)", ok: true)
        } catch {
            // No video for the rest of this session; the data recording carries on.
            writer = nil
            running = false
            status("Camera error", ok: false)
        }
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
        videoIn?.markAsFinished()
        audioIn?.markAsFinished()
        if writer.status == .writing {
            writer.finishWriting {}
        }
        self.writer = nil
        self.videoIn = nil
        self.audioIn = nil
        self.file = nil
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
