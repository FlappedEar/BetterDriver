import BetterDriverCore
import CoreBluetooth
import Foundation
import Network

/// How long an adapter gets to connect and turn its notifications on, as on Android (BleObdTarget).
private let setupTimeoutSeconds = 15.0

/// OBD-II in a recording on iOS (FBBD-333): the shared ObdFeed reads the car on a thread of its own,
/// exactly as on Android, over a Bluetooth LE ELM327 (the Vgate vLinker MC+) reached through
/// CoreBluetooth, or over Wi-Fi to a Wi-Fi ELM327 at 192.168.0.10 port 35000 (the phone joined to its
/// network), tried after the Bluetooth ones as on Android. iOS has no Bluetooth Classic serial profile
/// for apps, so only LE Bluetooth adapters are used. A Bluetooth adapter is found by its name
/// (ObdAdapterNames, as Android lists them) and remembered, so the one used last is tried first. All
/// CoreBluetooth work runs on one serial queue.
final class ObdLink: NSObject, CBCentralManagerDelegate, ObdFeedListener {
    /// Every CoreBluetooth call of this link and its ports runs here.
    fileprivate let queue = DispatchQueue(label: "obd.link", qos: .userInitiated)
    private var central: CBCentralManager!
    private var feed: ObdFeed!
    private var running = false
    /// Adapters seen in this session, by identifier, and the port of the one connecting or open.
    private var seen: [UUID: CBPeripheral] = [:]
    /// Named Bluetooth devices nearby that are not OBD adapters, logged once each: when no adapter is
    /// found, the log shows whether the adapter was off, busy with another phone, or named otherwise.
    private var others: Set<String> = []
    private var port: ObdBlePortLink?
    /// The Wi-Fi network as iOS reports it: logged, and a network coming back is tried at once.
    private var wifi: NWPathMonitor?
    private var onWifi: Bool?

    private static let lastIdKey = "lastObdAdapterId"
    private static let lastNameKey = "lastObdAdapterName"

    /// The adapters listed in Settings › Equipment (FBBD-338): only these are tried, as on Android. With
    /// none listed, the one used last and any other found nearby.
    private let listed: [(id: UUID, name: String)]

    /// [sink] is the recording; [listed] the Bluetooth LE adapters in Settings. With [setup], the
    /// session's car's own parameters and switched-on PIDs are read (FBBD-344), and [tiles] are its
    /// track screen. (The setup, not the car: setup's and obd's CarProfile share a name in Swift.)
    /// With [page] it is the car data page's own connection outside a session (FBBD-261, FBBD-344):
    /// every channel the car has, nothing recorded, and Scan car.
    init(
        sink: SampleSink, listed: [(id: UUID, name: String)] = [], setup: Setup? = nil, tiles: [String] = [],
        page: Bool = false
    ) {
        self.listed = listed
        super.init()
        feed = ObdFeed(
            sink: sink, targets: { [weak self] in self?.targets() ?? [] }, listener: self,
            car: setup?.active().car, tiles: tiles, page: page
        )
    }

    func start() {
        queue.async {
            guard !self.running else { return }
            self.running = true
            self.central = CBCentralManager(delegate: self, queue: self.queue)
            self.watchWifi()
            self.feed.start()
        }
    }

    func stop() {
        feed.stop()
        queue.async {
            self.running = false
            if self.central?.isScanning == true { self.central?.stopScan() }
            self.wifi?.cancel()
            self.wifi = nil
            self.port?.cancel()
            self.port = nil
        }
    }

    /// The connection as the track screen shows it (Android: RecorderState.obd).
    func status() -> ObdStatus { feed.status() }

    /// Scan car (UI-303 B), on the page's own connection.
    func scanCar() { feed.scanCar() }
    var scanning: Bool { feed.scanning }
    var scanNote: String? { feed.scanNote }

    /// A scan just finished, once, as it is stored with the car.
    func takeScan() -> CarScanRecord? { feed.takeScan(wallMs: Int64(Date().timeIntervalSince1970 * 1000)) }

    func log(text: String) { AppLog.i("Obd", text) }

    /// The adapters to try now, on the OBD thread: the Bluetooth ones, then the Wi-Fi one.
    private func targets() -> [ObdTarget] {
        queue.sync {
            guard running else { return [] }
            let wifi: ObdTarget = IosWifiObdTarget(host: "192.168.0.10", port: 35000, name: "Wi-Fi adapter")
            return bluetoothTargets() + [wifi]
        }
    }

    /// Queue only. Logs the Wi-Fi network changes (FBBD-271 on Android) and tries at once when one is back.
    private func watchWifi() {
        let monitor = NWPathMonitor(requiredInterfaceType: .wifi)
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            let on = path.status == .satisfied
            guard on != self.onWifi else { return }
            self.onWifi = on
            self.log(text: on ? "Wi-Fi network available" : "Wi-Fi network lost")
            if on { self.feed.retryNow() }
        }
        wifi = monitor
        monitor.start(queue: queue)
    }

    /// Queue only. The Bluetooth adapters: those listed in Settings (FBBD-338); with none listed, the one
    /// used last, then any other seen nearby.
    private func bluetoothTargets() -> [ObdTarget] {
        guard central?.state == .poweredOn else { return [] }
        var list: [CBPeripheral] = []
        if !listed.isEmpty {
            for item in listed {
                if let known = seen[item.id] ?? central.retrievePeripherals(withIdentifiers: [item.id]).first {
                    list.append(known)
                }
            }
        } else {
            if let text = UserDefaults.standard.string(forKey: Self.lastIdKey), let id = UUID(uuidString: text),
               let known = seen[id] ?? central.retrievePeripherals(withIdentifiers: [id]).first {
                list.append(known)
            }
            list += seen.values.filter { p in !list.contains { $0.identifier == p.identifier } }
        }
        // Keep looking while no adapter is open: the remembered one may be out of range and another near.
        scan()
        return list.map { p in
            let name = listed.first { $0.id == p.identifier }?.name
                ?? p.name ?? UserDefaults.standard.string(forKey: Self.lastNameKey) ?? "OBD adapter"
            return IosBleObdTarget(name: name, identifier: p.identifier.uuidString, port: ObdBlePortLink(link: self, peripheral: p))
        }
    }

    private func scan() {
        guard running, central.state == .poweredOn, !central.isScanning else { return }
        central.scanForPeripherals(withServices: nil)
    }

    // MARK: Used by the port, on the queue

    fileprivate func connect(_ port: ObdBlePortLink) {
        guard running, central.state == .poweredOn else {
            port.failed("Bluetooth off")
            return
        }
        if central.isScanning { central.stopScan() }
        self.port = port
        port.peripheral.delegate = port
        central.connect(port.peripheral)
    }

    fileprivate func disconnect(_ port: ObdBlePortLink) {
        if self.port === port { self.port = nil }
        central?.cancelPeripheralConnection(port.peripheral)
        // A failed or ended connection: look for adapters again, the didDisconnect callback no longer will.
        scan()
    }

    fileprivate func ready(_ port: ObdBlePortLink) {
        UserDefaults.standard.set(port.peripheral.identifier.uuidString, forKey: Self.lastIdKey)
        if let name = port.peripheral.name { UserDefaults.standard.set(name, forKey: Self.lastNameKey) }
    }

    // MARK: CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        log(text: "Bluetooth state \(central.state.rawValue) (5 = on, 3 = not allowed, 4 = off)")
        if central.state == .poweredOn {
            scan()
            feed.retryNow()
        } else {
            port?.lost(central.state == .unauthorized ? "Bluetooth not allowed" : "Bluetooth off")
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? peripheral.name
        guard ObdAdapterNames.shared.isCandidate(name: name) else {
            if let name, others.count < 30, others.insert(name).inserted {
                log(text: "nearby, not an OBD adapter: \(name)")
            }
            return
        }
        guard seen[peripheral.identifier] == nil else {
            // Known already: nothing new to try, so the scan can stop until the next round asks again.
            central.stopScan()
            return
        }
        seen[peripheral.identifier] = peripheral
        log(text: "found \(name ?? "?") \(peripheral.identifier.uuidString)")
        central.stopScan()
        feed.retryNow()
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard let port, port.peripheral === peripheral else { return }
        port.connected()
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        guard let port, port.peripheral === peripheral else { return }
        port.lost(error?.localizedDescription ?? "connect failed")
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        guard let port, port.peripheral === peripheral else { return }
        self.port = nil
        port.lost(error?.localizedDescription ?? "disconnected")
        scan()
    }
}

/// One connection to an adapter, the iOS side of the shared BleSerialConnection: open blocks the OBD
/// thread while the queue connects, discovers every service's characteristics, picks the serial
/// pair (BleSerial.choose, as on Android) and turns its notifications on.
final class ObdBlePortLink: NSObject, ObdBlePort, CBPeripheralDelegate {
    let peripheral: CBPeripheral
    private weak var link: ObdLink?
    private let done = DispatchSemaphore(value: 0)
    // Queue only, except failure, read by open after done is signalled.
    private var events: ObdBleEvents?
    private var failure: String?
    private var setUp = false
    private var isOpen = false
    private var servicesLeft = 0
    private var notifyCh: CBCharacteristic?
    private var writeCh: CBCharacteristic?
    private var withResponse = true
    private var writePending = false
    private(set) var payload: Int32 = 20

    init(link: ObdLink, peripheral: CBPeripheral) {
        self.link = link
        self.peripheral = peripheral
    }

    private var queue: DispatchQueue? { link?.queue }

    func open(events: ObdBleEvents) -> String? {
        guard let link, let queue else { return "link stopped" }
        queue.async {
            self.events = events
            link.connect(self)
        }
        if done.wait(timeout: .now() + setupTimeoutSeconds) == .timedOut {
            queue.sync {
                if !self.setUp {
                    self.failure = "setup timed out"
                    self.setUp = true
                    link.disconnect(self)
                }
            }
        }
        return queue.sync { isOpen ? nil : (failure ?? "setup failed") }
    }

    func writeChunk(data chunk: Data) -> Bool {
        guard let queue else { return false }
        queue.async {
            guard self.isOpen, let ch = self.writeCh else {
                self.events?.writeDone(ok: false)
                return
            }
            if self.withResponse {
                self.peripheral.writeValue(chunk, for: ch, type: .withResponse)
            } else {
                self.peripheral.writeValue(chunk, for: ch, type: .withoutResponse)
                // No callback for a write without response: done once the link can take the next one.
                if self.peripheral.canSendWriteWithoutResponse {
                    self.events?.writeDone(ok: true)
                } else {
                    self.writePending = true
                }
            }
        }
        return true
    }

    func disconnect() {
        queue?.async { self.cancel() }
    }

    /// Queue only.
    fileprivate func cancel() {
        isOpen = false
        failed("closed")
        link?.disconnect(self)
    }

    // MARK: Driven by ObdLink and CoreBluetooth, on the queue

    fileprivate func connected() {
        peripheral.discoverServices(nil)
    }

    /// Setup failed: open returns [reason].
    fileprivate func failed(_ reason: String) {
        guard !setUp else { return }
        setUp = true
        failure = reason
        done.signal()
    }

    /// The link dropped: before setup open fails, after it the connection hears why.
    fileprivate func lost(_ reason: String) {
        if setUp {
            if isOpen { events?.lost(reason: reason) }
            isOpen = false
        } else {
            failed(reason)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        let services = peripheral.services ?? []
        guard error == nil, !services.isEmpty else { return giveUp("service discovery failed") }
        servicesLeft = services.count
        services.forEach { peripheral.discoverCharacteristics(nil, for: $0) }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        servicesLeft -= 1
        guard servicesLeft == 0 else { return }
        let all = (peripheral.services ?? []).flatMap { s in
            (s.characteristics ?? []).map {
                GattCharacteristic(service: s.uuid.uuidString, uuid: $0.uuid.uuidString, properties: Int32($0.properties.rawValue))
            }
        }
        guard let pair = BleSerial.shared.choose(characteristics: all) else {
            return giveUp("no serial characteristic in \(peripheral.services?.map(\.uuid.uuidString) ?? [])")
        }
        let chars = (peripheral.services ?? [])
            .filter { BleSerial.shared.uuid(text: $0.uuid.uuidString) == pair.service }
            .flatMap { $0.characteristics ?? [] }
        notifyCh = chars.first { BleSerial.shared.uuid(text: $0.uuid.uuidString) == pair.notify }
        writeCh = chars.first { BleSerial.shared.uuid(text: $0.uuid.uuidString) == pair.write }
        withResponse = pair.withResponse
        guard let notifyCh, writeCh != nil else { return giveUp("characteristic \(pair.notify) gone") }
        peripheral.setNotifyValue(true, for: notifyCh)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic === notifyCh, !setUp else { return }
        guard error == nil, characteristic.isNotifying else { return giveUp("enable notifications failed") }
        payload = Int32(max(1, peripheral.maximumWriteValueLength(for: withResponse ? .withResponse : .withoutResponse)))
        isOpen = true
        setUp = true
        link?.ready(self)
        done.signal()
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard isOpen, characteristic === notifyCh, let value = characteristic.value else { return }
        events?.notified(value: value)
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        events?.writeDone(ok: error == nil)
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        guard writePending else { return }
        writePending = false
        events?.writeDone(ok: true)
    }

    private func giveUp(_ reason: String) {
        failed(reason)
        link?.disconnect(self)
    }
}
