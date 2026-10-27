import CoreBluetooth
import BetterDriverCore

/// Where the RaceBox link is, for the recording screen. The Android app's LinkState.
enum RaceBoxLinkState: Equatable {
    case off
    /// Bluetooth is off or not allowed; [reason] says which.
    case unavailable(reason: String)
    case connecting
    case streaming
    /// Lost; the link asks again on its own.
    case reconnecting(reason: String)
}

/// A RaceBox seen in a scan. [id] is CoreBluetooth's identifier, which iOS keeps per phone in place
/// of the Android app's Bluetooth address.
struct FoundRaceBoxDevice: Identifiable, Equatable {
    let id: UUID
    let name: String
    let rssi: Int

    var isMicro: Bool { name.hasPrefix(RaceBoxIds.shared.MICRO_PREFIX) }
}

private let uartService = CBUUID(string: RaceBoxIds.shared.UART_SERVICE)
private let uartTx = CBUUID(string: RaceBoxIds.shared.UART_TX)

/// Finds RaceBoxes nearby by their advertised name, as the Android app's scanner does (FBBD-332).
final class RaceBoxScanner: NSObject, CBCentralManagerDelegate {
    private let queue = DispatchQueue(label: "racebox.scan")
    private var central: CBCentralManager!
    private var wanted = false
    private let onFound: (FoundRaceBoxDevice) -> Void

    /// [onFound] runs on the main queue for every advertisement of a RaceBox.
    init(onFound: @escaping (FoundRaceBoxDevice) -> Void) {
        self.onFound = onFound
        super.init()
        central = CBCentralManager(delegate: self, queue: queue)
    }

    func start() { queue.async { self.wanted = true; self.scanIfReady() } }

    func stop() {
        queue.async {
            self.wanted = false
            if self.central.state == .poweredOn { self.central.stopScan() }
        }
    }

    private func scanIfReady() {
        guard wanted, central.state == .poweredOn else { return }
        // By name: whether every RaceBox model advertises the UART service is not documented.
        central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) { scanIfReady() }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? peripheral.name
        guard let name, FoundRaceBox.companion.isRaceBox(name: name) else { return }
        let found = FoundRaceBoxDevice(id: peripheral.identifier, name: name, rssi: RSSI.intValue)
        DispatchQueue.main.async { self.onFound(found) }
    }
}

/// The RaceBox in a recording on iOS (FBBD-332): connects to the chosen RaceBox, turns on its UART
/// notifications and hands every notification to the shared RaceBoxSession, which decodes it into
/// the recording exactly as on Android. Keeps reconnecting until stopped, with the Android app's
/// delays (ReconnectPolicy). All CoreBluetooth work runs on one serial queue.
final class RaceBoxLink: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    private let queue = DispatchQueue(label: "racebox.link", qos: .userInitiated)
    private var central: CBCentralManager!
    private let deviceId: UUID
    private let session: RaceBoxSession
    private let onState: (RaceBoxLinkState) -> Void
    private let policy = ReconnectPolicy(firstDelayMs: 500, maxDelayMs: 3_000)
    private var peripheral: CBPeripheral?
    private var running = false
    private var flowing = false

    /// [sink] is the recording; [onState] runs on the main queue.
    init(deviceId: UUID, deviceName: String, sink: SampleSink, onState: @escaping (RaceBoxLinkState) -> Void) {
        self.deviceId = deviceId
        self.onState = onState
        session = RaceBoxSession(
            sink: sink,
            isMicro: deviceName.hasPrefix(RaceBoxIds.shared.MICRO_PREFIX),
            calibration: nil,
            log: { line in NSLog("RaceBox %@", line) },
            // iOS chooses the connection interval itself; there is no faster link to ask for.
            onSlowLink: { _ in }
        )
        super.init()
    }

    func start() {
        queue.async {
            guard !self.running else { return }
            self.running = true
            self.session.start()
            self.central = CBCentralManager(delegate: self, queue: self.queue)
        }
    }

    func stop() {
        queue.async {
            self.running = false
            // A lookup scan for a RaceBox this phone did not know yet may still be running.
            if self.central?.isScanning == true { self.central?.stopScan() }
            if let p = self.peripheral { self.central?.cancelPeripheralConnection(p) }
            self.peripheral = nil
            self.report(.off)
        }
    }

    private func report(_ state: RaceBoxLinkState) {
        DispatchQueue.main.async { self.onState(state) }
    }

    private func connect() {
        guard running, central.state == .poweredOn else { return }
        guard let p = peripheral ?? central.retrievePeripherals(withIdentifiers: [deviceId]).first else {
            // Not known to this phone yet: look for it.
            central.scanForPeripherals(withServices: nil)
            report(.connecting)
            return
        }
        peripheral = p
        p.delegate = self
        // A pending connect does not time out: iOS completes it when the RaceBox is in range again.
        central.connect(p)
        report(.connecting)
    }

    private func dropped(_ reason: String) {
        flowing = false
        session.reconnecting(reason: reason)
        guard running else { return }
        report(.reconnecting(reason: reason))
        let delay = Double(policy.nextDelayMs()) / 1_000
        queue.asyncAfter(deadline: .now() + delay) { [weak self] in self?.connect() }
    }

    // MARK: CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn: connect()
        case .unauthorized: report(.unavailable(reason: "Bluetooth not allowed"))
        case .poweredOff: report(.unavailable(reason: "Bluetooth off"))
        default: break
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        guard peripheral.identifier == deviceId else { return }
        central.stopScan()
        self.peripheral = peripheral
        connect()
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([uartService])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        dropped(error?.localizedDescription ?? "connect failed")
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        dropped(error?.localizedDescription ?? "disconnected")
    }

    // MARK: CBPeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == uartService }) else {
            central.cancelPeripheralConnection(peripheral)
            return
        }
        peripheral.discoverCharacteristics([uartTx], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let tx = service.characteristics?.first(where: { $0.uuid == uartTx }) else {
            central.cancelPeripheralConnection(peripheral)
            return
        }
        peripheral.setNotifyValue(true, for: tx)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard characteristic.uuid == uartTx else { return }
        // Notifications refused: no data would ever come on this connection, so drop it and try again.
        if error != nil || !characteristic.isNotifying { central.cancelPeripheralConnection(peripheral) }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == uartTx, let value = characteristic.value else { return }
        if !flowing {
            flowing = true
            policy.reset()
            session.streaming(address: deviceId.uuidString)
            report(.streaming)
        }
        session.feed(data: value)
    }
}
