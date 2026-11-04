import XCTest

extension XCUIApplication {
    /// The app as every UI test launches it: its coach speaks at no volume (FBBD-457), since the
    /// Mac mini that runs the tests is also Arek's desk machine. Phrases are still said, timed and
    /// logged as usual.
    static func quiet() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["BD_QUIET_VOICE"] = "1"
        return app
    }
}
