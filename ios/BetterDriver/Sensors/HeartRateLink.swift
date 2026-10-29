import CoreBluetooth
import BetterDriverCore

private let heartRateService = CBUUID(string: HeartRateIds.shared.SERVICE)
private let heartRateMeasurement = CBUUID(string: HeartRateIds.shared.MEASUREMENT)

/// The strap the app records from, remembered as RaceBox's last receiver is: the Android app records
/// the strap listed in its equipment, the iPhone the first one it found (FBBD-333).
enum RememberedStrap {
    private static let idKey = "heartRateStrapId"
    private static let nameKey = "heartRateStrapName"

    static var id: UUID? {
        UserDefaults.standard.string(forKey: idKey).flatMap(UUID.init(uuidString:))
    }

    static var name: String { UserDefaults.standard.string(forKey: nameKey) ?? "" }

    static func remember(id: UUID, name: String) {
        UserDefaults.standard.set(id.uuidString, forKey: idKey)
        UserDefaults.standard.set(name, forKey: nameKey)
    }
}

/// A Bluetooth heart-rate strap in a recording on iOS (FBBD-333), the Android app's HeartRateSource:
/// connects to the strap, turns on Heart Rate Measurement notifications and hands each one to the
/// shared HeartRateSession, which records the heart rate, RR intervals and energy and logs connects
/// and drops exactly as on Android. A strap with no plausible heart rate in the first 45 s of the
/// session is off until it ends (FBBD-285). With no strap remembered yet, the first one advertising
/// the Heart Rate Service in that time is used and remembered. Keeps reconnecting until stopped, with
/// the RaceBox link's delays. All CoreBluetooth work runs on one serial queue.
final class HeartRateStrapLink: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    private let queue = DispatchQueue(label: "hr.link", qos: .userInitiated)
    private var central: CBCentralManager!
    private let sink: SampleSink
    private let policy = ReconnectPolicy(firstDelayMs: 500, maxDelayMs: 3_000)
    private let startedAt = Date()
    private var session: HeartRateSession?
    private var deviceId: UUID?
    private var peripheral: CBPeripheral?
    private var watch: DispatchSourceTimer?
    private var running = false
    private var flowing = false
    /// Notifications since the last rate line, logged once a minute as Android's link log (FBBD-352).
    private var notifications = 0
    private var rateSince = Date()

    /// The strap listed in Settings › Equipment (FBBD-338), recorded as Android records it; without one
    /// the strap found before, else the first one found.
    private let listed: (id: UUID, name: String)?

    /// [sink] is the recording; [listed] the strap in Settings, if any.
    init(sink: SampleSink, listed: (id: UUID, name: String)? = nil) {
        self.sink = sink
        self.listed = listed
        super.init()
    }

    func start() {
        queue.async {
            guard !self.running else { return }
            self.running = true
            if let strap = self.listed {
                AppLog.i("HeartRate", "strap \(strap.name) \(strap.id.uuidString) from Settings")
                self.begin(id: strap.id, name: strap.name)
            } else if let id = RememberedStrap.id {
                AppLog.i("HeartRate", "strap \(RememberedStrap.name) \(id.uuidString) remembered")
                self.begin(id: id, name: RememberedStrap.name)
            } else {
                AppLog.i("HeartRate", "no strap listed: the first one found in 45 s is used")
            }
            self.central = CBCentralManager(delegate: self, queue: self.queue)
            // A strap that never answers sends no state changes on iOS (a pending connect waits for it),
            // so the 45 s start window is checked on a timer, as Android's reconnect attempts check it.
            let timer = DispatchSource.makeTimerSource(queue: self.queue)
            timer.schedule(deadline: .now() + 5, repeating: 5)
            timer.setEventHandler { [weak self] in self?.check() }
            timer.resume()
            self.watch = timer
        }
    }

    func stop() {
        queue.async { self.end() }
    }

    /// The strap for the track screen's status strip; nil before a strap is found or once it is off.
    var status: HeartRateStatus? {
        queue.sync { session?.status() }
    }

    private func end() {
        running = false
        watch?.cancel()
        watch = nil
        if central?.isScanning == true { central?.stopScan() }
        if let p = peripheral { central?.cancelPeripheralConnection(p) }
        peripheral = nil
    }

    /// Starts recording from the strap [id], as the Android source does when the session starts.
    private func begin(id: UUID, name: String) {
        deviceId = id
        let s = HeartRateSession(sink: sink, address: id.uuidString, deviceName: name) { [weak self] in
            // Off for the session: called on this queue, from the session itself.
            AppLog.i("HeartRate", "off for this session: no plausible heart rate in the first 45 s")
            self?.end()
        }
        session = s
        // The start window counts from the session start, not from when the strap was found.
        let sinceStart = Int64(Date().timeIntervalSince(startedAt) * 1_000_000)
        s.start(windowStartMicros: sink.nowMicros() - sinceStart)
    }

    private var searching: Bool {
        Date().timeIntervalSince(startedAt) * 1_000_000 <= Double(HeartRateSessionKt.StartWindowMicros)
    }

    private func check() {
        guard running else { return }
        if Date().timeIntervalSince(rateSince) >= 60 {
            let bpm = session?.status()?.bpm.map { "\($0.intValue) bpm" } ?? "no heart rate"
            AppLog.i("HeartRate", "rate: \(notifications) notifications in 60 s, last \(bpm)")
            notifications = 0
            rateSince = Date()
        }
        if let session {
            _ = session.offIfNotFound()
        } else if !searching {
            // No strap in range at the start: none records this session.
            AppLog.i("HeartRate", "no strap found in the first 45 s: none records this session")
            end()
        }
    }

    private func connect() {
        guard running, central.state == .poweredOn else { return }
        guard let id = deviceId else {
            if searching { central.scanForPeripherals(withServices: [heartRateService]) }
            return
        }
        guard let p = peripheral ?? central.retrievePeripherals(withIdentifiers: [id]).first else {
            // Not known to this phone any more: look for it.
            central.scanForPeripherals(withServices: [heartRateService])
            return
        }
        peripheral = p
        p.delegate = self
        // A pending connect does not time out: iOS completes it when the strap is in range again.
        AppLog.i("HeartRate", "connecting \(p.identifier.uuidString)")
        central.connect(p)
        session?.connecting()
    }

    private func dropped(_ reason: String) {
        AppLog.i("HeartRate", "dropped: \(reason)")
        flowing = false
        guard running, let session, session.reconnecting(reason: reason) else { return }
        let delay = Double(policy.nextDelayMs()) / 1_000
        queue.asyncAfter(deadline: .now() + delay) { [weak self] in self?.connect() }
    }

    // MARK: CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        AppLog.i("HeartRate", "Bluetooth state \(central.state.rawValue) (5 = on, 3 = not allowed, 4 = off)")
        if central.state == .poweredOn {
            connect()
        } else if flowing {
            // iOS drops its connections without a didDisconnect when Bluetooth goes off: the strap is
            // gone for the session's log too, and is asked for again once Bluetooth is back on.
            dropped(central.state == .unauthorized ? "Bluetooth not allowed" : "Bluetooth off")
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        guard running else { return }
        if let id = deviceId {
            guard peripheral.identifier == id else { return }
        } else {
            let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? peripheral.name ?? ""
            AppLog.i("HeartRate", "found \(name) \(peripheral.identifier.uuidString), remembered")
            RememberedStrap.remember(id: peripheral.identifier, name: name)
            begin(id: peripheral.identifier, name: name)
        }
        central.stopScan()
        self.peripheral = peripheral
        connect()
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        AppLog.i("HeartRate", "connected")
        peripheral.discoverServices([heartRateService])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        dropped(error?.localizedDescription ?? "connect failed")
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        dropped(error?.localizedDescription ?? "disconnected")
    }

    // MARK: CBPeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == heartRateService }) else {
            AppLog.i("HeartRate", "no Heart Rate Service: disconnecting")
            central.cancelPeripheralConnection(peripheral)
            return
        }
        peripheral.discoverCharacteristics([heartRateMeasurement], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let ch = service.characteristics?.first(where: { $0.uuid == heartRateMeasurement }) else {
            AppLog.i("HeartRate", "no Heart Rate Measurement: disconnecting")
            central.cancelPeripheralConnection(peripheral)
            return
        }
        peripheral.setNotifyValue(true, for: ch)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard characteristic.uuid == heartRateMeasurement else { return }
        // Notifications refused: no data would ever come on this connection, so drop it and try again.
        if error != nil || !characteristic.isNotifying {
            AppLog.i("HeartRate", "notifications refused: \(error?.localizedDescription ?? "not notifying")")
            central.cancelPeripheralConnection(peripheral)
        } else {
            AppLog.i("HeartRate", "notifications on")
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == heartRateMeasurement, let value = characteristic.value, let session else { return }
        notifications += 1
        if !flowing {
            flowing = true
            policy.reset()
            AppLog.i("HeartRate", "streaming")
            guard session.streaming() else { return }
        }
        session.feed(data: value)
    }
}
