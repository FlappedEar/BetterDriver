import AVFoundation
import XCTest
@testable import BetterDriver

/// The once-a-minute audio line (FBBD-305) says whether the app held its audio session, and where the
/// sound went, so a drive log can set the RaceBox's lost messages against a held Bluetooth headset.
final class AudioMinuteTests: XCTestCase {
    func testTheLineNamesRouteAndHeldSession() {
        let line = AudioMinute.text(
            output: "bt_a2dp", outputName: "JBL Tune 135BT", input: "mic", category: "PlayAndRecord",
            mode: "VideoRecording", appActive: true, micHeld: true, othersPlaying: false
        )
        XCTAssertEqual(
            line,
            "audio minute: out=bt_a2dp (JBL Tune 135BT) in=mic category=PlayAndRecord mode=VideoRecording " +
                "app_active=true mic_held=true others_playing=false"
        )
    }

    func testTheSpeakerHasNoName() {
        let line = AudioMinute.text(
            output: "speaker", outputName: nil, input: "none", category: "Playback", mode: "VoicePrompt",
            appActive: false, micHeld: false, othersPlaying: true
        )
        XCTAssertTrue(line.hasPrefix("audio minute: out=speaker in=none "), line)
    }

    func testCategoryAndModeLoseApplesPrefix() {
        XCTAssertEqual(
            AudioMinute.short(AVAudioSession.Category.playAndRecord.rawValue, prefix: "AVAudioSessionCategory"),
            "PlayAndRecord"
        )
        XCTAssertEqual(AudioMinute.short("Other", prefix: "AVAudioSessionMode"), "Other")
    }

    func testInputsAreNamed() {
        XCTAssertEqual(AudioMinute.inputType(.builtInMic), "mic")
        XCTAssertEqual(AudioMinute.inputType(.bluetoothHFP), "bt_sco")
        XCTAssertEqual(AudioMinute.inputType(nil), "none")
    }

    func testActiveFollowsActivateAndGiveBack() {
        let use = AudioSessionUse {}
        XCTAssertFalse(use.isActive)
        use.activated()
        XCTAssertTrue(use.isActive)
        use.microphoneStarted()
        XCTAssertFalse(use.giveBack())
        XCTAssertTrue(use.isActive, "kept for the microphone")
        use.microphoneStopped()
        XCTAssertTrue(use.giveBack())
        XCTAssertFalse(use.isActive)
    }

    func testTheLineNowHasEveryField() {
        let line = AudioMinute.now(use: AudioSessionUse {})
        for key in ["out=", " in=", " category=", " mode=", " app_active=false", " mic_held=false", " others_playing="] {
            XCTAssertTrue(line.contains(key), "\(key) in \(line)")
        }
    }
}
