import AVFoundation
import BetterDriverCore
import XCTest
@testable import BetterDriver

/// The pre-rendered coach cues on the iPhone (FBBD-48), as on Android: the iPhone's voice renders a clip
/// of every word and number the coach can say, each is kept and loads, every cue is joined from them,
/// and the coach's voice plays a cue from clips.
final class CueClipTests: XCTestCase {
    private func preRendered() -> CoachSettings {
        let settings = LiveCoachKt.coachSettings(polish: false, audioDelayMillis: 0)
        let mode = SettingsCore.shared.voiceModes.first { $0.name == "PreRendered" }!
        return SettingsCore.shared.withVoiceMode(s: settings, v: mode)
    }

    /// Renders (or loads from the cache) every English clip, as at a coached session's start.
    private func readyClips() -> CueClipRenderer {
        let renderer = CueClipRenderer(settings: preRendered())
        let done = expectation(description: "clips ready")
        var ok = false
        renderer.start { ready in
            ok = ready
            done.fulfill()
        }
        wait(for: [done], timeout: CueClipRenderer.timeoutSeconds + 10)
        XCTAssertTrue(ok, "every clip rendered and loaded")
        return renderer
    }

    func testEveryWordAndNumberIsRenderedAndEveryCueIsJoinedFromClips() {
        let cache = readyClips().cache
        XCTAssertTrue(cache.ready)
        // Each word group and number on its own: rendered, not silent, kept.
        for key in CueClips.shared.vocabulary {
            XCTAssertNotNil(cache.phrase(text: key), key)
        }
        // Cues as the coach says them.
        let book = Phrases.shared
        let cues = [
            book.lapTime(millis: 92_400, best: true),
            book.lapTime(millis: 58_300, best: false),
            book.plan(turns: [1, 3, 5].map { KotlinInt(value: $0) }),
            "Turn 3, brake at 150, 10 later",
            "Turn 12, 60 at the apex, 5 more",
        ]
        for cue in cues {
            let sound = cache.phrase(text: cue)
            XCTAssertNotNil(sound, cue)
            XCTAssertGreaterThan(sound?.durationMillis ?? 0, 300, cue)
        }
    }

    func testTheCoachsVoicePlaysACueFromClips() {
        _ = readyClips()
        // The clips are already rendered with this voice: the coach's voice loads them from the cache.
        let loaded = expectation(description: "the voice's clips loaded")
        var ok = false
        let voice = CoachVoice(settings: preRendered()) { ready in
            ok = ready
            loaded.fulfill()
        }
        defer { voice.shutdown() }
        wait(for: [loaded], timeout: 30)
        XCTAssertTrue(ok, "clips loaded from the cache")
        XCTAssertTrue(voice.clipsReady)
        let speech = Heard()
        speech.done = expectation(description: "played")
        XCTAssertTrue(voice.say(text: Phrases.shared.lapTime(millis: 65_400, best: true), speech: speech))
        XCTAssertTrue(voice.speaking)
        wait(for: [speech.done!], timeout: 10)
        XCTAssertEqual(speech.clip, true, "said from clips, not live: \(voice.clipError ?? "no engine error")")
        XCTAssertEqual(speech.end, "done")
    }

    /// What became of one phrase.
    private final class Heard: NSObject, CoachSpeech {
        var clip: Bool?
        var end: String?
        var done: XCTestExpectation?

        func started(route: String, device: String, clip: Bool) { self.clip = clip }

        func ended(end: String) {
            self.end = end
            done?.fulfill()
        }
    }
}
