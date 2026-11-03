import CoreBluetooth
import BetterDriverCore

/// A Bluetooth LE GPS receiver other than the RaceBox in a recording on iOS (FBBD-374), the Android
/// app's LnsSource and DiyBleSource: a receiver with the standard Location and Navigation Service
/// (Location and Speed notifications) or a RaceChrono DIY device (GPS main notifications, GPS time
/// notified or read when the decoder asks). Every value goes to the shared GnssReceiverSession, which
/// records it and logs connects and drops exactly as on Android. The link's life cycle is the shared
/// NotifyLinkCore with Android's numbers: 15 s to set up, 5 s of silence before a restart, retries 1 s,
/// 2 s, 4 s, then every 5 s. All CoreBluetooth work runs on one serial queue.
final class GnssBleLink: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate, NotifyLinkIo {
    enum Kind {
        /// Location and Navigation Service (FBBD-114).
        case lns
        /// RaceChrono DIY BLE (FBBD-118).
        case diy

        var service: CBUUID { CBUUID(string: self == .lns ? LnsIds.shared.SERVICE : DiyIds.shared.SERVICE) }
        /// The characteristic every fix comes in.
        var main: CBUUID {
            CBUUID(string: self == .lns ? LnsIds.shared.LOCATION_AND_SPEED : DiyIds.shared.GPS_MAIN)
        }
        /// RaceChrono DIY's GPS time, notified when the device can, else read.
        var time: CBUUID? { self == .diy ? CBUUID(string: DiyIds.shared.GPS_TIME) : nil }
        var tag: String { self == .lns ? "LnsGps" : "DiyGps" }
    }

    private let queue = DispatchQueue(label: "gnss.ble.link", qos: .userInitiated)
    private var central: CBCentralManager!
    private let sink: SampleSink
    private let kind: Kind
    private var session: GnssReceiverSession?
    private var core: NotifyLinkCore?
    private var peripheral: CBPeripheral?
    private var timeCharacteristic: CBCharacteristic?
    private var watch: DispatchSourceTimer?
    private var running = false
    /// A connection the core asked for is open; callbacks of one it closed are left out.
    private var connectionOpen = false
    /// Characteristics whose notifications are still to be confirmed.
    private var awaiting = Set<CBUUID>()
    private let onState: (LinkState) -> Void

    /// The receiver listed in Settings › Equipment.
    private let listed: (id: UUID, name: String)

    /// [sink] is the recording; [listed] the receiver in Settings; [onState] gets each link state on the main queue.
    init(kind: Kind, sink: SampleSink, listed: (id: UUID, name: String), onState: @escaping (LinkState) -> Void) {
        self.kind = kind
        self.sink = sink
        self.listed = listed
        self.onState = onState
        super.init()
    }

    func start() {
        queue.async {
            guard !self.running else { return }
            self.running = true
            AppLog.i(self.kind.tag, "receiver \(self.listed.name) \(self.listed.id.uuidString) from Settings")
            self.begin()
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
        queue.async { self.end() }
    }

    private func end() {
        running = false
        watch?.cancel()
        watch = nil
        if central?.isScanning == true { central?.stopScan() }
        core?.stop()
        // The core holds this link as its IO: dropping it lets a finished recording go.
        core = nil
        if let p = peripheral { central?.cancelPeripheralConnection(p) }
        peripheral = nil
    }

    private func begin() {
        let address = listed.id.uuidString
        let s: GnssReceiverSession
        switch kind {
        case .lns:
            s = GnssReceiverSession.companion.lns(sink: sink, address: address, deviceName: listed.name)
        case .diy:
            // The decoder asks for GPS time from within a feed, on this queue.
            s = GnssReceiverSession.companion.diy(sink: sink, address: address, deviceName: listed.name) { [weak self] in
                guard let self, self.connectionOpen, let p = self.peripheral, let ch = self.timeCharacteristic else { return }
                p.readValue(for: ch)
            }
        }
        session = s
        s.start()
        let report = onState
        core = NotifyLinkCore(
            address: address,
            io: self,
            now: { KotlinLong(value: Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)) },
            onState: { state in
                s.linkChanged(s: state)
                DispatchQueue.main.async { report(state) }
            },
            silenceTimeoutMs: 5_000,
            setupTimeoutMs: NotifyLinkCore.companion.SetupTimeoutMs,
            backgroundSetupTimeoutMs: NotifyLinkCore.companion.BackgroundSetupTimeoutMs,
            policy: ReconnectPolicy(
                firstDelayMs: NotifyLinkCore.companion.FirstRetryMs,
                maxDelayMs: NotifyLinkCore.companion.MaxRetryMs
            ),
            quietAfter: NotifyLinkCoreKt.QuietAfterSetups
        )
    }

    /// Bluetooth is on: the core starts with the receiver known to this phone, else it is looked for.
    private func find() {
        guard running, central.state == .poweredOn else { return }
        guard let p = peripheral ?? central.retrievePeripherals(withIdentifiers: [listed.id]).first else {
            central.scanForPeripherals(withServices: [kind.service])
            return
        }
        peripheral = p
        core?.start()
    }

    // MARK: NotifyLinkIo, called by the core on this queue

    func open(plan: ConnectPlan) -> String? {
        guard running else { return "stopped" }
        guard central.state == .poweredOn else { return "Bluetooth off" }
        guard let p = peripheral else { return "receiver not known to this phone" }
        p.delegate = self
        timeCharacteristic = nil
        central.connect(p)
        connectionOpen = true
        return nil
    }

    func close(setupFailed: Bool) {
        connectionOpen = false
        timeCharacteristic = nil
        if let p = peripheral { central.cancelPeripheralConnection(p) }
    }

    func later(delayMs: Int64, action: @escaping () -> Void) {
        queue.asyncAfter(deadline: .now() + .milliseconds(Int(delayMs)), execute: action)
    }

    func detail() -> String? { nil }

    // MARK: CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        AppLog.i(kind.tag, "Bluetooth state \(central.state.rawValue) (5 = on, 3 = not allowed, 4 = off)")
        if central.state == .poweredOn {
            find()
        } else if connectionOpen {
            core?.failed(reason: central.state == .unauthorized ? "Bluetooth not allowed" : "Bluetooth off")
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        // Only the listed receiver.
        guard running, peripheral.identifier == listed.id else { return }
        central.stopScan()
        self.peripheral = peripheral
        core?.start()
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard connectionOpen else { return }
        core?.connected()
        core?.reached(next: .services)
        peripheral.discoverServices([kind.service])
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
        core.log(text: "services found, \(error?.localizedDescription ?? "no error"), \(core.elapsed()) ms after connecting began")
        guard let service = peripheral.services?.first(where: { $0.uuid == kind.service }) else {
            core.failed(reason: "service \(kind.service.uuidString) not found")
            return
        }
        peripheral.discoverCharacteristics([kind.main] + (kind.time.map { [$0] } ?? []), for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard connectionOpen, let core else { return }
        guard let main = service.characteristics?.first(where: { $0.uuid == kind.main }) else {
            core.failed(reason: "characteristic \(kind.main.uuidString) not found")
            return
        }
        core.reached(next: .notifications)
        awaiting = [kind.main]
        peripheral.setNotifyValue(true, for: main)
        // GPS time is optional; notified when it can be, else read when the decoder asks (as Android).
        if let time = kind.time, let ch = service.characteristics?.first(where: { $0.uuid == time }) {
            timeCharacteristic = ch
            if ch.properties.contains(.notify) {
                awaiting.insert(time)
                peripheral.setNotifyValue(true, for: ch)
            }
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard connectionOpen, let core, awaiting.contains(characteristic.uuid) else { return }
        if error != nil || !characteristic.isNotifying {
            // Only the fixes are required; a GPS time that will not notify is read instead.
            if characteristic.uuid == kind.main {
                core.failed(reason: "enable notifications: \(error?.localizedDescription ?? "not notifying")")
                return
            }
        }
        awaiting.remove(characteristic.uuid)
        if awaiting.isEmpty {
            core.log(text: "notifications on, \(core.elapsed()) ms after connecting began")
            core.reached(next: .firstValue)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard connectionOpen, let value = characteristic.value, let session else { return }
        if characteristic.uuid == kind.main {
            core?.data()
            session.feed(data: value)
        } else if characteristic.uuid == kind.time {
            session.feedTime(data: value)
        }
    }
}
