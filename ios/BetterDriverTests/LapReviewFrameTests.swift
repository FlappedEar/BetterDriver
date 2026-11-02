import XCTest
import SwiftUI
import UIKit
import BetterDriverCore
@testable import BetterDriver

/// The lap review's cost a display frame (FBBD-429): on the iPad app on a Mac it played sluggishly,
/// as every frame re-ran the whole screen, re-read thousands of points from the core and redrew the
/// map line and chart traces. Plays a made-up pair of 1:50 laps (2 750 fixes each) in a window for
/// two seconds and checks that only the cursors follow the review.
@MainActor
final class LapReviewFrameTests: XCTestCase {
    func testPlayingTheLapReviewRedrawsOnlyTheCursors() {
        let review = LapVideoReviewKt.syntheticLapVideoReview(fixesPerLap: 2_750)
        LapReviewProbe.reset()
        let st = ReviewTransport(review: review)
        let host = UIHostingController(rootView: NavigationStack { LapVideoView(transport: st) })
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        spin(1.0)
        XCTAssertGreaterThan(LapReviewProbe.staticDraws, 0, "the map line and chart traces were never drawn")
        XCTAssertEqual(LapReviewProbe.geometryBuilds, 1, "the lines were read from the core more than once")

        let drawsBefore = LapReviewProbe.staticDraws
        let bodiesBefore = LapReviewProbe.screenBodies
        let framesBefore = LapReviewProbe.frames
        let workBefore = LapReviewProbe.workSeconds
        st.playPause()
        spin(2.0)
        st.playPause()
        let frames = LapReviewProbe.frames - framesBefore
        let draws = LapReviewProbe.staticDraws - drawsBefore
        let bodies = LapReviewProbe.screenBodies - bodiesBefore
        let meanWorkMs = frames > 0 ? (LapReviewProbe.workSeconds - workBefore) / Double(frames) * 1000 : 0
        let summary = "frames \(frames), static redraws \(draws), screen runs \(bodies), "
            + "mean frame-loop work \(String(format: "%.2f", meanWorkMs)) ms, max \(String(format: "%.2f", LapReviewProbe.maxWorkSeconds * 1000)) ms"
        let note = XCTAttachment(string: summary)
        note.name = "Lap review frame cost"
        note.lifetime = .keepAlways
        add(note)

        XCTAssertGreaterThan(frames, 20, "the review did not run its frame loop while playing: \(summary)")
        XCTAssertGreaterThan(st.cursor, 1_000_000, "the cursor did not move: \(summary)")
        XCTAssertEqual(LapReviewProbe.geometryBuilds, 1, "the lines were read again while playing: \(summary)")
        // Play and Pause re-run the screen; the frames in between must not.
        XCTAssertLessThanOrEqual(bodies, 4, "the whole screen re-ran each frame: \(summary)")
        XCTAssertLessThanOrEqual(draws, 4, "the map line or chart traces were redrawn each frame: \(summary)")
        // Generous for a busy CI simulator; it was the per-frame redraw, not this loop, that was slow.
        XCTAssertLessThan(meanWorkMs, 4, "the frame loop is too slow: \(summary)")
    }

    private func spin(_ seconds: TimeInterval) {
        RunLoop.main.run(until: Date().addingTimeInterval(seconds))
    }
}
