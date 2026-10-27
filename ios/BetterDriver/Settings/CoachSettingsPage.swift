import AVFoundation
import BetterDriverCore
import SwiftUI

/// What each verbosity says, as on Android.
private func verbosityLabel(_ v: Verbosity) -> String {
    switch v.name {
    case "Off": "Off"
    case "LapTimes": "Lap times only"
    case "Focus": "Lap times and the 3 corners losing most"
    default: "Lap times and every corner"
    }
}

/// The settings row's value (UI-64 B): "3 focus corners".
func coachLabel(_ s: CoachSettings) -> String {
    switch s.verbosity.name {
    case "Off": "Off"
    case "LapTimes": "Lap times"
    case "Focus": "3 focus corners"
    default: "Every corner"
    }
}

private func languageName(_ l: CoachLanguage) -> String { l.name == "Polish" ? "Polski" : "English" }

/// The phone's voices of a coach language, as iOS lists them.
private func voices(_ l: CoachLanguage) -> [AVSpeechSynthesisVoice] {
    let prefix = l.tag
    return AVSpeechSynthesisVoice.speechVoices()
        .filter { $0.language.hasPrefix(prefix) }
        .sorted { ($0.language, $0.name) < ($1.language, $1.name) }
}

/// Spoken coach settings (FBBD-98, UI-64 B), as the Android page: how much it says, the headset delay
/// used when it cannot be measured, learning laps, and the voice: language, voice, speed and pitch
/// (UI-90 A, UI-91 B), with Test voice. Each change is saved at once and applies from the next session.
struct CoachSettingsPage: View {
    @EnvironmentObject private var settings: SettingsModel
    @State private var delay: Double = 0
    @State private var rate: Double = 1
    @State private var pitch: Double = 1
    @State private var picking: Pick?
    @State private var tester = VoiceTester()

    private enum Pick: Identifiable {
        case language, voice
        var id: Self { self }
    }

    var body: some View {
        let s = settings.coach
        ShellPage(title: "Spoken coach") {
            ShellCard {
                ForEach(SettingsCore.shared.verbosities, id: \.name) { v in
                    radio(verbosityLabel(v), on: s.verbosity == v) {
                        settings.updateCoach { SettingsCore.shared.withVerbosity(s: $0, v: v) }
                    }
                }
            }
            ShellCard { delayAndLaps(s) }
            ShellCard { voiceAndSpeed(s) }
            ShellCard {
                HStack {
                    DimText("Plays “\(SettingsCore.shared.sampleCue(language: s.language))” on the current audio output.")
                    Spacer()
                    Button("Test voice") { tester.say(s) }.tint(Palette.accent)
                }
            }
        }
        .onAppear {
            delay = Double(s.audioDelayMillis) / 1_000
            rate = Double(s.speech.rate)
            pitch = Double(s.speech.pitch)
        }
        .sheet(item: $picking) { pick in
            switch pick {
            case .language: languageSheet(s)
            case .voice: voiceSheet(s)
            }
        }
    }

    private func radio(_ text: String, on: Bool, pick: @escaping () -> Void) -> some View {
        Button(action: pick) {
            HStack(spacing: 12) {
                Image(systemName: on ? "largecircle.fill.circle" : "circle")
                    .foregroundStyle(on ? Palette.accent : Palette.onSurfaceVariant)
                Text(text).foregroundStyle(Palette.onBackground)
                Spacer()
            }
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private func delayAndLaps(_ s: CoachSettings) -> some View {
        Text("Headset delay").foregroundStyle(Palette.onBackground)
        DimText(VoiceTester.delayNow)
        DimText(
            "Measured on its own at the start of every session; cues are said that much earlier. " +
                "Nothing to do but connect the headset."
        )
        Text("If it can't be measured \(String(format: "%.1f s", delay))")
            .foregroundStyle(Palette.onBackground)
            .padding(.top, 8)
        Slider(value: $delay, in: 0...Double(SettingsCore.shared.maxAudioDelayMillis) / 1_000, step: 0.1, onEditingChanged: { editing in
            if !editing {
                let ms = Int32((delay * 1_000).rounded())
                settings.updateCoach { SettingsCore.shared.withDelay(s: $0, millis: ms) }
            }
        })
        .tint(Palette.accent)
        HStack {
            Text("Learning laps").foregroundStyle(Palette.onBackground)
            Spacer()
            Button("−") { settings.updateCoach { SettingsCore.shared.withLearningLaps(s: $0, laps: s.learningLaps - 1) } }
                .disabled(s.learningLaps <= 0)
            Text("\(s.learningLaps)").foregroundStyle(Palette.onBackground).frame(minWidth: 24)
            Button("+") { settings.updateCoach { SettingsCore.shared.withLearningLaps(s: $0, laps: s.learningLaps + 1) } }
                .disabled(s.learningLaps >= SettingsCore.shared.maxLearningLaps)
        }
        .font(.body)
        .tint(Palette.accent)
        .padding(.top, 8)
        DimText(
            "The coach says only lap times until it has learned this many laps. " +
                "Back out on the same day, it needs only one."
        )
    }

    @ViewBuilder
    private func voiceAndSpeed(_ s: CoachSettings) -> some View {
        ListRow("Language", value: languageName(s.language), divider: false) { picking = .language }
        ListRow("Voice", value: voiceLabel(s)) { picking = .voice }
        DimText("The Voice list shows the iPhone's voices in the language above.")
        speechSlider("Speed", $rate, Double(SettingsCore.shared.minRate)...Double(SettingsCore.shared.maxRate)) {
            settings.updateCoach { SettingsCore.shared.withRate(s: $0, rate: Float(rate)) }
        }
        speechSlider("Pitch", $pitch, Double(SettingsCore.shared.minPitch)...Double(SettingsCore.shared.maxPitch)) {
            settings.updateCoach { SettingsCore.shared.withPitch(s: $0, pitch: Float(pitch)) }
        }
        DimText("Speed 0.8× to 1.5×, pitch 0.7× to 1.3×.")
    }

    private func speechSlider(
        _ name: String, _ value: Binding<Double>, _ range: ClosedRange<Double>, onSet: @escaping () -> Void
    ) -> some View {
        VStack(spacing: 0) {
            HStack {
                Text(name).foregroundStyle(Palette.onBackground)
                Spacer()
                Text(String(format: "%.1f×", value.wrappedValue)).foregroundStyle(Palette.onBackground)
            }
            Slider(value: value, in: range, step: 0.1, onEditingChanged: { editing in if !editing { onSet() } })
                .tint(Palette.accent)
        }
        .padding(.top, 8)
    }

    /// "Daniel (en-GB)", or the language's own voice when none is chosen or it is gone.
    private func voiceLabel(_ s: CoachSettings) -> String {
        if let id = s.speech.voiceName, let v = AVSpeechSynthesisVoice(identifier: id) {
            return "\(v.name) (\(v.language))"
        }
        return "Default"
    }

    private func languageSheet(_ s: CoachSettings) -> some View {
        PickSheet(title: "Language", note: "The Voice list then shows only voices of this language.") {
            ForEach(SettingsCore.shared.languages, id: \.name) { l in
                radio(languageName(l), on: s.language == l) {
                    if s.language != l { settings.updateCoach { SettingsCore.shared.withLanguage(s: $0, l: l) } }
                    picking = nil
                }
            }
        }
    }

    private func voiceSheet(_ s: CoachSettings) -> some View {
        PickSheet(title: "Voice", note: "The coach says its cues in this voice.") {
            radio("Default", on: s.speech.voiceName == nil) {
                settings.updateCoach { SettingsCore.shared.withVoice(s: $0, voiceName: nil) }
                picking = nil
            }
            ForEach(voices(s.language), id: \.identifier) { v in
                radio("\(v.name) (\(v.language))", on: s.speech.voiceName == v.identifier) {
                    settings.updateCoach { SettingsCore.shared.withVoice(s: $0, voiceName: v.identifier) }
                    picking = nil
                }
            }
        }
    }
}

private struct PickSheet<Content: View>: View {
    let title: String
    let note: String
    @ViewBuilder let content: Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text(title).font(.title3.weight(.semibold)).foregroundStyle(Palette.onBackground).padding(.bottom, 8)
                content
                DimText(note).padding(.top, 8)
            }
            .padding(.horizontal, 20)
            .padding(.top, 22)
        }
        .presentationDetents([.medium, .large])
        .presentationBackground(Palette.surface)
    }
}

/// Says the sample cue in the coach's language, voice, speed and pitch, on the current audio output.
final class VoiceTester {
    private let synthesizer = AVSpeechSynthesizer()

    func say(_ s: CoachSettings) {
        let utterance = AVSpeechUtterance(string: SettingsCore.shared.sampleCue(language: s.language))
        utterance.voice = CoachVoiceChoice.voice(s)
        utterance.rate = CoachVoiceChoice.rate(s)
        utterance.pitchMultiplier = s.speech.pitch
        synthesizer.stopSpeaking(at: .immediate)
        synthesizer.speak(utterance)
    }

    /// "190 ms now, on AirPods Pro": the delay iOS reports for the output connected now.
    static var delayNow: String {
        let session = AVAudioSession.sharedInstance()
        let ms = Int((session.outputLatency * 1_000).rounded())
        let route = session.currentRoute.outputs.first?.portName ?? "the speaker"
        return "\(ms) ms now, on \(route)"
    }
}

/// The coach's voice from its settings, for the coach and Test voice: the chosen voice, else the
/// phone's voice of the language (British, then any English; Polish), at the chosen speed.
enum CoachVoiceChoice {
    static func voice(_ s: CoachSettings) -> AVSpeechSynthesisVoice? {
        if let id = s.speech.voiceName, let v = AVSpeechSynthesisVoice(identifier: id) { return v }
        let tags = s.language.name == "Polish" ? ["pl-PL"] : ["en-GB", "en-US"]
        return tags.lazy.compactMap { AVSpeechSynthesisVoice(language: $0) }.first
            ?? AVSpeechSynthesisVoice(language: s.language.tag)
    }

    /// Android's 1× is the engine's normal speed; iOS's normal is AVSpeechUtteranceDefaultSpeechRate.
    static func rate(_ s: CoachSettings) -> Float {
        min(AVSpeechUtteranceMaximumSpeechRate, AVSpeechUtteranceDefaultSpeechRate * s.speech.rate)
    }
}
