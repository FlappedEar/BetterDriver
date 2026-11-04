import XCTest
@testable import BetterDriver

/// The OBD-II adapter link on iPhone (FBBD-271). The simulator has no Bluetooth, so the choices the
/// link makes on CoreBluetooth's connection events are checked as ObdConnectRules.
final class ObdLinkTests: XCTestCase {
    /// An adapter not in range when the setup times out keeps its connect asked, so it connects the
    /// moment it is back, also with the phone locked; one that connected and stalled starts afresh.
    func testATimedOutConnectStaysAskedOnlyWhenTheAdapterWasNeverReached() {
        XCTAssertTrue(ObdConnectRules.keepsConnectAsked(linkUp: false, connecting: true, running: true))
        XCTAssertFalse(ObdConnectRules.keepsConnectAsked(linkUp: true, connecting: false, running: true))
        XCTAssertFalse(ObdConnectRules.keepsConnectAsked(linkUp: false, connecting: false, running: true))
        // Stopped: nothing is left asked after the session.
        XCTAssertFalse(ObdConnectRules.keepsConnectAsked(linkUp: false, connecting: true, running: false))
    }

    /// A disconnect reported before the new port connected is the end of the connection closed just
    /// before (Scan car), not a failure of the new one; after it connected, it is the link dropping.
    func testADisconnectBeforeTheConnectIsTheEarlierConnections() {
        XCTAssertTrue(ObdConnectRules.isEarlierConnectionsEnd(linkUp: false))
        XCTAssertFalse(ObdConnectRules.isEarlierConnectionsEnd(linkUp: true))
    }

    /// A connect that completes with no port waiting is kept only when it was left asked, the link
    /// runs and no other adapter is connecting; else it is cancelled, so the adapter is free.
    func testAnUnclaimedConnectIsKeptOnlyForTheNextRound() {
        XCTAssertTrue(ObdConnectRules.keepsUnclaimedConnect(kept: true, running: true, portBusy: false))
        XCTAssertFalse(ObdConnectRules.keepsUnclaimedConnect(kept: false, running: true, portBusy: false))
        XCTAssertFalse(ObdConnectRules.keepsUnclaimedConnect(kept: true, running: false, portBusy: false))
        XCTAssertFalse(ObdConnectRules.keepsUnclaimedConnect(kept: true, running: true, portBusy: true))
    }
}
