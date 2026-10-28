import BetterDriverCore
import CoreBluetooth

private let controlService = CBUUID(string: GoProUuids.shared.CONTROL_SERVICE)
private let wifiService = CBUUID(string: GoProUuids.shared.WIFI_AP_SERVICE)
/// The three characteristics the camera answers on (GP-0073, 0075, 0077).
private let responses = [
    GoProUuids.shared.COMMAND_RESPONSE, GoProUuids.shared.SETTINGS_RESPONSE, GoProUuids.shared.QUERY_RESPONSE,
].map { CBUUID(string: $0) }

/// One Open GoPro camera over Bluetooth LE on the iPhone (FBBD-343), as the Android app's
/// GoProBleLink: the shared Kotlin start and stop (OpenGoProCamera) asks for each step and this does
/// it with CoreBluetooth. Connecting finds the camera by the identifier the iPhone gave it when it was
/// added (scanning for the Open GoPro service if iOS does not know it yet), discovers the control and
/// Wi-Fi services and turns on the three response characteristics; the first of those makes iOS show
/// its pairing prompt for a camera in pairing mode. Everything runs on the main queue, where the
/// Kotlin side waits.
final class GoProBleLink: IosGoProLink, CBCentralManagerDelegate, CBPeripheralDelegate {
    private let deviceId: UUID?
    private var central: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var wantConnected = false
    private var characteristics: [CBUUID: CBCharacteristic] = [:]
    private var notifying: Set<CBUUID> = []
    private var ready = false

    /// [address] is CoreBluetooth's identifier for the camera, kept as the equipment's address.
    init(address: String) {
        deviceId = UUID(uuidString: address)
        super.init()
    }

    override func beginConnect() {
        guard deviceId != nil else {
            connected(error: "No Bluetooth identifier for this GoPro")
            return
        }
        wantConnected = true
        if central == nil {
            central = CBCentralManager(delegate: self, queue: nil)
        } else {
            connectIfReady()
        }
    }

    override func beginWrite(characteristic: String, packet: Data) {
        guard ready, let peripheral, let c = characteristics[CBUUID(string: characteristic)] else {
            written(error: "GoPro not connected")
            return
        }
        peripheral.writeValue(packet, for: c, type: .withResponse)
    }

    override func beginRead(characteristic: String) {
        guard ready, let peripheral, let c = characteristics[CBUUID(string: characteristic)] else {
            readDone(value: nil, error: "GoPro not connected")
            return
        }
        peripheral.readValue(for: c)
    }

    override func beginDisconnect() {
        wantConnected = false
        ready = false
        central?.stopScan()
        if let central, let peripheral { central.cancelPeripheralConnection(peripheral) }
    }

    private func connectIfReady() {
        guard wantConnected, let central, central.state == .poweredOn, let deviceId else { return }
        ready = false
        characteristics = [:]
        notifying = []
        if let known = peripheral ?? central.retrievePeripherals(withIdentifiers: [deviceId]).first {
            peripheral = known
            known.delegate = self
            central.connect(known)
        } else {
            // A camera iOS has not seen since launch: found by its Open GoPro service.
            central.scanForPeripherals(withServices: [controlService])
        }
    }

    private func fail(_ reason: String) {
        let wasReady = ready
        ready = false
        characteristics = [:]
        notifying = []
        if wasReady {
            disconnected(error: reason)
        } else {
            connected(error: reason)
        }
    }

    // MARK: CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn: connectIfReady()
        case .unauthorized: if wantConnected { fail("Bluetooth not allowed") }
        case .poweredOff: if wantConnected { fail("Bluetooth off") }
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
        peripheral.delegate = self
        central.connect(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([controlService, wifiService])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        fail(error?.localizedDescription ?? "GoPro connect failed")
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        fail(error?.localizedDescription ?? "disconnected")
    }

    // MARK: CBPeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        let services = peripheral.services ?? []
        guard services.contains(where: { $0.uuid == controlService }) else {
            fail("Not an Open GoPro camera")
            central?.cancelPeripheralConnection(peripheral)
            return
        }
        for service in services { peripheral.discoverCharacteristics(nil, for: service) }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        for c in service.characteristics ?? [] {
            characteristics[c.uuid] = c
            if responses.contains(c.uuid) { peripheral.setNotifyValue(true, for: c) }
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard responses.contains(characteristic.uuid) else { return }
        if let error {
            // Pairing refused or not in pairing mode: the camera does not answer without it.
            fail(error.localizedDescription)
            central?.cancelPeripheralConnection(peripheral)
            return
        }
        if characteristic.isNotifying { notifying.insert(characteristic.uuid) }
        if !ready, notifying.count == responses.count {
            ready = true
            connected(error: nil)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        written(error: error?.localizedDescription)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if responses.contains(characteristic.uuid) {
            if let value = characteristic.value { notified(characteristic: characteristic.uuid.uuidString, packet: value) }
        } else {
            readDone(value: characteristic.value, error: error?.localizedDescription)
        }
    }
}
