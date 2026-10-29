import AVFoundation
import BetterDriverCore
import XCTest
@testable import BetterDriver

/// The coach's voice logs the output it plays on and passes a new output's delay on (FBBD-354), so a
/// headset that connects mid-session retimes the cues and the headset delay test reads the log.
final class CoachVoiceTests: XCTestCase {
    func testAnOutputChangePassesTheNewDelayOn() {
        let voice = CoachVoice(settings: LiveCoachKt.coachSettings(polish: false, audioDelayMillis: 0))
        defer { voice.shutdown() }
        var delays: [Int] = []
        voice.onDelay = { delays.append($0) }
        NotificationCenter.default.post(
            name: AVAudioSession.routeChangeNotification, object: nil,
            userInfo: [AVAudioSessionRouteChangeReasonKey: AVAudioSession.RouteChangeReason.newDeviceAvailable.rawValue]
        )
        XCTAssertEqual(delays, [CoachVoice.outputDelayMillis])
    }

    func testNothingIsPassedOnAfterTheSessionEnds() {
        let voice = CoachVoice(settings: LiveCoachKt.coachSettings(polish: false, audioDelayMillis: 0))
        var delays: [Int] = []
        voice.onDelay = { delays.append($0) }
        voice.shutdown()
        NotificationCenter.default.post(name: AVAudioSession.routeChangeNotification, object: nil)
        XCTAssertEqual(delays, [])
    }

    func testTheOutputIsDescribedForTheLog() {
        let text = CoachVoice.outputText
        for field in ["route=", "port=", "name=", "out_latency_ms=", "io_buffer_ms="] {
            XCTAssertTrue(text.contains(field), text)
        }
        XCTAssertEqual(CoachVoice.reasonText(AVAudioSession.RouteChangeReason.newDeviceAvailable.rawValue), "new device")
        XCTAssertEqual(CoachVoice.reasonText(AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue), "device gone")
        XCTAssertEqual(CoachVoice.reasonText(nil), "other")
    }
}
