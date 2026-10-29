import AVFoundation
import BetterDriverCore
import CoreImage
import UIKit

/// "Video with data" (FBBD-152), as Android's VideoExports: a session's video, a lap or all of it, with
/// the data overlay drawn on every frame. The same video composition plays in the app and is saved as
/// an MP4, so what is watched is what is saved. It works without laps too (a drive away from a saved
/// track): the overlay then shows speed, G and the map.
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
        var turned = false
        for cut in cuts {
            let asset = AVURLAsset(url: URL(fileURLWithPath: cut.path))
            let range = CMTimeRange(start: cmTime(cut.fromMicros), end: cmTime(cut.toMicros))
            let at = cmTime(cut.outputStartMicros)
            guard let source = try await asset.loadTracks(withMediaType: .video).first else { continue }
            try video.insertTimeRange(range, of: source, at: at)
            if !turned {
                // The phone's files say how they are turned (PhoneCamera's transform); the video is shown upright.
                video.preferredTransform = try await source.load(.preferredTransform)
                turned = true
            }
            // A clip without sound is silent there, so clips with and without it follow each other.
            if let sound = try await asset.loadTracks(withMediaType: .audio).first {
                try? audio.insertTimeRange(range, of: sound, at: at)
            }
        }
        if audio.segments.isEmpty { composition.removeTrack(audio) }
        let painter = DataOverlayPainter(session: session, cuts: cuts, part: part)
        let overlay = AVMutableVideoComposition(asset: composition) { request in
            let source = request.sourceImage
            let at = DataVideoMaker.micros(request.compositionTime)
            guard let band = painter.band(at: at, size: source.extent.size) else {
                request.finish(with: source, context: nil)
                return
            }
            // Core Image counts from the bottom left: the band's bottom edge on the picture's.
            let placed = band.transformed(by: CGAffineTransform(translationX: source.extent.minX, y: source.extent.minY))
            request.finish(with: placed.composited(over: source), context: nil)
        }
        return (composition, overlay)
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

/// Draws the band along the bottom of each frame, as Android's OverlayPainter (UI for FBBD-152): speed,
/// lap and lap time, delta to the best lap; at the right the G meter and the track map with the car.
/// Light text on a dark gradient in the app's number and label fonts. Called on AVFoundation's threads.
final class DataOverlayPainter: @unchecked Sendable {
    private let session: DataVideoSession
    private let cuts: [DataVideoCut]
    private let part: Int32
    private let lock = NSLock()
    private var shaped: (size: CGSize, band: OverlayBand, track: CGPath)?

    init(session: DataVideoSession, cuts: [DataVideoCut], part: Int32) {
        self.session = session
        self.cuts = cuts
        self.part = part
        _ = TrackFonts.register
    }

    /// The band for the frame at [outputMicros] of the video, on a picture of [size]; nil outside the cuts.
    func band(at outputMicros: Int64, size: CGSize) -> CIImage? {
        guard size.width >= 1, size.height >= 1,
              let cut = cuts.last(where: { $0.outputStartMicros <= outputMicros }) ?? cuts.first else { return nil }
        let sessionMicros = cut.sessionStartMicros + (outputMicros - cut.outputStartMicros)
        let text = session.text(sessionMicros: sessionMicros, part: part)
        let (band, track) = layout(size)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        let image = UIGraphicsImageRenderer(size: CGSize(width: Int(band.width), height: Int(band.height)), format: format).image { ui in
            draw(ui.cgContext, band, track, text)
        }
        return image.cgImage.map { CIImage(cgImage: $0) }
    }

    private func layout(_ size: CGSize) -> (OverlayBand, CGPath) {
        lock.lock()
        defer { lock.unlock() }
        if let shaped, shaped.size == size { return (shaped.band, shaped.track) }
        let band = session.band(videoWidth: Int32(size.width.rounded()), videoHeight: Int32(size.height.rounded()))
        let path = CGMutablePath()
        for (i, p) in band.track.enumerated() {
            let q = CGPoint(x: CGFloat(p.x), y: CGFloat(p.y))
            if i == 0 { path.move(to: q) } else { path.addLine(to: q) }
        }
        shaped = (size, band, path)
        return (band, path)
    }

    private func draw(_ cg: CGContext, _ band: OverlayBand, _ track: CGPath, _ text: OverlayText) {
        let w = CGFloat(band.width), h = CGFloat(band.height)
        // The scrim: clear at the top, darker towards the bottom (Android's 0, 0.45 at 35 %, 0.75).
        let colors = [shade.withAlphaComponent(0), shade.withAlphaComponent(0.45), shade.withAlphaComponent(0.75)]
        if let gradient = CGGradient(
            colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors.map(\.cgColor) as CFArray, locations: [0, 0.35, 1]
        ) {
            cg.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: 0, y: h), options: [])
        }
        let number = UIFont(name: "SairaSemiCondensed-Bold", size: CGFloat(band.numberSize)) ?? .boldSystemFont(ofSize: CGFloat(band.numberSize))
        let label = UIFont(name: "Barlow-SemiBold", size: CGFloat(band.labelSize)) ?? .systemFont(ofSize: CGFloat(band.labelSize))
        func slot(_ box: Box, _ caption: String, _ captionInk: OverlayInk, _ value: String, _ valueInk: OverlayInk) {
            // As Android's drawText: the caption's baseline a label's height below the top, the number's
            // baseline 2 % above the bottom.
            let left = CGFloat(box.left)
            put(caption, label, color(captionInk), x: left, baseline: CGFloat(box.top) + label.pointSize, kern: label.pointSize * 0.08)
            put(value, number, color(valueInk), x: left, baseline: CGFloat(box.bottom) - CGFloat(box.height) * 0.02, kern: 0)
        }
        slot(band.speed, "KM/H", .dim, text.speed, .text)
        slot(band.lap, text.lapCaption, text.lapCaptionInk, text.lapValue, text.lapValueInk)
        if let delta = text.deltaValue { slot(band.delta, "VS BEST", .dim, delta, text.deltaInk) }
        gMeter(cg, band, text)
        map(cg, band, track, text)
    }

    private func gMeter(_ cg: CGContext, _ band: OverlayBand, _ text: OverlayText) {
        let box = band.gMeter
        let r = CGFloat(box.width) / 2
        let cx = CGFloat(box.centerX), cy = CGFloat(box.centerY)
        cg.setLineWidth(CGFloat(band.stroke))
        cg.setStrokeColor(dim.withAlphaComponent(0.6).cgColor)
        cg.strokeEllipse(in: CGRect(x: cx - r, y: cy - r, width: 2 * r, height: 2 * r))
        // 1 g, of the 1.5 on the outer ring.
        let one = r / 1.5
        cg.strokeEllipse(in: CGRect(x: cx - one, y: cy - one, width: 2 * one, height: 2 * one))
        cg.strokeLineSegments(between: [
            CGPoint(x: CGFloat(box.left), y: cy), CGPoint(x: CGFloat(box.right), y: cy),
            CGPoint(x: cx, y: CGFloat(box.top)), CGPoint(x: cx, y: CGFloat(box.bottom)),
        ])
        guard let g = text.g else { return }
        let dot = band.dot(g: g)
        let d = r * 0.14
        cg.setFillColor(ink.cgColor)
        cg.fillEllipse(in: CGRect(x: CGFloat(dot.x) - d, y: CGFloat(dot.y) - d, width: 2 * d, height: 2 * d))
    }

    private func map(_ cg: CGContext, _ band: OverlayBand, _ track: CGPath, _ text: OverlayText) {
        cg.setLineWidth(CGFloat(band.stroke) * 1.6)
        cg.setLineCap(.round)
        cg.setLineJoin(.round)
        cg.setStrokeColor(ink.withAlphaComponent(0.85).cgColor)
        cg.addPath(track)
        cg.strokePath()
        guard let position = text.position else { return }
        let car = band.car(position: position)
        let r = CGFloat(band.height) * 0.045
        let rim = r * 1.45
        let x = CGFloat(car.x), y = CGFloat(car.y)
        cg.setFillColor(shade.cgColor)
        cg.fillEllipse(in: CGRect(x: x - rim, y: y - rim, width: 2 * rim, height: 2 * rim))
        cg.setFillColor(best.cgColor)
        cg.fillEllipse(in: CGRect(x: x - r, y: y - r, width: 2 * r, height: 2 * r))
    }

    private func put(_ s: String, _ font: UIFont, _ color: UIColor, x: CGFloat, baseline: CGFloat, kern: CGFloat) {
        let attributes: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color, .kern: kern]
        (s as NSString).draw(at: CGPoint(x: x, y: baseline - font.ascender), withAttributes: attributes)
    }

    private func color(_ i: OverlayInk) -> UIColor {
        switch i {
        case .gain: gain
        case .loss: loss
        case .best: best
        case .dim: dim
        default: ink
        }
    }

    // Android's TrackColorsGreenRed (TrackColors).
    private let ink = UIColor(TrackColors.text)
    private let dim = UIColor(TrackColors.textDim)
    private let gain = UIColor(TrackColors.gain)
    private let loss = UIColor(TrackColors.loss)
    private let best = UIColor(TrackColors.best)
    private let shade = UIColor(TrackColors.background)
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

    /// A grey 640×360 H.264 file of [seconds] at 30 fps.
    private static func write(_ url: URL, seconds: Int) -> Bool {
        try? FileManager.default.removeItem(at: url)
        guard let writer = try? AVAssetWriter(outputURL: url, fileType: .mp4) else { return false }
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: 640, AVVideoHeightKey: 360,
        ])
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: 640, kCVPixelBufferHeightKey as String: 360,
        ])
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
