import XCTest
@testable import BetterDriver

/// Apple's satellite tiles under the perfect lap in 3D (FBBD-381) never block the main queue and
/// never fail: a tile not at hand, a zoom MapKit has no picture for, or a tile off the map gives nil,
/// and the road is drawn instead.
final class PerfectLapTilesTests: XCTestCase {
    func testOnTheMainQueueATileNotAtHandIsNilAtOnce() throws {
        let tiles = try XCTUnwrap(PerfectLapTiles.make())
        XCTAssertNil(tiles.tile(z: 18, x: 1, y: 1))
        XCTAssertNil(tiles.load(z: 18, x: 1, y: 1))
        XCTAssertEqual(tiles.attribution, "Apple Maps")
    }

    func testTilesOffTheMapAreNilWithoutASnapshot() throws {
        let tiles = try XCTUnwrap(PerfectLapTiles.make())
        let done = expectation(description: "loaded")
        DispatchQueue.global().async {
            XCTAssertNil(tiles.load(z: 30, x: 0, y: 0))
            XCTAssertNil(tiles.load(z: -1, x: 0, y: 0))
            XCTAssertNil(tiles.load(z: 2, x: -1, y: 0))
            XCTAssertNil(tiles.load(z: 2, x: 0, y: 99))
            done.fulfill()
        }
        wait(for: [done], timeout: 10)
    }
}
