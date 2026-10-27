import AVFoundation
import BetterDriverCore

/// The coach's language on the iPhone (FBBD-333): Polish when the phone's first language is Polish,
/// else English, the two the Android coach speaks (FBBD-245). Stored once picked, as the Android setting.
enum CoachLanguagePick {
    private static let key = "coachLanguage"

    static var polish: Bool {
        get {
            if let saved = UserDefaults.standard.string(forKey: key) { return saved == "pl" }
            return Locale.preferredLanguages.first?.hasPrefix("pl") == true
        }
        set { UserDefaults.standard.set(newValue ? "pl" : "en", forKey: key) }
    }
}

/// The coach's voice on the iPhone (FBBD-333), as Voice on Android: live text-to-speech in the coach's
/// language, to whatever output is connected (a helmet headset or the car over Bluetooth, else the
/// speaker), with music ducked under it while a phrase plays (FBBD-47). A new phrase replaces one
/// still being said. Each phrase's start and end go back to the coach for its VOICE events.
final class CoachVoice: NSObject, CoachSpeaker, AVSpeechSynthesizerDelegate {
    private let synthesizer = AVSpeechSynthesizer()
    private let voice: AVSpeechSynthesisVoice?
    private let lock = NSLock()
    private var current: [ObjectIdentifier: CoachSpeech] = [:]
    private var playing = 0

    init(polish: Bool) {
        // The phone's own voice of the language: British, then American or any English; Polish.
        let tags = polish ? ["pl-PL"] : ["en-GB", "en-US"]
        voice = tags.lazy.compactMap { AVSpeechSynthesisVoice(language: $0) }.first
            ?? AVSpeechSynthesisVoice(language: polish ? "pl" : "en")
        super.init()
        synthesizer.delegate = self
        // Spoken prompts over music: other audio ducks while a phrase plays, as navigation guidance.
        try? AVAudioSession.sharedInstance().setCategory(
            .playback, mode: .voicePrompt, options: [.duckOthers, .interruptSpokenAudioAndMixWithOthers]
        )
    }

    /// The output's delay as iOS reports it for the route now, in ms (FBBD-49); the cues are timed for it.
    static var outputDelayMillis: Int {
        Int((AVAudioSession.sharedInstance().outputLatency * 1_000).rounded())
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
        synthesizer.stopSpeaking(at: .immediate)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
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
