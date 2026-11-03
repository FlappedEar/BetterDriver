import BetterDriverCore
import Foundation
import Network

/// A Wi-Fi GPS receiver in a recording on iOS (FBBD-374), the Android app's TcpNmeaSource: a TCP
/// connection to the "host:port" listed in Settings › Equipment, reading NMEA 0183 sentences and
/// RaceChrono DIY $RC2/$RC3 lines, which the shared GnssReceiverSession records and logs exactly as
/// on Android. The connection's life cycle is the shared NotifyLinkCore with Android's stream timing:
/// 5 s without a line restarts it, retries 1 s, 2 s, 4 s, then every 5 s, and no quiet wait (a Wi-Fi
/// receiver has no background connection). The device is the server; this end only reads.
final class GnssTcpLink: NotifyLinkIo {
    private let queue = DispatchQueue(label: "gnss.tcp.link", qos: .userInitiated)
    private let sink: SampleSink
    private let host: String
    private let port: UInt16
    private let name: String
    private var session: GnssReceiverSession?
    private var core: NotifyLinkCore?
    private var connection: NWConnection?
    private var watch: DispatchSourceTimer?
    private var running = false
    private let onState: (LinkState) -> Void

    /// Nil when [address] is not "host:port" with a usable port.
    init?(sink: SampleSink, address: String, name: String, onState: @escaping (LinkState) -> Void) {
        guard let parsed = TcpAddress.companion.parse(address: address),
              let port = UInt16(exactly: parsed.port) else { return nil }
        self.sink = sink
        self.host = parsed.host
        self.port = port
        self.name = name
        self.onState = onState
    }

    func start() {
        queue.async {
            guard !self.running else { return }
            self.running = true
            let address = "\(self.host):\(self.port)"
            AppLog.i("TcpGps", "receiver \(self.name) \(address) from Settings")
            let s = GnssReceiverSession.companion.nmea(sink: self.sink, address: address, deviceName: self.name)
            self.session = s
            s.start()
            let report = self.onState
            let core = NotifyLinkCore(
                address: address,
                io: self,
                now: { KotlinLong(value: Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)) },
                onState: { state in
                    s.linkChanged(s: state)
                    DispatchQueue.main.async { report(state) }
                },
                silenceTimeoutMs: 5_000,
                setupTimeoutMs: NotifyLinkCore.companion.SetupTimeoutMs,
                backgroundSetupTimeoutMs: NotifyLinkCore.companion.SetupTimeoutMs,
                policy: ReconnectPolicy(
                    firstDelayMs: NotifyLinkCore.companion.FirstRetryMs,
                    maxDelayMs: NotifyLinkCore.companion.MaxRetryMs
                ),
                quietAfter: Int32.max
            )
            self.core = core
            // Once a second, as Android's watchdog: the core's rate, silence and setup checks.
            let timer = DispatchSource.makeTimerSource(queue: self.queue)
            timer.schedule(deadline: .now() + 1, repeating: 1)
            timer.setEventHandler { [weak self] in self?.core?.tick() }
            timer.resume()
            self.watch = timer
            core.start()
        }
    }

    func stop() {
        queue.async {
            self.running = false
            self.watch?.cancel()
            self.watch = nil
            self.core?.stop()
            // The core holds this link as its IO: dropping it lets a finished recording go.
            self.core = nil
            self.connection?.cancel()
            self.connection = nil
        }
    }

    // MARK: NotifyLinkIo, called by the core on this queue

    func open(plan: ConnectPlan) -> String? {
        guard running, let port = NWEndpoint.Port(rawValue: port) else { return "stopped" }
        let c = NWConnection(host: NWEndpoint.Host(host), port: port, using: .tcp)
        connection = c
        c.stateUpdateHandler = { [weak self, weak c] state in
            guard let self, let c, c === self.connection else { return }
            switch state {
            case .ready:
                self.core?.connected()
                self.core?.reached(next: .firstValue)
                self.receive(c)
            case .waiting(let error), .failed(let error):
                self.core?.failed(reason: "\(error)")
            default:
                break
            }
        }
        c.start(queue: queue)
        return nil
    }

    func close(setupFailed: Bool) {
        connection?.cancel()
        connection = nil
    }

    func later(delayMs: Int64, action: @escaping () -> Void) {
        queue.asyncAfter(deadline: .now() + .milliseconds(Int(delayMs)), execute: action)
    }

    func detail() -> String? { "Wi-Fi" }

    private func receive(_ c: NWConnection) {
        c.receive(minimumIncompleteLength: 1, maximumLength: 1_024) { [weak self] data, _, complete, error in
            guard let self, c === self.connection else { return }
            if let data, !data.isEmpty {
                // The rate is lines a second, as Android's stream link counts them.
                for byte in data where byte == 0x0A { self.core?.data() }
                self.session?.feed(data: data)
            }
            if let error {
                self.core?.failed(reason: "\(error)")
            } else if complete {
                self.core?.failed(reason: "closed by the receiver")
            } else {
                self.receive(c)
            }
        }
    }
}
