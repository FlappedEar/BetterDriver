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
/// the recording exactly as on Android. The link's life cycle is the shared NotifyLinkCore with the
/// Android RaceBox link's numbers (FBBD-358): retries 0.5–3 s, the setup ladder with 10 s and 30 s
/// setup times, a restart after 3 s without data, no quiet wait, and the same "Link" log lines. iOS
/// chooses the MTU and the connection interval itself, so the Android link's MTU request and fast
/// link have nothing to do here. All CoreBluetooth work runs on one serial queue.
final class RaceBoxLink: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate, NotifyLinkIo {
    private let queue = DispatchQueue(label: "racebox.link", qos: .userInitiated)
    private var central: CBCentralManager!
    private let deviceId: UUID
    private let session: RaceBoxSession
    private let onState: (RaceBoxLinkState) -> Void
    private var core: NotifyLinkCore?
    private var peripheral: CBPeripheral?
    private var watch: DispatchSourceTimer?
    private var running = false
    /// A connection the core asked for is open; callbacks of one it closed are left out.
    private var connectionOpen = false

    /// [sink] is the recording; [onState] runs on the main queue.
    init(deviceId: UUID, deviceName: String, sink: SampleSink, onState: @escaping (RaceBoxLinkState) -> Void) {
        self.deviceId = deviceId
        self.onState = onState
        session = RaceBoxSession(
            sink: sink,
            isMicro: deviceName.hasPrefix(RaceBoxIds.shared.MICRO_PREFIX),
            calibration: nil,
            log: { line in AppLog.i("RaceBox", line) },
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
            let address = self.deviceId.uuidString
            let session = self.session
            let onState = self.onState
            self.core = NotifyLinkCore(
                address: address,
                io: self,
                now: { KotlinLong(value: Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)) },
                onState: { state in
                    session.linkChanged(address: address, s: state)
                    let shown = RaceBoxLink.shown(state)
                    DispatchQueue.main.async { onState(shown) }
                },
                silenceTimeoutMs: 3_000,
                setupTimeoutMs: 10_000,
                backgroundSetupTimeoutMs: 30_000,
                policy: ReconnectPolicy(firstDelayMs: 500, maxDelayMs: 3_000),
                quietAfter: Int32.max
            )
            self.central = CBCentralManager(delegate: self, queue: self.queue)
            // Once a second, as Android's watchdog: the core's rate, silence and setup checks.
            let timer = DispatchSource.makeTimerSource(queue: self.queue)
            timer.schedule(deadline: .now() + 1, repeating: 1)
            timer.setEventHandler { [weak self] in self?.core?.tick() }
            timer.resume()
            self.watch = timer
        }
    }

    func stop() {
        queue.async {
            self.running = false
            self.watch?.cancel()
            self.watch = nil
            // A lookup scan for a RaceBox this phone did not know yet may still be running.
            if self.central?.isScanning == true { self.central?.stopScan() }
            self.core?.stop()
            // The core holds this link as its IO: dropping it lets a finished recording go.
            self.core = nil
            self.peripheral = nil
            self.report(.off)
        }
    }

    /// The recording screen's state for the core's [state].
    private static func shown(_ state: LinkState) -> RaceBoxLinkState {
        if let r = state as? LinkStateReconnecting { return .reconnecting(reason: r.reason) }
        if state is LinkStateStreaming { return .streaming }
        if state is LinkStateConnecting { return .connecting }
        return .off
    }

    private func report(_ state: RaceBoxLinkState) {
        DispatchQueue.main.async { self.onState(state) }
    }

    /// Bluetooth is on: the core starts with a RaceBox known to this phone, else it is looked for.
    private func find() {
        guard running, central.state == .poweredOn else { return }
        guard let p = peripheral ?? central.retrievePeripherals(withIdentifiers: [deviceId]).first else {
            // Not known to this phone yet: look for it.
            central.scanForPeripherals(withServices: nil)
            report(.connecting)
            return
        }
        peripheral = p
        core?.start()
    }

    // MARK: NotifyLinkIo, called by the core on this queue

    func open(plan: ConnectPlan) -> String? {
        guard running else { return "stopped" }
        guard central.state == .poweredOn else { return "Bluetooth off" }
        guard let p = peripheral else { return "RaceBox not known to this phone" }
        p.delegate = self
        // A pending connect waits for the RaceBox; the core gives it up after the plan's setup time.
        central.connect(p)
        connectionOpen = true
        return nil
    }

    func close(setupFailed: Bool) {
        connectionOpen = false
        if let p = peripheral { central.cancelPeripheralConnection(p) }
    }

    func later(delayMs: Int64, action: @escaping () -> Void) {
        queue.asyncAfter(deadline: .now() + .milliseconds(Int(delayMs)), execute: action)
    }

    func detail() -> String? { nil }

    // MARK: CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn: find()
        case .unauthorized, .poweredOff:
            let reason = central.state == .unauthorized ? "Bluetooth not allowed" : "Bluetooth off"
            // iOS drops its connections without a didDisconnect when Bluetooth goes off.
            if connectionOpen { core?.failed(reason: reason) }
            report(.unavailable(reason: reason))
        default: break
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        guard running, peripheral.identifier == deviceId else { return }
        central.stopScan()
        self.peripheral = peripheral
        core?.start()
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard connectionOpen else { return }
        core?.connected()
        core?.reached(next: .services)
        peripheral.discoverServices([uartService])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        guard connectionOpen else { return }
        core?.failed(reason: error?.localizedDescription ?? "connect failed")
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        guard connectionOpen else { return }
        core?.failed(reason: error?.localizedDescription ?? "disconnected")
    }

    // MARK: CBPeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard connectionOpen, let core else { return }
        core.log(text: "services found, \(core.elapsed()) ms after connecting began")
        guard let service = peripheral.services?.first(where: { $0.uuid == uartService }) else {
            core.failed(reason: "service \(RaceBoxIds.shared.UART_SERVICE) not found")
            return
        }
        peripheral.discoverCharacteristics([uartTx], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard connectionOpen, let core else { return }
        guard let tx = service.characteristics?.first(where: { $0.uuid == uartTx }) else {
            core.failed(reason: "characteristic \(RaceBoxIds.shared.UART_TX) not found")
            return
        }
        core.reached(next: .notifications)
        peripheral.setNotifyValue(true, for: tx)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard connectionOpen, let core, characteristic.uuid == uartTx else { return }
        // Notifications refused: no data would ever come on this connection, so drop it and try again.
        if error != nil || !characteristic.isNotifying {
            core.failed(reason: "enable notifications: \(error?.localizedDescription ?? "not notifying")")
        } else {
            core.log(text: "notifications on, \(core.elapsed()) ms after connecting began")
            core.reached(next: .firstValue)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard connectionOpen, characteristic.uuid == uartTx, let value = characteristic.value else { return }
        core?.data()
        session.feed(data: value)
    }
}
