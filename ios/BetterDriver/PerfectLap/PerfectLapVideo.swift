import AVFoundation
import BetterDriverCore
import CoreVideo
import UIKit

/// The perfect lap as an MP4 (UI-180 A), as Android's PerfectLapVideo: 1920×1080 at 30 fps, every
/// frame drawn by the player's painter with the lap clock on it, the camera at the player's distance.
enum PerfectLapVideo {
    static let width = 1920
    static let height = 1080
    static let fps: Int32 = 30

    enum Failure: LocalizedError {
        case encoder(String)
        var errorDescription: String? {
            switch self {
            case .encoder(let why): why
            }
        }
    }

    /// Writes the video into the app's Documents/PerfectLap folder; [progress] goes from 0 to 1.
    static func save(scene: PerfectLapScene, distance: Double, progress: @escaping (Double) -> Void) async throws -> URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("PerfectLap", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("perfect-lap-\(Int(Date().timeIntervalSince1970)).mp4")
        // Not on the main actor: a nonisolated async function runs on the shared pool, and stopping
        // the caller's task stops the writing.
        try write(scene: scene, distance: distance, to: url, progress: progress)
        return url
    }

    private static func write(scene: PerfectLapScene, distance: Double, to url: URL, progress: (Double) -> Void) throws {
        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
        ])
        input.expectsMediaDataInRealTime = false
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: width,
            kCVPixelBufferHeightKey as String: height,
        ])
        guard writer.canAdd(input) else { throw Failure.encoder("the phone's video encoder refused") }
        writer.add(input)
        guard writer.startWriting() else { throw writer.error ?? Failure.encoder("the video could not be written") }
        writer.startSession(atSourceTime: .zero)
        // The satellite photo as in the player, each tile read or fetched before its frame is drawn.
        let painter = PerfectLapPainter(scene: scene, tiles: PerfectLapTiles.make(), waitForTiles: true)
        painter.distance = distance
        let frames = Int((scene.durationSeconds * Double(fps)).rounded(.up)) + 1
        do {
            for i in 0 ..< frames {
                try Task.checkCancellation()
                // A writer that fails while the input waits (a full disk) never takes more: stop with its error.
                while !input.isReadyForMoreMediaData {
                    try Task.checkCancellation()
                    if writer.status == .failed || writer.status == .cancelled {
                        throw writer.error ?? Failure.encoder("the video could not be written")
                    }
                    Thread.sleep(forTimeInterval: 0.005)
                }
                guard let pool = adaptor.pixelBufferPool else { throw Failure.encoder("no video buffers") }
                var buffer: CVPixelBuffer?
                CVPixelBufferPoolCreatePixelBuffer(nil, pool, &buffer)
                guard let buffer else { throw Failure.encoder("no video buffers") }
                draw(painter, seconds: min(Double(i) / Double(fps), scene.durationSeconds), into: buffer)
                if !adaptor.append(buffer, withPresentationTime: CMTime(value: CMTimeValue(i), timescale: fps)) {
                    throw writer.error ?? Failure.encoder("the video could not be written")
                }
                progress(Double(i + 1) / Double(frames))
            }
        } catch {
            writer.cancelWriting()
            try? FileManager.default.removeItem(at: url)
            throw error
        }
        input.markAsFinished()
        let done = DispatchSemaphore(value: 0)
        writer.finishWriting { done.signal() }
        done.wait()
        if writer.status != .completed { throw writer.error ?? Failure.encoder("the video could not be written") }
    }

    private static func draw(_ painter: PerfectLapPainter, seconds: Double, into buffer: CVPixelBuffer) {
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        guard let cg = CGContext(
            data: CVPixelBufferGetBaseAddress(buffer),
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: CVPixelBufferGetBytesPerRow(buffer),
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
        ) else { return }
        // Origin top left, as the player's picture.
        cg.translateBy(x: 0, y: CGFloat(height))
        cg.scaleBy(x: 1, y: -1)
        painter.draw(cg, seconds: seconds, size: CGSize(width: width, height: height))
    }
}
