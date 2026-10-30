import AVFoundation
import BetterDriverCore
import CoreImage
import UIKit

/// "Video with data" (FBBD-152), as Android's VideoExports: a session's video, a lap or all of it, with
/// the data overlay drawn on every frame in FlappedEar Overlays' Tech HUD layout (FBBD-347,
/// TechHudPainter). The same video composition plays in the app and is saved as an MP4, so what is
/// watched is what is saved. It works without laps too (a drive away from a saved track): the overlay
/// then leaves the lap's values empty.
enum DataVideoMaker {
    /// Session [id] with its video and the length of each file, read off the main queue; nil without video.
    static func open(_ id: String) async throws -> (DataVideoSession, [String: KotlinLong])? {
        let opened = try await Task.detached(priority: .userInitiated) { () -> DataVideoSession? in
            try DataVideo(sessionsRoot: AppFolders.sessions.path, tracksRoot: AppFolders.tracks.path).open(id: id)
        }.value
        guard let session = opened else { return nil }
        var durations: [String: KotlinLong] = [:]
        for path in session.paths {
            // A file AVFoundation cannot read is left out, as on Android.
            guard let length = try? await AVURLAsset(url: URL(fileURLWithPath: path)).load(.duration),
                  length.isNumeric, length.seconds > 0 else { continue }
            durations[path] = KotlinLong(value: micros(length))
        }
        return (session, durations)
    }

    /// The video of [part] (0: all of it) with the overlay: an asset to play or save, and its video composition.
    /// A lap starts [handleSeconds] before its line and ends as long after (FBBD-346, as FlappedEar Overlays).
    static func make(
        _ session: DataVideoSession, part: Int32, durations: [String: KotlinLong], handleSeconds: Int32 = 0
    ) async throws -> (AVComposition, AVVideoComposition) {
        let cuts = session.cuts(part: part, durations: durations, handleSeconds: handleSeconds)
        let composition = AVMutableComposition()
        guard !cuts.isEmpty,
              let video = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid),
              let audio = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)
        else { throw DataVideoError.noVideo }
        var turned: CGAffineTransform?
        // Where a file was recorded on the other landscape side than the first (the phone turned
        // round during a break): one track has one transform, so those frames are turned here.
        var flipped: [CMTimeRange] = []
        for cut in cuts {
            let asset = AVURLAsset(url: URL(fileURLWithPath: cut.path))
            let range = CMTimeRange(start: cmTime(cut.fromMicros), end: cmTime(cut.toMicros))
            let at = cmTime(cut.outputStartMicros)
            guard let source = try await asset.loadTracks(withMediaType: .video).first else { continue }
            try video.insertTimeRange(range, of: source, at: at)
            // The phone's files say how they are turned (PhoneCamera's transform). Video is landscape,
            // as the track screen: a file turned upright (recorded before landscape was forced) plays
            // as the camera took it, which is the right way up for a phone in a landscape mount.
            let transform = landscape(try await source.load(.preferredTransform))
            if let first = turned {
                if (transform.a < 0) != (first.a < 0) { flipped.append(CMTimeRange(start: at, duration: range.duration)) }
            } else {
                video.preferredTransform = transform
                turned = transform
            }
            // A clip without sound is silent there, so clips with and without it follow each other.
            if let sound = try await asset.loadTracks(withMediaType: .audio).first {
                try? audio.insertTimeRange(range, of: sound, at: at)
            }
        }
        if audio.segments.isEmpty { composition.removeTrack(audio) }
        let painter = TechHudPainter(session: session, cuts: cuts, part: part)
        let flips = flipped
        let overlay = AVMutableVideoComposition(asset: composition) { request in
            var source = request.sourceImage
            if flips.contains(where: { $0.containsTime(request.compositionTime) }) {
                let e = source.extent
                source = source.transformed(by: CGAffineTransform(translationX: -e.midX, y: -e.midY)
                    .concatenating(CGAffineTransform(rotationAngle: .pi))
                    .concatenating(CGAffineTransform(translationX: e.midX, y: e.midY)))
            }
            let at = DataVideoMaker.micros(request.compositionTime)
            guard let hud = painter.overlay(at: at, size: source.extent.size) else {
                request.finish(with: source, context: nil)
                return
            }
            let placed = hud.transformed(by: CGAffineTransform(translationX: source.extent.minX, y: source.extent.minY))
            request.finish(with: placed.composited(over: source), context: nil)
        }
        return (composition, overlay)
    }

    /// [transform] when it keeps the video landscape (0° or 180°), else none.
    static func landscape(_ transform: CGAffineTransform) -> CGAffineTransform {
        abs(transform.b) > 0.5 ? .identity : transform
    }

    static func micros(_ t: CMTime) -> Int64 {
        CMTimeConvertScale(t, timescale: 1_000_000, method: .roundHalfAwayFromZero).value
    }

    static func cmTime(_ micros: Int64) -> CMTime { CMTime(value: micros, timescale: 1_000_000) }
}

enum DataVideoError: LocalizedError {
    case noVideo
    case notSaved(String)

    var errorDescription: String? {
        switch self {
        case .noVideo: "No video for this part"
        case .notSaved(let why): why
        }
    }
}

/// Saves the video of [asset] with its overlay as an MP4 in the temporary folder; [progress] goes from 0 to 1.
enum DataVideoSaver {
    static func save(
        _ asset: AVComposition, _ overlay: AVVideoComposition, name: String, progress: @escaping (Double) -> Void
    ) async throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("data-video", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: url)
        guard let export = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetHighestQuality) else {
            throw DataVideoError.notSaved("the phone's video encoder refused")
        }
        export.videoComposition = overlay
        export.outputURL = url
        export.outputFileType = .mp4
        let watch = Task {
            while !Task.isCancelled {
                progress(Double(export.progress))
                try? await Task.sleep(nanoseconds: 300_000_000)
            }
        }
        defer { watch.cancel() }
        await withTaskCancellationHandler {
            await export.export()
        } onCancel: {
            export.cancelExport()
        }
        try Task.checkCancellation()
        guard export.status == .completed else {
            try? FileManager.default.removeItem(at: url)
            throw DataVideoError.notSaved(export.error?.localizedDescription ?? "the video could not be written")
        }
        return url
    }
}

#if DEBUG
/// For the UI tests (-demoVideo): ten seconds of phone video in each session, a minute in, so Video
/// with data has something to show on the simulator, which has no camera.
enum DataVideoDemo {
    static func add(sessionsRoot: String) {
        let root = URL(fileURLWithPath: sessionsRoot, isDirectory: true)
        let ids = (try? FileManager.default.contentsOfDirectory(atPath: sessionsRoot)) ?? []
        for id in ids {
            let dir = root.appendingPathComponent(id, isDirectory: true).appendingPathComponent("video", isDirectory: true)
            guard (try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)) != nil,
                  write(dir.appendingPathComponent("phone_demo.mp4"), seconds: 10) else { continue }
            let index = """
            {"version":1,"clips":[{"fileName":"phone_demo.mp4","kind":"PHONE","sourceId":"phone",\
            "sessionOffsetUs":60000000,"alignment":{"method":"CAPTURE_CLOCK","uncertaintyUs":0},\
            "durationUs":10000000,"status":"LOCAL"}]}
            """
            try? index.write(to: dir.appendingPathComponent("clips.json"), atomically: true, encoding: .utf8)
        }
    }

    /// A grey 640×360 H.264 file of [seconds] at 30 fps, tagged as turned upright (90°) as the
    /// iPhone's files were before landscape was forced: it still plays landscape.
    static func write(_ url: URL, seconds: Int, upright: Bool = true) -> Bool {
        try? FileManager.default.removeItem(at: url)
        guard let writer = try? AVAssetWriter(outputURL: url, fileType: .mp4) else { return false }
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: 640, AVVideoHeightKey: 360,
        ])
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: 640, kCVPixelBufferHeightKey as String: 360,
        ])
        if upright { input.transform = CGAffineTransform(rotationAngle: .pi / 2) }
        guard writer.canAdd(input) else { return false }
        writer.add(input)
        guard writer.startWriting() else { return false }
        writer.startSession(atSourceTime: .zero)
        for i in 0 ..< seconds * 30 {
            while !input.isReadyForMoreMediaData { Thread.sleep(forTimeInterval: 0.002) }
            guard let pool = adaptor.pixelBufferPool else { return false }
            var buffer: CVPixelBuffer?
            CVPixelBufferPoolCreatePixelBuffer(nil, pool, &buffer)
            guard let buffer else { return false }
            CVPixelBufferLockBaseAddress(buffer, [])
            if let base = CVPixelBufferGetBaseAddress(buffer) {
                memset(base, Int32(60 + i % 60), CVPixelBufferGetDataSize(buffer))
            }
            CVPixelBufferUnlockBaseAddress(buffer, [])
            guard adaptor.append(buffer, withPresentationTime: CMTime(value: CMTimeValue(i), timescale: 30)) else { return false }
        }
        input.markAsFinished()
        let done = DispatchSemaphore(value: 0)
        writer.finishWriting { done.signal() }
        done.wait()
        return writer.status == .completed
    }
}
#endif
