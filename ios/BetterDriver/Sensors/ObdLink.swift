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
/// for apps, so only LE Bluetooth adapters are used. Only the adapters listed in Settings › Equipment
/// are tried, chosen by the shared rule Android uses (SessionEquipment, FBBD-349): with none listed,
/// none is read, and one removed there is not used again. All CoreBluetooth work runs on one serial queue.
final class ObdLink: NSObject, CBCentralManagerDelegate, ObdFeedListener {
    /// Every CoreBluetooth call of this link and its ports runs here.
    fileprivate let queue = DispatchQueue(label: "obd.link", qos: .userInitiated)
    private var central: CBCentralManager!
    private var feed: ObdFeed!
    private var running = false
    /// Listed adapters seen in this session, by identifier, and the port of the one connecting or open.
    private var seen: [UUID: CBPeripheral] = [:]
    /// Named Bluetooth devices nearby that are not OBD adapters, logged once each: when no adapter is
    /// found, the log shows whether the adapter was off, busy with another phone, or named otherwise.
    private var others: Set<String> = []
    /// OBD adapters nearby that Settings › Equipment does not list, logged once each and never tried.
    private var notListed: Set<UUID> = []
    private var port: ObdBlePortLink?
    /// When an adapter seen before, advertising again, last ended a retry delay early.
    private var seenRetryAt = Date.distantPast
    /// The Wi-Fi network as iOS reports it: logged, and a network coming back is tried at once.
    private var wifi: NWPathMonitor?
    private var onWifi: Bool?

    /// A Bluetooth LE adapter listed in Settings › Equipment, by the iPhone's identifier for it.
    typealias BleAdapter = (id: UUID, name: String)
    /// A Wi-Fi adapter listed there, at its listed address.
    typealias WifiAdapter = (host: String, port: Int32, name: String)

    /// The Bluetooth LE adapters listed, in order: only these are tried.
    private let listed: [BleAdapter]
    /// The Wi-Fi adapters listed, tried after the Bluetooth ones.
    private let wifiListed: [WifiAdapter]

    /// The adapters [setup] lists, by the shared rule (FBBD-349): what a session or the page connects to.
    static func adapters(_ setup: Setup?) -> (bluetooth: [BleAdapter], wifi: [WifiAdapter]) {
        guard let active = setup?.active() else { return ([], []) }
        let bluetooth = active.obdBluetooth.compactMap { item -> BleAdapter? in
            item.address.flatMap(UUID.init(uuidString:)).map { ($0, item.name) }
        }
        let wifi = active.obdWifi.map { WifiAdapter(host: $0.at.host, port: $0.at.port, name: $0.adapter.name) }
        return (bluetooth, wifi)
    }

    /// Whether [setup] lists any adapter this phone can reach: without one no ObdLink starts.
    static func anyListed(_ setup: Setup?) -> Bool {
        let a = adapters(setup)
        return !a.bluetooth.isEmpty || !a.wifi.isEmpty
    }

    /// [sink] is the recording; the adapters are the ones [setup] lists ([adapters]). With [setup], the
    /// session's car's own parameters and switched-on PIDs are read (FBBD-344), and [tiles] are its
    /// track screen. (The setup, not the car: setup's and obd's CarProfile share a name in Swift.)
    /// With [page] it is the car data page's own connection outside a session (FBBD-261, FBBD-344):
    /// every channel the car has, nothing recorded, and Scan car.
    init(sink: SampleSink, setup: Setup?, tiles: [String] = [], page: Bool = false) {
        let adapters = Self.adapters(setup)
        listed = adapters.bluetooth
        wifiListed = adapters.wifi
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
            let wifi: [ObdTarget] = wifiListed.map { IosWifiObdTarget(host: $0.host, port: $0.port, name: $0.name) }
            return bluetoothTargets() + wifi
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

    /// Queue only. The Bluetooth adapters listed in Settings, in their order (FBBD-349): nothing else.
    private func bluetoothTargets() -> [ObdTarget] {
        guard !listed.isEmpty, central?.state == .poweredOn else { return [] }
        var list: [CBPeripheral] = []
        for item in listed {
            if let known = seen[item.id] ?? central.retrievePeripherals(withIdentifiers: [item.id]).first {
                list.append(known)
            }
        }
        // Keep looking while no adapter is open: a listed one may be out of range now and back later.
        scan()
        return list.map { p in
            let name = listed.first { $0.id == p.identifier }?.name ?? p.name ?? "OBD adapter"
            return IosBleObdTarget(name: name, identifier: p.identifier.uuidString, port: ObdBlePortLink(link: self, peripheral: p))
        }
    }

    private func scan() {
        guard running, !listed.isEmpty, central.state == .poweredOn, !central.isScanning else { return }
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
        log(text: "connecting \(port.peripheral.name ?? "?") \(port.peripheral.identifier.uuidString)")
        port.connectStarted = Date()
        central.connect(port.peripheral)
    }

    fileprivate func disconnect(_ port: ObdBlePortLink) {
        if self.port === port { self.port = nil }
        central?.cancelPeripheralConnection(port.peripheral)
        // A failed or ended connection: look for adapters again, the didDisconnect callback no longer will.
        scan()
    }

    fileprivate func ready(_ port: ObdBlePortLink) {
        log(text: "serial line ready: \(port.setupText)")
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
        // By name, or by an ELM327 serial service it advertises, so an adapter of another make is tried.
        let services = (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []).map(\.uuidString)
        guard ObdAdapterNames.shared.isCandidate(name: name, services: services) else {
            if let name, others.count < 30, others.insert(name).inserted {
                log(text: "nearby, not an OBD adapter: \(name)\(advertised(services))")
            }
            return
        }
        // An adapter not in Settings › Equipment is never used (FBBD-349): said once, so the log shows it.
        guard listed.contains(where: { $0.id == peripheral.identifier }) else {
            if notListed.insert(peripheral.identifier).inserted {
                log(text: "found \(name ?? "?") \(peripheral.identifier.uuidString)\(advertised(services)), not in Equipment")
            }
            return
        }
        guard seen[peripheral.identifier] == nil else {
            // Known already: the scan can stop until the next round asks again. Advertising again while
            // no connection is open means it may be back in range or free (FBBD-271): try it at once,
            // at most once in 20 s, so one that keeps failing its setup keeps the retry delays.
            central.stopScan()
            if port == nil, Date().timeIntervalSince(seenRetryAt) >= 20 {
                seenRetryAt = Date()
                feed.retryNow()
            }
            return
        }
        seen[peripheral.identifier] = peripheral
        log(text: "found \(name ?? "?") \(peripheral.identifier.uuidString)\(advertised(services))")
        central.stopScan()
        feed.retryNow()
    }

    /// The services a device advertised, for its log line; nothing when it named none.
    private func advertised(_ services: [String]) -> String {
        services.isEmpty ? "" : " (services \(services.joined(separator: ",")))"
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
    /// When the connect was asked for, and what setup found, for the log (as Android's MTU line, FBBD-333).
    fileprivate var connectStarted = Date()
    fileprivate var setupText = ""

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
        link?.log(text: String(format: "connected in %.1f s, discovering services", Date().timeIntervalSince(connectStarted)))
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
        // Each characteristic with its properties (0x10 notify, 0x08 write, 0x04 write without response),
        // so an adapter whose serial pair is not found or is the wrong one can be read from the log.
        let listed = (peripheral.services ?? []).map { svc in
            let chars = (svc.characteristics ?? []).map { "\($0.uuid.uuidString):0x\(String($0.properties.rawValue, radix: 16))" }
            return "\(svc.uuid.uuidString)[\(chars.joined(separator: " "))]"
        }
        link?.log(text: "services \(listed.joined(separator: " ")), \(all.count) characteristics")
        guard let pair = BleSerial.shared.choose(characteristics: all) else {
            return giveUp("no serial characteristic in \(peripheral.services?.map(\.uuid.uuidString) ?? [])")
        }
        let chars = (peripheral.services ?? [])
            .filter { BleSerial.shared.uuid(text: $0.uuid.uuidString) == pair.service }
            .flatMap { $0.characteristics ?? [] }
        notifyCh = chars.first { BleSerial.shared.uuid(text: $0.uuid.uuidString) == pair.notify }
        writeCh = chars.first { BleSerial.shared.uuid(text: $0.uuid.uuidString) == pair.write }
        withResponse = pair.withResponse
        link?.log(text: "serial pair: service \(pair.service), notify \(pair.notify), write \(pair.write)")
        guard let notifyCh, writeCh != nil else { return giveUp("characteristic \(pair.notify) gone") }
        peripheral.setNotifyValue(true, for: notifyCh)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic === notifyCh, !setUp else { return }
        guard error == nil, characteristic.isNotifying else { return giveUp("enable notifications failed") }
        // The MTU's payload either way: iOS offers 512 bytes with response by long writes (prepare and
        // execute), which ELM327 adapters need not take; Android cuts to the MTU too.
        payload = Int32(max(1, peripheral.maximumWriteValueLength(for: .withoutResponse)))
        setupText = "notify \(characteristic.uuid.uuidString), write \(writeCh?.uuid.uuidString ?? "?") " +
            "\(withResponse ? "with" : "without") response, payload \(payload) bytes, " +
            String(format: "%.1f s after connect asked", Date().timeIntervalSince(connectStarted))
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
        link?.log(text: "setup failed: \(reason)")
        failed(reason)
        link?.disconnect(self)
    }
}
