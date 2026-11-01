import AVFoundation
import BetterDriverCore

/// The coach's voice on the iPhone (FBBD-333), as Voice on Android: live text-to-speech in the coach's
/// language, to whatever output is connected (a helmet headset or the car over Bluetooth, else the
/// speaker), with music ducked under it while a phrase plays (FBBD-47). A new phrase replaces one
/// still being said. Each phrase's start and end go back to the coach for its VOICE events. When the
/// output changes mid-session (a headset connects or drops) it is logged as Android logs it, and
/// [onDelay] hears the delay iOS now reports, 0 when none (FBBD-354, FBBD-355).
///
/// Pre-rendered (FBBD-48), as on Android: when Settings › Spoken coach › Voice playback says so, every
/// word and number is rendered to clips at the start ([CueClipRenderer]) and a phrase is joined from them
/// and played at once on an audio engine, without waiting for speech to be synthesised. A phrase the
/// clips do not cover, or any phrase until they are ready, is said live.
final class CoachVoice: NSObject, CoachSpeaker, AVSpeechSynthesizerDelegate {
    private let synthesizer = AVSpeechSynthesizer()
    private let clips: CueClipRenderer?
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private var playerFormat: AVAudioFormat?
    /// The clip phrase playing.
    private var clip: (id: Int, speech: CoachSpeech)?
    private var clipIds = 0
    /// Held while the audio engine is set up, started or paused.
    private let engineLock = NSLock()
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
        clips = settings.voice.name == "PreRendered" ? CueClipRenderer(settings: settings) : nil
        super.init()
        synthesizer.delegate = self
        clips?.start()
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

    /// Whether the pre-rendered clips are loaded, so phrases are played from them (FBBD-48).
    var clipsReady: Bool { clips?.cache.ready ?? false }

    var speaking: Bool {
        lock.lock()
        defer { lock.unlock() }
        return playing > 0 || clip != nil
    }

    func say(text: String, speech: CoachSpeech) -> Bool {
        if let sound = clips?.cache.phrase(text: text), play(sound, speech) { return true }
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
        // A new phrase replaces one still being said, as on Android (QUEUE_FLUSH). A cut clip's engine
        // is paused, so the audio can go back to the other apps when the live phrase ends.
        if cutClip() { pauseEngine() }
        if synthesizer.isSpeaking { synthesizer.stopSpeaking(at: .immediate) }
        synthesizer.speak(utterance)
        return true
    }

    /// Plays a phrase joined from clips (FBBD-48), cutting off one still being said; false when the
    /// audio engine cannot start, so it is said live.
    private func play(_ sound: CueClipSound, _ speech: CoachSpeech) -> Bool {
        guard let format = AVAudioFormat(
            commonFormat: .pcmFormatFloat32, sampleRate: Double(sound.sampleRate), channels: 1, interleaved: false
        ) else { return false }
        let samples = sound.samples
        let frames = samples.count / MemoryLayout<Int16>.size
        guard frames > 0, let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frames)),
              let out = buffer.floatChannelData?[0] else { return false }
        buffer.frameLength = AVAudioFrameCount(frames)
        samples.withUnsafeBytes { raw in
            for i in 0 ..< frames {
                out[i] = Float(raw.loadUnaligned(fromByteOffset: i * 2, as: Int16.self)) / Float(Int16.max)
            }
        }
        if synthesizer.isSpeaking { synthesizer.stopSpeaking(at: .immediate) }
        cutClip()
        try? AVAudioSession.sharedInstance().setActive(true)
        engineLock.lock()
        defer { engineLock.unlock() }
        if engine.attachedNodes.contains(player) == false { engine.attach(player) }
        if playerFormat != format {
            engine.disconnectNodeOutput(player)
            engine.connect(player, to: engine.mainMixerNode, format: format)
            playerFormat = format
        }
        if !engine.isRunning {
            do { try engine.start() } catch {
                AppLog.w("Voice", "clip not played, said live: \(error.localizedDescription)")
                return false
            }
        }
        lock.lock()
        clipIds += 1
        let id = clipIds
        clip = (id, speech)
        lock.unlock()
        player.scheduleBuffer(buffer, at: nil, options: .interrupts, completionCallbackType: .dataPlayedBack) {
            [weak self] _ in self?.clipEnded(id)
        }
        player.play()
        let (route, device) = Self.route()
        speech.started(route: route, device: device, clip: true)
        return true
    }

    /// Stops the clip phrase playing, if any: it ends as "stopped". True when there was one.
    @discardableResult
    private func cutClip() -> Bool {
        lock.lock()
        let cut = clip
        clip = nil
        lock.unlock()
        guard let cut else { return false }
        player.stop()
        cut.speech.ended(end: "stopped")
        return true
    }

    /// Pauses the clips' audio engine, which holds the audio session while it runs.
    private func pauseEngine() {
        engineLock.lock()
        defer { engineLock.unlock() }
        if engine.isRunning { engine.pause() }
    }

    /// A clip phrase played to its end (one cut off has ended already); the audio goes back to the
    /// other apps when nothing else is said.
    private func clipEnded(_ id: Int) {
        lock.lock()
        guard let ended = clip, ended.id == id else {
            lock.unlock()
            return
        }
        clip = nil
        let quiet = playing == 0
        lock.unlock()
        ended.speech.ended(end: "done")
        guard quiet else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.engineLock.lock()
            defer { self.engineLock.unlock() }
            guard !self.speaking else { return }
            self.engine.pause()
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
    }

    /// Stops speaking and gives the other apps' audio back; at the end of a recording.
    func shutdown() {
        if let routeObserver { NotificationCenter.default.removeObserver(routeObserver) }
        routeObserver = nil
        onDelay = nil
        clips?.stop()
        synthesizer.stopSpeaking(at: .immediate)
        cutClip()
        engineLock.lock()
        engine.stop()
        engineLock.unlock()
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
        take(utterance, remove: false)?.started(route: route, device: device, clip: false)
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        finished(utterance, "done")
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        finished(utterance, "stopped")
    }
}
