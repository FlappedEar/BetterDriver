import AVFoundation
import BetterDriverCore

/// Renders the coach's pre-rendered cue clips (FBBD-48) with the iPhone's text-to-speech, as
/// CueClipBank does on Android: every word group and number the shared cache ([CueClipCache]) still
/// lacks is written, one after another, with a synthesizer of its own (so the live voice can speak
/// meanwhile), in the voice, speed and pitch the cues are said in, and handed to the cache, which keeps
/// it and loads the set when all are there. Nothing plays aloud. Rendering that takes over two minutes,
/// or a voice that renders nothing, leaves the live voice.
final class CueClipRenderer {
    let cache: CueClipCache
    private let synthesizer = AVSpeechSynthesizer()
    private let voice: AVSpeechSynthesisVoice?
    private let rate: Float
    private let pitch: Float
    private let queue = DispatchQueue(label: "cue-clips")
    private var keys: [String] = []
    private var index = 0
    private var samples: [Int16] = []
    private var sampleRate = 0
    private var stopped = false
    private var timeout: DispatchWorkItem?

    /// Where the clips are kept between sessions: the app's caches folder, as Android's cache dir.
    static var cacheDir: String {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].path
    }

    /// The clips of [settings]' language, voice, speed and pitch, kept under [cacheDir].
    init(settings: CoachSettings, cacheDir: String = CueClipRenderer.cacheDir) {
        voice = CoachVoiceChoice.voice(settings)
        rate = CoachVoiceChoice.rate(settings)
        pitch = settings.speech.pitch
        let id = "\(voice?.identifier ?? "none")|\(settings.speech.rate)|\(settings.speech.pitch)"
        cache = CueClipCache(cacheDir: cacheDir, language: settings.language, voice: id)
    }

    /// Starts loading or rendering the clips; [done] hears whether they are ready, on any thread.
    func start(done: ((Bool) -> Void)? = nil) {
        queue.async { [self] in
            keys = cache.begin()
            guard voice != nil else { return fail("no voice to render with", done) }
            guard !keys.isEmpty else {
                done?(cache.finish())
                return
            }
            let item = DispatchWorkItem { [weak self] in
                self?.fail("rendering took over \(Int(Self.timeoutSeconds)) s", done)
            }
            timeout = item
            queue.asyncAfter(deadline: .now() + Self.timeoutSeconds, execute: item)
            renderNext(done)
        }
    }

    /// Stops rendering at the end of a session; clips rendered so far are kept for the next.
    func stop() {
        queue.async { [self] in
            stopped = true
            timeout?.cancel()
            synthesizer.stopSpeaking(at: .immediate)
        }
    }

    /// On [queue]: writes the next clip, or loads the set when all are written.
    private func renderNext(_ done: ((Bool) -> Void)?) {
        guard !stopped else { return }
        guard index < keys.count else {
            timeout?.cancel()
            stopped = true
            done?(cache.finish())
            return
        }
        let key = keys[index]
        samples = []
        sampleRate = 0
        let utterance = AVSpeechUtterance(string: key)
        utterance.voice = voice
        utterance.rate = rate
        utterance.pitchMultiplier = pitch
        synthesizer.write(utterance) { [weak self] buffer in
            guard let self, let pcm = buffer as? AVAudioPCMBuffer else { return }
            let part = Self.samples(pcm)
            let rate = Int(pcm.format.sampleRate)
            self.queue.async { self.received(key, part, rate, end: pcm.frameLength == 0, done) }
        }
    }

    /// On [queue]: a piece of [key]'s audio; an empty one ends it.
    private func received(_ key: String, _ part: [Int16], _ rate: Int, end: Bool, _ done: ((Bool) -> Void)?) {
        guard !stopped, index < keys.count, keys[index] == key else { return }
        samples += part
        if rate > 0 { sampleRate = rate }
        guard end else { return }
        let data = samples.withUnsafeBufferPointer { Data(buffer: $0) }
        guard cache.rendered(key: key, samples: data, sampleRate: Int32(sampleRate)) else {
            return fail("clip “\(key)” not rendered", done)
        }
        index += 1
        renderNext(done)
    }

    private func fail(_ why: String, _ done: ((Bool) -> Void)?) {
        guard !stopped else { return }
        stopped = true
        timeout?.cancel()
        synthesizer.stopSpeaking(at: .immediate)
        cache.fail(why: why)
        done?(false)
    }

    /// The buffer's first channel as 16-bit samples, whatever format the voice renders in.
    static func samples(_ b: AVAudioPCMBuffer) -> [Int16] {
        let n = Int(b.frameLength)
        let stride = b.stride
        guard n > 0 else { return [] }
        if let ch = b.int16ChannelData {
            return (0 ..< n).map { ch[0][$0 * stride] }
        }
        if let ch = b.floatChannelData {
            return (0 ..< n).map { Int16(max(-1, min(1, ch[0][$0 * stride])) * Float(Int16.max)) }
        }
        if let ch = b.int32ChannelData {
            return (0 ..< n).map { Int16(truncatingIfNeeded: ch[0][$0 * stride] >> 16) }
        }
        return []
    }

    static let timeoutSeconds: TimeInterval = 120
}
