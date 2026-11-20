import XCTest
@testable import BetterDriver

/// Review v1.34.1 F20 (FBBD-484): About and credits names every font family the app bundles, as
/// Android's AboutUiTest checks for its own.
final class FontCreditsTests: XCTestCase {
    func testEveryBundledFontFamilyIsCredited() {
        let files = Bundle.main.urls(forResourcesWithExtension: "ttf", subdirectory: nil) ?? []
        let bundled = Set(files.map { url in
            url.deletingPathExtension().lastPathComponent
                .replacingOccurrences(of: "_(regular|medium|semibold|bold)$", with: "", options: .regularExpression)
        })
        XCTAssertFalse(bundled.isEmpty, "no bundled fonts found")
        let credited = Set(AboutPage.creditedFonts.map { $0.lowercased().replacingOccurrences(of: " ", with: "_") })
        XCTAssertEqual(bundled, credited)
    }
}
