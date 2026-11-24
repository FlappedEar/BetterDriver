import AVFoundation

/// The audio state once a minute while a session records (FBBD-305), logged next to the load line so a
/// drive log shows, minute by minute, whether the app held an audio session on a Bluetooth headset
/// while the RaceBox lost messages: "audio minute: out=bt_a2dp (JBL Tune 135BT) in=mic
/// category=PlayAndRecord mode=VideoRecording app_active=true mic_held=true others_playing=false".
enum AudioMinute {
    static let tag = "AudioSession"

    /// The line for the given state; [outputName] only when the output is not the phone's speaker.
    static func text(
        output: String, outputName: String?, input: String, category: String, mode: String,
        appActive: Bool, micHeld: Bool, othersPlaying: Bool
    ) -> String {
        "audio minute: out=\(output)\(outputName.map { " (\($0))" } ?? "") in=\(input) " +
            "category=\(category) mode=\(mode) app_active=\(appActive) mic_held=\(micHeld) " +
            "others_playing=\(othersPlaying)"
    }

    /// The line for the audio state now.
    static func now(use: AudioSessionUse = .shared) -> String {
        let session = AVAudioSession.sharedInstance()
        let (output, name) = CoachVoice.output
        return text(
            output: output, outputName: name,
            input: inputType(session.currentRoute.inputs.first?.portType),
            category: short(session.category.rawValue, prefix: "AVAudioSessionCategory"),
            mode: short(session.mode.rawValue, prefix: "AVAudioSessionMode"),
            appActive: use.isActive, micHeld: use.recording, othersPlaying: session.isOtherAudioPlaying
        )
    }

    /// The input named as the outputs are: mic for the phone's own, bt_sco for a Bluetooth headset's, ...
    static func inputType(_ port: AVAudioSession.Port?) -> String {
        guard let port else { return "none" }
        switch port {
        case .builtInMic: return "mic"
        case .bluetoothHFP: return "bt_sco"
        case .headsetMic: return "wired"
        case .usbAudio: return "usb"
        case .carAudio: return "car"
        default: return port.rawValue
        }
    }

    static func short(_ raw: String, prefix: String) -> String {
        raw.hasPrefix(prefix) ? String(raw.dropFirst(prefix.count)) : raw
    }
}
