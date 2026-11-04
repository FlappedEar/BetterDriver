import XCTest

extension XCTestCase {
    /// Answers the system's permission prompts (camera, microphone, Bluetooth, motion) as a first-time
    /// user allowing them would; they belong to SpringBoard, not to the app.
    ///
    /// A prompt can go by itself between the look and the tap (the app stops asking, or answers it
    /// first). XCTest then fails the query ("no matches found for ... Alert"): that race is the
    /// simulator's, not the app's, so it is an expected failure here and the test goes on.
    func allowSystemPrompts() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let labels = ["Allow", "OK", "Allow While Using App", "Allow Once"]
        for _ in 0..<4 {
            guard springboard.alerts.firstMatch.waitForExistence(timeout: 2) else { return }
            var tapped = false
            let keep = continueAfterFailure
            continueAfterFailure = true
            let options = XCTExpectedFailure.Options()
            options.isStrict = false
            XCTExpectFailure("a permission prompt can go by itself before the tap", options: options) {
                let alert = springboard.alerts.firstMatch
                if let allow = labels.map({ alert.buttons[$0] }).first(where: { $0.exists }) {
                    allow.tap()
                    tapped = true
                }
            }
            continueAfterFailure = keep
            guard tapped else { return }
        }
    }
}
