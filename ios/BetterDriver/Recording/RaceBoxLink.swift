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
private let uartRx = CBUUID(string: RaceBoxIds.shared.UART_RX)
private let deviceInfoService = CBUUID(string: RaceBoxDeviceInfoIds.shared.SERVICE)
private let deviceInfoIds = RaceBoxDeviceInfoIds.shared.ALL.map { CBUUID(string: $0) }

/// What the RaceBox diagnostics screen (FBBD-371) takes from the link on top of a recording's, as the
/// Android link's LinkProbe: the notification bytes, the link's state, signal and packet size, the
/// Device Information strings, and the read-only queries to send once those are read. Every call
/// comes on the link's queue.
protocol RaceBoxLinkProbe: AnyObject {
    func data(_ value: Data)
    func state(_ state: LinkState)
    func link(rssi: Int?, mtu: Int?)
    func deviceInfo(id: String, value: String)
    /// Read-only queries only (FBBD-89): never a command that changes the RaceBox.
    func queries() -> [Data]
}

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
///
/// With a [RaceBoxLinkProbe] in place of a recording (the diagnostics screen, FBBD-371) the link also
/// reads the Device Information strings, reads the signal once a second and sends the probe's
/// read-only queries; without one it does exactly what a recording needs and nothing more.
final class RaceBoxLink: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate, NotifyLinkIo {
    private let queue = DispatchQueue(label: "racebox.link", qos: .userInitiated)
    private var central: CBCentralManager!
    private let deviceId: UUID
    private let session: RaceBoxSession?
    private let probe: RaceBoxLinkProbe?
    private let onState: (RaceBoxLinkState) -> Void
    private var core: NotifyLinkCore?
    private var peripheral: CBPeripheral?
    private var watch: DispatchSourceTimer?
    private var running = false
    /// A connection the core asked for is open; callbacks of one it closed are left out.
    private var connectionOpen = false
    // The probe's steps on the open connection: the UART's write side, notifications on, the
    // Device Information strings still to read (nil until known), the queries sent.
    private var rx: CBCharacteristic?
    private var notifying = false
    private var infoLeft: Int?
    private var queriesSent = false

    /// [sink] is the recording; [calibration] the one saved for this RaceBox (FBBD-89), applied to
    /// its motion sensors; [onState] runs on the main queue.
    init(
        deviceId: UUID,
        deviceName: String,
        sink: SampleSink,
        calibration: ImuCalibration? = nil,
        onState: @escaping (RaceBoxLinkState) -> Void
    ) {
        self.deviceId = deviceId
        self.onState = onState
        probe = nil
        session = RaceBoxSession(
            sink: sink,
            isMicro: deviceName.hasPrefix(RaceBoxIds.shared.MICRO_PREFIX),
            calibration: calibration,
            log: { line in AppLog.i("RaceBox", line) },
            // iOS chooses the connection interval itself; there is no faster link to ask for.
            onSlowLink: { _ in }
        )
        super.init()
    }

    /// The diagnostics screen's link (FBBD-371): no recording, everything to [probe].
    init(deviceId: UUID, probe: RaceBoxLinkProbe, onState: @escaping (RaceBoxLinkState) -> Void) {
        self.deviceId = deviceId
        self.onState = onState
        self.probe = probe
        session = nil
        super.init()
    }

    func start() {
        queue.async {
            guard !self.running else { return }
            self.running = true
            self.session?.start()
            let address = self.deviceId.uuidString
            let session = self.session
            let probe = self.probe
            let onState = self.onState
            self.core = NotifyLinkCore(
                address: address,
                io: self,
                now: { KotlinLong(value: Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)) },
                onState: { state in
                    session?.linkChanged(address: address, s: state)
                    probe?.state(state)
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
            timer.setEventHandler { [weak self] in
                self?.core?.tick()
                // The diagnostics screen's signal line, as Android's probe reads it.
                if self?.probe != nil, let p = self?.peripheral, p.state == .connected { p.readRSSI() }
            }
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
        rx = nil
        notifying = false
        infoLeft = nil
        queriesSent = false
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
        guard let probe else {
            peripheral.discoverServices([uartService])
            return
        }
        // iOS chose the packet size; the ATT payload plus its 3-byte header, as Android's MTU.
        probe.link(rssi: nil, mtu: peripheral.maximumWriteValueLength(for: .withoutResponse) + 3)
        peripheral.discoverServices([uartService, deviceInfoService])
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
        guard probe != nil else {
            peripheral.discoverCharacteristics([uartTx], for: service)
            return
        }
        peripheral.discoverCharacteristics([uartTx, uartRx], for: service)
        if let info = peripheral.services?.first(where: { $0.uuid == deviceInfoService }) {
            peripheral.discoverCharacteristics(deviceInfoIds, for: info)
        } else {
            infoLeft = 0
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard connectionOpen, let core else { return }
        if service.uuid == deviceInfoService {
            // In the order of the list: the firmware comes before the queries that depend on it.
            let found = deviceInfoIds.compactMap { id in service.characteristics?.first(where: { $0.uuid == id }) }
            infoLeft = found.count
            found.forEach { peripheral.readValue(for: $0) }
            sendQueries(peripheral)
            return
        }
        if probe != nil { rx = service.characteristics?.first(where: { $0.uuid == uartRx }) }
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
            notifying = true
            sendQueries(peripheral)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard connectionOpen else { return }
        if characteristic.uuid != uartTx {
            deviceInfoRead(peripheral, characteristic, error: error)
            return
        }
        guard let value = characteristic.value else { return }
        core?.data()
        if let session {
            session.feed(data: value)
        } else {
            probe?.data(value)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didReadRSSI RSSI: NSNumber, error: Error?) {
        guard connectionOpen, error == nil else { return }
        probe?.link(rssi: RSSI.intValue, mtu: nil)
    }

    /// One Device Information string read, or failed; once all are, the queries may go.
    private func deviceInfoRead(_ peripheral: CBPeripheral, _ characteristic: CBCharacteristic, error: Error?) {
        guard let probe, deviceInfoIds.contains(characteristic.uuid) else { return }
        if error == nil, let value = characteristic.value,
           let text = String(data: value, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines.union(.controlCharacters)),
           !text.isEmpty {
            probe.deviceInfo(id: characteristic.uuid.uuidString, value: text)
        }
        if let left = infoLeft { infoLeft = max(0, left - 1) }
        sendQueries(peripheral)
    }

    /// The probe's read-only queries, once per connection: after notifications are on (the replies
    /// come as notifications) and the Device Information strings are read (they decide which).
    private func sendQueries(_ peripheral: CBPeripheral) {
        guard let probe, notifying, infoLeft == 0, !queriesSent, let rx else { return }
        queriesSent = true
        let type: CBCharacteristicWriteType = rx.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse
        for query in probe.queries() { peripheral.writeValue(query, for: rx, type: type) }
    }
}
