import AVFoundation
import BetterDriverCore
import XCTest
@testable import BetterDriver

/// The coach's voice logs its output as Android does and passes a new output's delay on (FBBD-354,
/// FBBD-355), so a headset that connects mid-session retimes the cues and the same checks read the log.
final class CoachVoiceTests: XCTestCase {
    func testOutputsAreNamedAsOnAndroid() {
        XCTAssertEqual(CoachVoice.outputType(.bluetoothA2DP), "bt_a2dp")
        XCTAssertEqual(CoachVoice.outputType(.bluetoothHFP), "bt_sco")
        XCTAssertEqual(CoachVoice.outputType(.bluetoothLE), "ble")
        XCTAssertEqual(CoachVoice.outputType(.usbAudio), "usb")
        XCTAssertEqual(CoachVoice.outputType(.headphones), "wired")
        XCTAssertEqual(CoachVoice.outputType(.builtInSpeaker), "speaker")
        XCTAssertEqual(CoachVoice.outputType(nil), "speaker")
    }

    func testTheDelayLineHasAndroidsText() {
        let line = CoachVoice.delayLine
        XCTAssertTrue(line.hasPrefix("output delay "), line)
        XCTAssertTrue(line.contains(" on \(CoachVoice.output.type)"), line)
        XCTAssertTrue(line.hasSuffix("reading(s)"), line)
    }

    func testTheSameOutputDoesNotRetimeTheCues() {
        let voice = CoachVoice(settings: LiveCoachKt.coachSettings(polish: false, audioDelayMillis: 0))
        defer { voice.shutdown() }
        var changes = 0
        voice.onDelay = { _, _ in changes += 1 }
        // A category change keeps the route: nothing to measure again.
        NotificationCenter.default.post(
            name: AVAudioSession.routeChangeNotification, object: nil,
            userInfo: [
                AVAudioSessionRouteChangeReasonKey: AVAudioSession.RouteChangeReason.categoryChange.rawValue,
                AVAudioSessionRouteChangePreviousRouteKey: AVAudioSession.sharedInstance().currentRoute,
            ]
        )
        XCTAssertEqual(changes, 0)
    }

    func testANewOutputPassesItsDelayOn() {
        let voice = CoachVoice(settings: LiveCoachKt.coachSettings(polish: false, audioDelayMillis: 0))
        defer { voice.shutdown() }
        var delays: [Int] = []
        var outputs: [String] = []
        voice.onDelay = { ms, output in
            delays.append(ms)
            outputs.append(output)
        }
        // No previous route: the output is new.
        NotificationCenter.default.post(
            name: AVAudioSession.routeChangeNotification, object: nil,
            userInfo: [AVAudioSessionRouteChangeReasonKey: AVAudioSession.RouteChangeReason.newDeviceAvailable.rawValue]
        )
        XCTAssertEqual(delays, [CoachVoice.outputDelayMillis])
        XCTAssertEqual(outputs, [CoachVoice.output.type])
    }

    func testNothingIsPassedOnAfterTheSessionEnds() {
        let voice = CoachVoice(settings: LiveCoachKt.coachSettings(polish: false, audioDelayMillis: 0))
        var changes = 0
        voice.onDelay = { _, _ in changes += 1 }
        voice.shutdown()
        NotificationCenter.default.post(name: AVAudioSession.routeChangeNotification, object: nil)
        XCTAssertEqual(changes, 0)
    }
}
