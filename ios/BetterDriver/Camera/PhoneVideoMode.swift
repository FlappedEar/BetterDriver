import AVFoundation

/// A phone video setting, as on Android (FBBD-88, UI-103 A): 4K, 1080p or 720p at 60 or 30 fps,
/// no 2.7K. 1080p · 60 until another is chosen.
struct PhoneVideoMode: Hashable, Identifiable {
    enum Size: String, CaseIterable {
        case uhd = "4K", fhd = "1080p", hd = "720p"

        var width: Int32 {
            switch self {
            case .uhd: 3840
            case .fhd: 1920
            case .hd: 1280
            }
        }

        var height: Int32 {
            switch self {
            case .uhd: 2160
            case .fhd: 1080
            case .hd: 720
            }
        }
    }

    let size: Size
    let fps: Int32

    var id: String { label }
    var label: String { "\(size.rawValue) · \(fps)" }

    static let high: Int32 = 60
    static let low: Int32 = 30
    static let standard = PhoneVideoMode(size: .fhd, fps: high)
    static let all = Size.allCases.flatMap { [PhoneVideoMode(size: $0, fps: high), PhoneVideoMode(size: $0, fps: low)] }

    /// The mode saved under [label], or the default.
    static func named(_ label: String?) -> PhoneVideoMode {
        all.first { $0.label == label } ?? standard
    }

    /// The modes this phone's back camera can record, largest first; read from its formats.
    static func supported() -> [PhoneVideoMode] {
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else { return [] }
        return all.filter { format(of: device, for: $0) != nil }
    }

    /// Where the camera falls back when it cannot record a mode, as Android's ladderFrom: every
    /// smaller size at the chosen rate first, then the smallest at 30 fps. Never above the chosen mode.
    var ladder: [PhoneVideoMode] {
        let sizes = Size.allCases.drop(while: { $0 != size })
        let atRate = sizes.map { PhoneVideoMode(size: $0, fps: fps) }
        return fps > Self.low ? atRate + [PhoneVideoMode(size: .hd, fps: Self.low)] : Array(atRate)
    }

    /// A format of [device] that records [mode].
    /// An 8-bit format first: H.264 records those, while a 10-bit (HDR) one would need HEVC.
    static func format(of device: AVCaptureDevice, for mode: PhoneVideoMode) -> AVCaptureDevice.Format? {
        let fits = device.formats.filter { f in
            let d = CMVideoFormatDescriptionGetDimensions(f.formatDescription)
            return d.width == mode.size.width && d.height == mode.size.height &&
                f.videoSupportedFrameRateRanges.contains { $0.maxFrameRate >= Double(mode.fps) }
        }
        return fits.first { eightBit.contains(CMFormatDescriptionGetMediaSubType($0.formatDescription)) } ?? fits.first
    }

    private static let eightBit: Set<FourCharCode> = [
        kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
    ]
}
