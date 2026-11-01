import CoreBluetooth
import BetterDriverCore

private let heartRateService = CBUUID(string: HeartRateIds.shared.SERVICE)
private let heartRateMeasurement = CBUUID(string: HeartRateIds.shared.MEASUREMENT)

/// A Bluetooth heart-rate strap in a recording on iOS (FBBD-333), the Android app's HeartRateSource:
/// connects to the strap, turns on Heart Rate Measurement notifications and hands each one to the
/// shared HeartRateSession, which records the heart rate, RR intervals and energy and logs connects
/// and drops exactly as on Android. A strap with no plausible heart rate in the first 45 s of the
/// session is off until it ends (FBBD-285). Only the strap listed in Settings › Equipment records, by
/// the shared rule Android uses (FBBD-349); no other strap nearby is used. The link's life cycle is the shared
/// NotifyLinkCore, the one Android's GattNotifyLink runs (FBBD-358): the same retries, setup timeouts,
/// quiet wait after 6 setups without data, 5 s silence restart and "Link" log lines. This class only
/// turns CoreBluetooth's callbacks into the core's steps. All CoreBluetooth work runs on one serial queue.
final class HeartRateStrapLink: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate, NotifyLinkIo {
    private let queue = DispatchQueue(label: "hr.link", qos: .userInitiated)
    private var central: CBCentralManager!
    private let sink: SampleSink
    private let startedAt = Date()
    private var session: HeartRateSession?
    private var core: NotifyLinkCore?
    private var deviceId: UUID?
    private var peripheral: CBPeripheral?
    private var watch: DispatchSourceTimer?
    private var running = false
    /// A connection the core asked for is open; callbacks of one it closed are left out.
    private var connectionOpen = false
    /// Notifications since the last rate line, logged once a minute as Android's link log (FBBD-352).
    private var notifications = 0
    private var rateSince = Date()

    /// The strap listed in Settings › Equipment (FBBD-338, FBBD-349), recorded as Android records it.
    private let listed: (id: UUID, name: String)

    /// [sink] is the recording; [listed] the strap in Settings.
    init(sink: SampleSink, listed: (id: UUID, name: String)) {
        self.sink = sink
        self.listed = listed
        super.init()
    }

    func start() {
        queue.async {
            guard !self.running else { return }
            self.running = true
            let strap = self.listed
            AppLog.i("HeartRate", "strap \(strap.name) \(strap.id.uuidString) from Settings")
            self.begin(id: strap.id, name: strap.name)
            self.central = CBCentralManager(delegate: self, queue: self.queue)
            // Once a second, as Android's watchdog: the core's rate, silence and setup checks, and the
            // 45 s start window (a strap that never answers sends no state changes on iOS).
            let timer = DispatchSource.makeTimerSource(queue: self.queue)
            timer.schedule(deadline: .now() + 1, repeating: 1)
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
        core?.stop()
        // The core holds this link as its IO: dropping it here lets a finished recording go.
        core = nil
        if let p = peripheral { central?.cancelPeripheralConnection(p) }
        peripheral = nil
    }

    /// Starts recording from the strap [id], as the Android source does when the session starts.
    private func begin(id: UUID, name: String) {
        deviceId = id
        let s = HeartRateSession(sink: sink, address: id.uuidString, deviceName: name) { [weak self] in
            // Off for the session: called from the session, inside the core's state change; the link
            // ends after it returns.
            AppLog.i("HeartRate", "off for this session: no plausible heart rate in the first 45 s")
            self?.queue.async { self?.end() }
        }
        session = s
        // The start window counts from the session start, not from when the strap was found.
        let sinceStart = Int64(Date().timeIntervalSince(startedAt) * 1_000_000)
        s.start(windowStartMicros: sink.nowMicros() - sinceStart)
        core = NotifyLinkCore(
            address: id.uuidString,
            io: self,
            now: { KotlinLong(value: Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)) },
            onState: { state in s.linkChanged(s: state) },
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

    private var searching: Bool {
        Date().timeIntervalSince(startedAt) * 1_000_000 <= Double(HeartRateSessionKt.StartWindowMicros)
    }

    private func check() {
        guard running else { return }
        core?.tick()
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

    /// Bluetooth is on: the core starts with a strap known to this phone, else the strap is looked for.
    private func find() {
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
        core?.start()
    }

    // MARK: NotifyLinkIo, called by the core on this queue

    func open(plan: ConnectPlan) -> String? {
        guard running else { return "stopped" }
        guard central.state == .poweredOn else { return "Bluetooth off" }
        guard let p = peripheral else { return "strap not known to this phone" }
        p.delegate = self
        // iOS has no MTU request or background connection of its own: a pending connect waits for the
        // strap, and the core gives it up after the plan's setup time.
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
        AppLog.i("HeartRate", "Bluetooth state \(central.state.rawValue) (5 = on, 3 = not allowed, 4 = off)")
        if central.state == .poweredOn {
            find()
        } else if connectionOpen {
            // iOS drops its connections without a didDisconnect when Bluetooth goes off: the core
            // retries until Bluetooth is back on.
            core?.failed(reason: central.state == .unauthorized ? "Bluetooth not allowed" : "Bluetooth off")
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        guard running else { return }
        // Only the listed strap; another one nearby is never used (FBBD-349).
        guard peripheral.identifier == deviceId else { return }
        central.stopScan()
        self.peripheral = peripheral
        core?.start()
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard connectionOpen else { return }
        core?.connected()
        core?.reached(next: .services)
        peripheral.discoverServices([heartRateService])
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
        let status = error?.localizedDescription ?? "no error"
        core.log(text: "services found, \(status), \(core.elapsed()) ms after connecting began")
        guard let service = peripheral.services?.first(where: { $0.uuid == heartRateService }) else {
            core.failed(reason: "service \(HeartRateIds.shared.SERVICE) not found")
            return
        }
        peripheral.discoverCharacteristics([heartRateMeasurement], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard connectionOpen, let core else { return }
        guard let ch = service.characteristics?.first(where: { $0.uuid == heartRateMeasurement }) else {
            core.failed(reason: "characteristic \(HeartRateIds.shared.MEASUREMENT) not found")
            return
        }
        core.reached(next: .notifications)
        peripheral.setNotifyValue(true, for: ch)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard connectionOpen, let core, characteristic.uuid == heartRateMeasurement else { return }
        // Notifications refused: no data would ever come on this connection, so drop it and try again.
        if error != nil || !characteristic.isNotifying {
            core.failed(reason: "enable notifications: \(error?.localizedDescription ?? "not notifying")")
        } else {
            core.log(text: "notifications on, \(core.elapsed()) ms after connecting began")
            core.reached(next: .firstValue)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard connectionOpen, characteristic.uuid == heartRateMeasurement else { return }
        guard let value = characteristic.value, let session else { return }
        notifications += 1
        core?.data()
        session.feed(data: value)
    }
}
