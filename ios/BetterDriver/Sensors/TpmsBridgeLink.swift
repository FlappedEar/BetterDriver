import CoreBluetooth
import BetterDriverCore

private let tpmsService = CBUUID(string: TpmsIds.shared.SERVICE)
private let tpmsValues = CBUUID(string: TpmsIds.shared.VALUES)
private let tpmsFilter = CBUUID(string: TpmsIds.shared.FILTER)

/// The FlappedEar TPMS bridge (RCTPMS) in a recording on iOS (FBBD-224), the Android app's TpmsSource:
/// connects to the bridge listed in Settings › Equipment, turns on its value notifications, writes the
/// filter that lets them through (the bridge clears it on every connect) and hands each value to the
/// shared TpmsSession, which records the four tyres' pressures and temperatures and logs connects and
/// drops exactly as on Android. The link's life cycle is the shared NotifyLinkCore, with the bridge's
/// numbers Android uses: 2 min for a first value (the bridge sends nothing before it hears a sensor) and
/// 30 s of silence before a restart. A lost bridge never stops the session. All CoreBluetooth work runs
/// on one serial queue.
final class TpmsBridgeLink: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate, NotifyLinkIo {
    private let queue = DispatchQueue(label: "tpms.link", qos: .userInitiated)
    private var central: CBCentralManager!
    private let sink: SampleSink
    private var session: TpmsSession?
    private var core: NotifyLinkCore?
    private var peripheral: CBPeripheral?
    private var watch: DispatchSourceTimer?
    private var running = false
    /// A connection the core asked for is open; callbacks of one it closed are left out.
    private var connectionOpen = false

    /// The bridge listed in Settings › Equipment.
    private let listed: (id: UUID, name: String)

    /// [sink] is the recording; [listed] the bridge in Settings.
    init(sink: SampleSink, listed: (id: UUID, name: String)) {
        self.sink = sink
        self.listed = listed
        super.init()
    }

    func start() {
        queue.async {
            guard !self.running else { return }
            self.running = true
            AppLog.i("Tpms", "bridge \(self.listed.name) \(self.listed.id.uuidString) from Settings")
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
        core = nil
        if let p = peripheral { central?.cancelPeripheralConnection(p) }
        peripheral = nil
    }

    private func begin() {
        let s = TpmsSession(sink: sink, address: listed.id.uuidString, deviceName: listed.name)
        session = s
        s.start()
        core = NotifyLinkCore(
            address: listed.id.uuidString,
            io: self,
            now: { KotlinLong(value: Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)) },
            onState: { state in s.linkChanged(s: state) },
            silenceTimeoutMs: 30_000,
            setupTimeoutMs: 120_000,
            backgroundSetupTimeoutMs: 120_000,
            policy: ReconnectPolicy(
                firstDelayMs: NotifyLinkCore.companion.FirstRetryMs,
                maxDelayMs: NotifyLinkCore.companion.MaxRetryMs
            ),
            quietAfter: NotifyLinkCoreKt.QuietAfterSetups
        )
    }

    /// Bluetooth is on: the core starts with the bridge known to this phone, else the bridge is looked for.
    private func find() {
        guard running, central.state == .poweredOn else { return }
        guard let p = peripheral ?? central.retrievePeripherals(withIdentifiers: [listed.id]).first else {
            central.scanForPeripherals(withServices: [tpmsService])
            return
        }
        peripheral = p
        core?.start()
    }

    // MARK: NotifyLinkIo, called by the core on this queue

    func open(plan: ConnectPlan) -> String? {
        guard running else { return "stopped" }
        guard central.state == .poweredOn else { return "Bluetooth off" }
        guard let p = peripheral else { return "bridge not known to this phone" }
        p.delegate = self
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
        AppLog.i("Tpms", "Bluetooth state \(central.state.rawValue) (5 = on, 3 = not allowed, 4 = off)")
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
        // Only the listed bridge.
        guard running, peripheral.identifier == listed.id else { return }
        central.stopScan()
        self.peripheral = peripheral
        core?.start()
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard connectionOpen else { return }
        core?.connected()
        core?.reached(next: .services)
        peripheral.discoverServices([tpmsService])
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
        guard let service = peripheral.services?.first(where: { $0.uuid == tpmsService }) else {
            core.failed(reason: "service \(TpmsIds.shared.SERVICE) not found")
            return
        }
        peripheral.discoverCharacteristics([tpmsValues, tpmsFilter], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard connectionOpen, let core else { return }
        guard let ch = service.characteristics?.first(where: { $0.uuid == tpmsValues }) else {
            core.failed(reason: "characteristic \(TpmsIds.shared.VALUES) not found")
            return
        }
        guard service.characteristics?.contains(where: { $0.uuid == tpmsFilter }) == true else {
            core.failed(reason: "characteristic \(TpmsIds.shared.FILTER) not found")
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
        guard connectionOpen, let core, characteristic.uuid == tpmsValues else { return }
        if error != nil || !characteristic.isNotifying {
            core.failed(reason: "enable notifications: \(error?.localizedDescription ?? "not notifying")")
            return
        }
        core.log(text: "notifications on, \(core.elapsed()) ms after connecting began")
        // The bridge sends nothing until a filter allows it: every value, once a second.
        guard let filter = characteristic.service?.characteristics?.first(where: { $0.uuid == tpmsFilter }) else {
            core.failed(reason: "characteristic \(TpmsIds.shared.FILTER) not found")
            return
        }
        peripheral.writeValue(TpmsIosKt.tpmsFilterData(), for: filter, type: .withResponse)
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard connectionOpen, let core, characteristic.uuid == tpmsFilter else { return }
        if let error {
            core.failed(reason: "write \(TpmsIds.shared.FILTER): \(error.localizedDescription)")
        } else {
            core.log(text: "\(TpmsIds.shared.FILTER) written, \(core.elapsed()) ms after connecting began")
            core.reached(next: .firstValue)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard connectionOpen, characteristic.uuid == tpmsValues else { return }
        guard let value = characteristic.value, let session else { return }
        core?.data()
        session.feed(data: value)
    }
}
