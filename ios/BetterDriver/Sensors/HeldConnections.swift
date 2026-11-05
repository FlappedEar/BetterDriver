import BetterDriverCore
import Combine
import CoreBluetooth
import UIKit

/// Keeps the Bluetooth LE link to every device chosen for the next session open from the moment it is
/// chosen until the app closes (Arek 2026-11-05): a vLinker can need a restart after its link drops, a
/// RaceBox loses its fix and an 808S strap switches itself off without a receiver. The held devices
/// are the GPS receiver picked at START (a RaceBox or a listed Bluetooth LE GPS), the listed OBD-II
/// Bluetooth adapters the session would try, the chosen heart-rate strap and the tyre bridge: the same
/// ones RecordingController starts.
///
/// The holder only keeps the link: it connects, never discovers or subscribes. Each recording link
/// keeps its own CBCentralManager and does its own setup on the link the holder keeps up; iOS keeps
/// one radio link per device for the app's centrals together, so a recording link that stops or
/// restarts closes only its own connection and the device stays connected. A connect on iOS never
/// times out, so a held device out of range or switched off is connected again as it comes back.
/// Not on the iPad (no recording there) or in the UI test.
final class HeldConnections: NSObject, ObservableObject, CBCentralManagerDelegate {
    static let shared = HeldConnections()

    /// The held devices connected now, for the START sheet: a connected RaceBox no longer advertises,
    /// so the sheet's scan cannot see it. Main queue.
    @Published private(set) var connected: [UUID: String] = [:]

    private let queue = DispatchQueue(label: "held.links", qos: .userInitiated)
    private var central: CBCentralManager?
    /// Queue only: the devices to hold, by the iPhone's identifier, with their names.
    private var wanted: [UUID: String] = [:]
    /// Queue only: the held devices' peripherals, once iOS knows them.
    private var peripherals: [UUID: CBPeripheral] = [:]
    private var following: AnyCancellable?
    /// Queue only: the held devices iOS did not know, logged once each.
    private var unknown: Set<UUID> = []

    /// Whether this device holds links at all: the iPhone records; the iPad and the UI test do not.
    @MainActor
    static var holds: Bool {
        UIDevice.current.userInterfaceIdiom == .phone && !UITestMode.on
    }

    // MARK: The GPS receiver picked at START

    private static let gnssIdKey = "heldGnssId"
    private static let gnssNameKey = "heldGnssName"
    /// The receiver picked is one listed in Settings › Equipment (a RaceBox is found by its scan).
    private static let gnssListedKey = "heldGnssListed"

    /// The Bluetooth LE GPS receiver picked last at START, nil for none (phone GPS, Wi-Fi, the demo)
    /// or for a listed receiver since removed from [setup].
    static func chosenGnss(_ setup: Setup) -> (id: UUID, name: String)? {
        let defaults = UserDefaults.standard
        guard let text = defaults.string(forKey: gnssIdKey), let id = UUID(uuidString: text) else { return nil }
        if defaults.bool(forKey: gnssListedKey),
           !setup.equipment.contains(where: { $0.address.flatMap(UUID.init(uuidString:)) == id }) {
            return nil
        }
        return (id, defaults.string(forKey: gnssNameKey) ?? "GPS receiver")
    }

    /// The Bluetooth LE device [receiver] records from, nil when it has none.
    static func bluetoothDevice(_ receiver: Receiver) -> (id: UUID, name: String)? {
        switch receiver {
        case .raceBox(let device):
            return (device.id, device.name)
        case .listed(let item) where item.modelName == ModelName.lnsGnss || item.modelName == ModelName.diyGnss:
            return item.address.flatMap(UUID.init(uuidString:)).map { ($0, item.name) }
        default:
            return nil
        }
    }

    /// [receiver] was picked at START: its link is held from now on, and the one picked before is let go.
    @MainActor
    func choose(_ receiver: Receiver?) {
        let device = receiver.flatMap(Self.bluetoothDevice)
        if let device {
            UserDefaults.standard.set(device.id.uuidString, forKey: Self.gnssIdKey)
            UserDefaults.standard.set(device.name, forKey: Self.gnssNameKey)
            var listed = false
            if case .listed = receiver { listed = true }
            UserDefaults.standard.set(listed, forKey: Self.gnssListedKey)
        } else if receiver != nil {
            UserDefaults.standard.removeObject(forKey: Self.gnssIdKey)
            UserDefaults.standard.removeObject(forKey: Self.gnssNameKey)
        }
        refresh(SettingsModel.shared.setup)
    }

    // MARK: Which devices

    /// The devices to hold for [setup] and the receiver picked at START: the ones a session would start.
    @MainActor
    static func devices(_ setup: Setup, gnss: (id: UUID, name: String)?) -> [UUID: String] {
        var all: [UUID: String] = [:]
        if let gnss { all[gnss.id] = gnss.name }
        for adapter in ObdLink.adapters(setup).bluetooth { all[adapter.id] = adapter.name }
        let active = setup.active()
        if let strap = RecordingController.strap(active) { all[strap.id] = strap.name }
        if let bridge = RecordingController.bridge(active) { all[bridge.id] = bridge.name }
        return all
    }

    /// From launch on: the held devices follow every change of the setup (a device added, chosen or removed).
    @MainActor
    func follow(_ settings: SettingsModel) {
        guard Self.holds, following == nil else { return }
        // SettingsModel changes its setup on the main queue only.
        following = settings.$setup.sink { [weak self] setup in
            MainActor.assumeIsolated { self?.refresh(setup) }
        }
    }

    @MainActor
    private func refresh(_ setup: Setup) {
        guard Self.holds else { return }
        let devices = Self.devices(setup, gnss: Self.chosenGnss(setup))
        queue.async { self.hold(devices) }
    }

    // MARK: Holding, on the queue

    private func hold(_ devices: [UUID: String]) {
        guard devices != wanted else {
            // The same devices: one iOS did not know before may be known now (a recording met it).
            connectAll()
            publish()
            return
        }
        for (id, name) in wanted where devices[id] == nil {
            AppLog.i("HeldLinks", "let go \(name)")
            if let p = peripherals.removeValue(forKey: id) { central?.cancelPeripheralConnection(p) }
        }
        wanted = devices
        if central == nil {
            guard !devices.isEmpty else { return }
            // Its state update connects every held device.
            central = CBCentralManager(delegate: self, queue: queue)
            return
        }
        connectAll()
        publish()
    }

    /// Asks iOS to connect every held device not connected yet; a device it does not know yet is
    /// asked for again at the next change or once a recording link has met it.
    private func connectAll() {
        guard let central, central.state == .poweredOn else { return }
        for (id, name) in wanted {
            if let p = peripherals[id] {
                if p.state == .disconnected { central.connect(p) }
                continue
            }
            guard let p = central.retrievePeripherals(withIdentifiers: [id]).first else {
                if unknown.insert(id).inserted { AppLog.i("HeldLinks", "\(name) not known to this phone yet") }
                continue
            }
            peripherals[id] = p
            AppLog.i("HeldLinks", "holding \(name)")
            central.connect(p)
        }
    }

    private func publish() {
        var now: [UUID: String] = [:]
        for (id, p) in peripherals where p.state == .connected { now[id] = wanted[id] }
        DispatchQueue.main.async { if self.connected != now { self.connected = now } }
    }

    // MARK: CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        AppLog.i("HeldLinks", "Bluetooth state \(central.state.rawValue) (5 = on, 3 = not allowed, 4 = off)")
        if central.state == .poweredOn {
            connectAll()
        } else {
            // iOS has dropped every connection; they are asked for afresh once Bluetooth is on again.
            peripherals = [:]
        }
        publish()
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard let name = wanted[peripheral.identifier] else { return }
        AppLog.i("HeldLinks", "\(name) connected")
        publish()
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        guard let name = wanted[peripheral.identifier] else { return }
        AppLog.i("HeldLinks", "\(name) connect failed: \(error?.localizedDescription ?? "no reason"); again in 2 s")
        queue.asyncAfter(deadline: .now() + 2) { self.connectAll() }
        publish()
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        guard let name = wanted[peripheral.identifier] else { return }
        // Out of range or switched off: the connect waits for it to come back.
        AppLog.i("HeldLinks", "\(name) dropped: \(error?.localizedDescription ?? "no reason"); waiting for it")
        if central.state == .poweredOn { central.connect(peripheral) }
        publish()
    }
}
