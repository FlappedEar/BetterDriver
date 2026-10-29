import AVFoundation
import BetterDriverCore

/// The coach's voice on the iPhone (FBBD-333), as Voice on Android: live text-to-speech in the coach's
/// language, to whatever output is connected (a helmet headset or the car over Bluetooth, else the
/// speaker), with music ducked under it while a phrase plays (FBBD-47). A new phrase replaces one
/// still being said. Each phrase's start and end go back to the coach for its VOICE events. When the
/// output changes mid-session (a headset connects or drops) it is logged with its delay, and
/// [onDelay] hears the delay iOS now reports, 0 when none (FBBD-354).
final class CoachVoice: NSObject, CoachSpeaker, AVSpeechSynthesizerDelegate {
    private let synthesizer = AVSpeechSynthesizer()
    private let voice: AVSpeechSynthesisVoice?
    private let rate: Float
    private let pitch: Float
    private let lock = NSLock()
    private var current: [ObjectIdentifier: CoachSpeech] = [:]
    private var playing = 0
    private var routeObserver: NSObjectProtocol?

    /// The output changed: the delay iOS reports for the new one, in ms, 0 when it reports none.
    var onDelay: ((Int) -> Void)?

    /// The voice, speed and pitch chosen in Settings › Spoken coach (FBBD-338), as Test voice says them.
    init(settings: CoachSettings) {
        voice = CoachVoiceChoice.voice(settings)
        rate = CoachVoiceChoice.rate(settings)
        pitch = settings.speech.pitch
        super.init()
        synthesizer.delegate = self
        routeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification, object: nil, queue: nil
        ) { [weak self] note in self?.routeChanged(note) }
        // Spoken prompts over music: other audio ducks while a phrase plays, as navigation guidance.
        try? AVAudioSession.sharedInstance().setCategory(
            .playback, mode: .voicePrompt, options: [.duckOthers, .interruptSpokenAudioAndMixWithOthers]
        )
    }

    /// The output's delay as iOS reports it for the route now, in ms (FBBD-49); the cues are timed for it.
    static var outputDelayMillis: Int {
        Int((AVAudioSession.sharedInstance().outputLatency * 1_000).rounded())
    }

    /// The output now, for the debug log (FBBD-354): "route=headset port=BluetoothA2DPOutput
    /// name=AirPods Pro out_latency_ms=190 io_buffer_ms=23".
    static var outputText: String {
        let session = AVAudioSession.sharedInstance()
        let (route, port) = route()
        let name = session.currentRoute.outputs.first?.portName ?? "none"
        let buffer = Int((session.ioBufferDuration * 1_000).rounded())
        return "route=\(route) port=\(port) name=\(name) out_latency_ms=\(outputDelayMillis) io_buffer_ms=\(buffer)"
    }

    /// Why the output changed, in words, for the debug log.
    static func reasonText(_ raw: UInt?) -> String {
        switch raw.flatMap(AVAudioSession.RouteChangeReason.init(rawValue:)) {
        case .newDeviceAvailable: "new device"
        case .oldDeviceUnavailable: "device gone"
        case .categoryChange: "category change"
        case .override: "override"
        case .wakeFromSleep: "wake from sleep"
        case .noSuitableRouteForCategory: "no suitable route"
        case .routeConfigurationChange: "configuration change"
        default: "other"
        }
    }

    private func routeChanged(_ note: Notification) {
        let reason = Self.reasonText(note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt)
        let previous = (note.userInfo?[AVAudioSessionRouteChangePreviousRouteKey] as? AVAudioSessionRouteDescription)?
            .outputs.first.map { "\($0.portType.rawValue) \($0.portName)" } ?? "none"
        AppLog.i("Coach", "audio output now \(Self.outputText), was \(previous) (\(reason))")
        onDelay?(Self.outputDelayMillis)
    }

    var speaking: Bool {
        lock.lock()
        defer { lock.unlock() }
        return playing > 0
    }

    func say(text: String, speech: CoachSpeech) -> Bool {
        guard voice != nil else { return false }
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = voice
        utterance.rate = rate
        utterance.pitchMultiplier = pitch
        lock.lock()
        current[ObjectIdentifier(utterance)] = speech
        playing += 1
        lock.unlock()
        try? AVAudioSession.sharedInstance().setActive(true)
        // A new phrase replaces one still being said, as on Android (QUEUE_FLUSH).
        if synthesizer.isSpeaking { synthesizer.stopSpeaking(at: .immediate) }
        synthesizer.speak(utterance)
        return true
    }

    /// Stops speaking and gives the other apps' audio back; at the end of a recording.
    func shutdown() {
        if let routeObserver { NotificationCenter.default.removeObserver(routeObserver) }
        routeObserver = nil
        onDelay = nil
        synthesizer.stopSpeaking(at: .immediate)
        // Off the main queue: deactivating blocks while audio (the camera's microphone too) is still
        // running, which held the screen when the recording ended. Tried again while the camera stops,
        // so other apps' sound is not left ducked.
        DispatchQueue.global(qos: .utility).async {
            for _ in 0 ..< 10 {
                if (try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)) != nil { return }
                Thread.sleep(forTimeInterval: 0.3)
            }
        }
    }

    /// Where the sound goes: "headset" for Bluetooth, wired or the car's audio, else "speaker"; and the port type.
    private static func route() -> (String, String) {
        let port = AVAudioSession.sharedInstance().currentRoute.outputs.first?.portType
        let speaker: Set<AVAudioSession.Port> = [.builtInSpeaker, .builtInReceiver]
        let name = port.map { speaker.contains($0) ? "speaker" : "headset" } ?? "speaker"
        return (name, port?.rawValue ?? "unknown")
    }

    private func take(_ utterance: AVSpeechUtterance, remove: Bool) -> CoachSpeech? {
        lock.lock()
        defer { lock.unlock() }
        let id = ObjectIdentifier(utterance)
        guard let speech = current[id] else { return nil }
        if remove {
            current[id] = nil
            playing = max(playing - 1, 0)
        }
        return speech
    }

    private func finished(_ utterance: AVSpeechUtterance, _ end: String) {
        take(utterance, remove: true)?.ended(end: end)
        if !speaking {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
    }

    // MARK: AVSpeechSynthesizerDelegate

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didStart utterance: AVSpeechUtterance) {
        let (route, device) = Self.route()
        take(utterance, remove: false)?.started(route: route, device: device)
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        finished(utterance, "done")
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        finished(utterance, "stopped")
    }
}
