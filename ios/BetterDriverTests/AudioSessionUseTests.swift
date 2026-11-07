import AVFoundation
import BetterDriverCore
import XCTest
@testable import BetterDriver

/// The coach's voice never gives the audio session back while the phone camera records sound through
/// it (FBBD-466): Apple stops running audio input when its session is deactivated, so the video's
/// sound would end at the coach's first phrase. The session goes back to the other apps once the
/// camera's microphone has stopped.
final class AudioSessionUseTests: XCTestCase {
    /// Counts the deactivations instead of making them.
    private final class Deactivations {
        private let lock = NSLock()
        private var n = 0
        var count: Int {
            lock.lock()
            defer { lock.unlock() }
            return n
        }

        func add() {
            lock.lock()
            n += 1
            lock.unlock()
        }
    }

    func testTheSessionIsKeptWhileAMicrophoneRecords() {
        let made = Deactivations()
        let use = AudioSessionUse { made.add() }
        use.microphoneStarted()
        XCTAssertTrue(use.recording)
        XCTAssertFalse(use.giveBack())
        XCTAssertFalse(use.giveBack())
        XCTAssertEqual(made.count, 0, "no deactivation while the microphone records")
        use.microphoneStopped()
        XCTAssertFalse(use.recording)
        XCTAssertTrue(use.giveBack())
        XCTAssertEqual(made.count, 1, "given back once the microphone stopped")
    }

    func testAStopWithoutAStartDoesNotLeaveTheCountBelowZero() {
        let made = Deactivations()
        let use = AudioSessionUse { made.add() }
        use.microphoneStopped()
        use.microphoneStarted()
        XCTAssertFalse(use.giveBack())
        XCTAssertEqual(made.count, 0)
    }

    func testRetriesStopWhenARecordingHoldsTheSession() {
        let made = Deactivations()
        let failing = AudioSessionUse {
            made.add()
            throw NSError(domain: "busy", code: 1)
        }
        failing.giveBackSoon(tries: 3, wait: 0.01)
        let tried = expectation(description: "tried three times")
        DispatchQueue.global().asyncAfter(deadline: .now() + 1) { tried.fulfill() }
        wait(for: [tried], timeout: 3)
        XCTAssertEqual(made.count, 3, "a failed deactivation is tried again")

        let kept = Deactivations()
        let held = AudioSessionUse { kept.add() }
        held.microphoneStarted()
        held.giveBackSoon(tries: 3, wait: 0.01)
        let waited = expectation(description: "waited")
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.5) { waited.fulfill() }
        wait(for: [waited], timeout: 3)
        XCTAssertEqual(kept.count, 0, "nothing deactivated while a recording holds the session")
    }

    /// The coach's real voice says phrases one after another, as at the track, while the camera's
    /// microphone records: not one of them ends with the session given back. Without a recording,
    /// the phrase's end gives it back, as before.
    func testCoachPhrasesNeverDeactivateTheSessionWhileTheCameraRecordsSound() {
        let made = Deactivations()
        let use = AudioSessionUse { made.add() }
        use.microphoneStarted()
        let voice = CoachVoice(settings: LiveCoachKt.coachSettings(polish: false, audioDelayMillis: 0), use: use)
        for text in ["Turn 3", "Turn 5", "Lap"] {
            let heard = Heard(expectation(description: text))
            XCTAssertTrue(voice.say(text: text, speech: heard), text)
            wait(for: [heard.done], timeout: 20)
            settle()
            XCTAssertEqual(made.count, 0, "after \"\(text)\" (\(heard.end ?? "no end")) the session was given back")
        }
        XCTAssertTrue(use.recording)

        // The camera stops: the next phrase's end gives the session back.
        use.microphoneStopped()
        let last = Heard(expectation(description: "after the recording"))
        XCTAssertTrue(voice.say(text: "Turn 1", speech: last))
        wait(for: [last.done], timeout: 20)
        settle()
        XCTAssertGreaterThanOrEqual(made.count, 1, "given back when no recording holds it")
        voice.shutdown()
    }

    func testTheCoachsEndKeepsTheSessionWhileTheCameraStillRecords() {
        let made = Deactivations()
        let use = AudioSessionUse { made.add() }
        use.microphoneStarted()
        let voice = CoachVoice(settings: LiveCoachKt.coachSettings(polish: false, audioDelayMillis: 0), use: use)
        voice.shutdown()
        let waited = expectation(description: "waited for retries")
        DispatchQueue.global().asyncAfter(deadline: .now() + 1) { waited.fulfill() }
        wait(for: [waited], timeout: 3)
        XCTAssertEqual(made.count, 0, "the camera gives the session back when it stops")
    }

    /// Work the coach queued before STOP never speaks after it, nor into the next session (audit BD-T09).
    func testAPhraseAskedForAfterTheSessionEndedIsNotSaid() {
        let use = AudioSessionUse {}
        let voice = CoachVoice(settings: LiveCoachKt.coachSettings(polish: false, audioDelayMillis: 0), use: use)
        voice.shutdown()
        let late = Heard(expectation(description: "never said"))
        late.done.isInverted = true
        XCTAssertFalse(voice.say(text: "Turn 3", speech: late))
        XCTAssertFalse(voice.speaking)
        wait(for: [late.done], timeout: 1)
    }

    /// The voice check (FBBD-466): 30 s after START, then every 2 min, never over a phrase being said
    /// and never at a saved track, where the coach speaks itself.
    func testTheVoiceCheckIsSaidAwayFromASavedTrackOnly() {
        var check = VoiceCheck()
        XCTAssertFalse(check.due(elapsed: 29, atSavedTrack: false, speaking: false))
        XCTAssertFalse(check.due(elapsed: 30, atSavedTrack: false, speaking: true), "not over a phrase")
        XCTAssertTrue(check.due(elapsed: 31, atSavedTrack: false, speaking: false))
        XCTAssertEqual(check.said, 1)
        XCTAssertFalse(check.due(elapsed: 100, atSavedTrack: false, speaking: false))
        XCTAssertFalse(check.due(elapsed: 151, atSavedTrack: true, speaking: false), "not at a saved track")
        XCTAssertTrue(check.due(elapsed: 152, atSavedTrack: false, speaking: false))
        XCTAssertEqual(check.said, 2)
    }

    /// Lets queued main-queue work (a clip's end) run.
    private func settle() {
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))
    }

    private final class Heard: NSObject, CoachSpeech {
        let done: XCTestExpectation
        var end: String?

        init(_ done: XCTestExpectation) { self.done = done }

        func started(route: String, device: String, clip: Bool) {}

        func ended(end: String) {
            self.end = end
            done.fulfill()
        }
    }
}
