import AVFoundation
import BetterDriverCore

/// The coach's voice on the iPhone (FBBD-333), as Voice on Android: live text-to-speech in the coach's
/// language, to whatever output is connected (a helmet headset or the car over Bluetooth, else the
/// speaker), with music ducked under it while a phrase plays (FBBD-47). A new phrase replaces one
/// still being said. Each phrase's start and end go back to the coach for its VOICE events. When the
/// output changes mid-session (a headset connects or drops) it is logged as Android logs it, and
/// [onDelay] hears the delay iOS now reports, 0 when none (FBBD-354, FBBD-355).
final class CoachVoice: NSObject, CoachSpeaker, AVSpeechSynthesizerDelegate {
    private let synthesizer = AVSpeechSynthesizer()
    private let voice: AVSpeechSynthesisVoice?
    private let rate: Float
    private let pitch: Float
    private let lock = NSLock()
    private var current: [ObjectIdentifier: CoachSpeech] = [:]
    private var playing = 0
    private var routeObserver: NSObjectProtocol?

    /// The output changed: the delay iOS reports for the new one, in ms, 0 when it reports none, and its type.
    var onDelay: ((Int, String) -> Void)?

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

    /// The output the coach's voice goes to, named as Android's AudioOutputs.typeOf names it: bt_a2dp,
    /// bt_sco, ble, usb, wired, car, else speaker.
    static func outputType(_ port: AVAudioSession.Port?) -> String {
        guard let port else { return "speaker" }
        switch port {
        case .bluetoothA2DP: return "bt_a2dp"
        case .bluetoothHFP: return "bt_sco"
        case .bluetoothLE: return "ble"
        case .usbAudio: return "usb"
        case .headphones, .lineOut: return "wired"
        case .carAudio: return "car"
        default: return "speaker"
        }
    }

    /// The output now: its type, and its name when it is not the phone's speaker.
    static var output: (type: String, name: String?) {
        let port = AVAudioSession.sharedInstance().currentRoute.outputs.first
        let type = outputType(port?.portType)
        return (type, type == "speaker" ? nil : port?.portName)
    }

    /// Android's OutputDelay line (FBBD-354, FBBD-355): "output delay 190 ms on bt_a2dp (AirPods Pro),
    /// 1 reading(s)"; iOS reports one value for the route, "not reported" when it is 0.
    static var delayLine: String {
        let (type, name) = output
        let ms = outputDelayMillis
        let delay = ms > 0 ? "\(ms) ms" : "not reported"
        return "output delay \(delay) on \(type)\(name.map { " (\($0))" } ?? ""), \(ms > 0 ? 1 : 0) reading(s)"
    }

    /// A route change that moved the voice to another output (not a category change on the same one):
    /// logged as Android's AudioRoute and OutputDelay lines, and its delay goes to [onDelay].
    private func routeChanged(_ note: Notification) {
        let before = (note.userInfo?[AVAudioSessionRouteChangePreviousRouteKey] as? AVAudioSessionRouteDescription)?
            .outputs.first?.uid
        guard AVAudioSession.sharedInstance().currentRoute.outputs.first?.uid != before else { return }
        let (type, name) = Self.output
        AppLog.i("AudioRoute", "audio output now \(type)\(name.map { " (\($0))" } ?? ""), measuring its delay")
        AppLog.i("OutputDelay", Self.delayLine)
        onDelay?(Self.outputDelayMillis, type)
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
    /// Where the sound goes, "headset" or "speaker", and the output's type, as in Android's VOICE events.
    private static func route() -> (String, String) {
        let type = output.type
        return (type == "speaker" ? "speaker" : "headset", type)
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
