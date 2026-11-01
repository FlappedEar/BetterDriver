import BetterDriverCore
import SwiftUI

/// The saved motion-sensor calibration per RaceBox (FBBD-89), as Android's ImuCalibrationStore:
/// Application Support/imu-calibration/<identifier>.txt with the shared encoding. The recording
/// applies it to a new session's RaceBox (RecordingController), the diagnostics screen takes it.
enum RaceBoxCalibrationFiles {
    static func load(_ deviceId: UUID) -> ImuCalibration? {
        guard let text = try? String(contentsOf: file(deviceId), encoding: .utf8) else { return nil }
        return ImuCalibration.companion.decode(line: text)
    }

    static func save(_ deviceId: UUID, _ calibration: ImuCalibration) throws {
        let dir = AppFolders.base.appendingPathComponent("imu-calibration", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try calibration.encode().write(to: file(deviceId), atomically: true, encoding: .utf8)
    }

    private static func file(_ deviceId: UUID) -> URL {
        AppFolders.base.appendingPathComponent("imu-calibration", isDirectory: true)
            .appendingPathComponent("\(deviceId.uuidString.filter { $0.isLetter || $0.isNumber }.uppercased()).txt")
    }
}

/// The probe behind the screen: the shared RaceBoxDiagnostics (FBBD-371), fed on the link's queue and
/// read on the main one, so every call holds the lock.
final class RaceBoxDiagProbe: RaceBoxLinkProbe {
    private let lock = NSLock()
    private let diag: RaceBoxDiagnostics
    private var current: LinkState = LinkStateConnecting.shared

    init(isMicro: Bool) {
        diag = RaceBoxDiagnostics(isMicro: isMicro)
    }

    static func nowMs() -> Int64 { Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000) }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    func data(_ value: Data) { locked { diag.feed(data: value, nowMs: Self.nowMs()) } }

    func state(_ state: LinkState) {
        locked {
            current = state
            // After a reconnect, a partial packet is dropped and gap tracking starts again.
            if state is LinkStateReconnecting { diag.reset() }
        }
    }

    func link(rssi: Int?, mtu: Int?) {
        locked {
            diag.link(rssiDbm: rssi.map { KotlinInt(value: Int32($0)) }, mtuBytes: mtu.map { KotlinInt(value: Int32($0)) })
        }
    }

    func deviceInfo(id: String, value: String) {
        locked { diag.deviceInfo { RaceBoxDeviceInfoIds.shared.apply(info: $0, id: id, value: value) } }
    }

    func queries() -> [Data] {
        let info = locked { diag.snapshot(nowMs: Self.nowMs()).info }
        return RaceBoxDiagnosticsText.shared.queryData(info: info)
    }

    func snapshot() -> (LinkState, RaceBoxDiagnosticsSnapshot) {
        locked { (current, diag.snapshot(nowMs: Self.nowMs())) }
    }

    func calibration(_ c: ImuCalibration?) { locked { diag.calibration(c: c) } }
    func startCapture() { locked { diag.startCapture() } }
    func endCapture() -> [ImuSample] { locked { diag.endCapture() } }
}

/// What the screen shows: as Android's DiagnosticsView.
enum RaceBoxDiagView: Equatable {
    case recording([DiagRow])
    case unavailable(String)
    case live(DiagHeaderText, [DiagSection])
}

/// RaceBox diagnostics for one equipment item, as on Android (FBBD-89, UI-39 A, UI-40 B; iOS FBBD-371):
/// while open and no session records, its own link to the RaceBox reads the Device Information
/// strings and sends the two read-only queries; the shared core turns what comes back into the
/// screen's text. Calibrate takes 5 s of still samples and saves them for this RaceBox, for the next
/// session. Leaving the screen closes the link.
@MainActor
final class RaceBoxDiagModel: ObservableObject {
    @Published private(set) var view: RaceBoxDiagView?
    @Published private(set) var calibrating = false
    private var calibrationError: String?

    let name: String
    private let deviceId: UUID?
    private let probe: RaceBoxDiagProbe
    private var link: RaceBoxLink?
    private var unavailable: String?
    private var timer: Timer?
    private var demoStarted = false

    private static let clock: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "HH:mm:ss"
        return f
    }()

    private static let taken: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_GB")
        f.dateFormat = "d MMM, HH:mm"
        return f
    }()

    init(item: Equipment) {
        name = item.name
        deviceId = item.address.flatMap(UUID.init(uuidString:))
        // The advertised name tells a Mini from a Micro even after a rename; the Model string confirms it.
        let model = item.advertisedName ?? item.name
        probe = RaceBoxDiagProbe(isMicro: !model.hasPrefix("RaceBox Mini"))
        if let deviceId { probe.calibration(RaceBoxCalibrationFiles.load(deviceId)) }
    }

    /// Opens the link unless a session records; refreshes four times a second, as Android.
    func start(recorder: RecordingController) {
        refresh(recorder)
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self, weak recorder] _ in
            MainActor.assumeIsolated {
                guard let self, let recorder else { return }
                self.refresh(recorder)
            }
        }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        link?.stop()
        link = nil
    }

    private func refresh(_ recorder: RecordingController) {
        if let session = recorder.session {
            // The recording owns the RaceBox: nothing connects, the screen shows what it receives.
            if link != nil { link?.stop(); link = nil }
            view = .recording(RaceBoxDiagnosticsText.shared.recordingRows(live: session.screen().live))
            return
        }
        guard let deviceId else {
            view = .unavailable("This RaceBox has no Bluetooth identifier on this phone. Add it again from Equipment.")
            return
        }
        #if DEBUG
        if RaceBoxDiagDemo.on {
            RaceBoxDiagDemo.feed(probe, first: !demoStarted)
            demoStarted = true
        } else if link == nil {
            openLink(deviceId)
        }
        #else
        if link == nil { openLink(deviceId) }
        #endif
        if let unavailable {
            view = .unavailable(unavailable)
            return
        }
        let (state, s) = probe.snapshot()
        let text = RaceBoxDiagnosticsText.shared
        view = .live(
            text.header(name: name, link: state, s: s),
            text.sections(
                s: s,
                streaming: state is LinkStateStreaming,
                calibrationError: calibrationError,
                clock: { Self.clock.string(from: Date(timeIntervalSince1970: Double($0.int64Value) / 1000)) },
                takenAt: { Self.taken.string(from: Date(timeIntervalSince1970: Double($0.int64Value) / 1000)) }
            )
        )
    }

    private func openLink(_ deviceId: UUID) {
        if link == nil {
            // The link reports on the main queue.
            let l = RaceBoxLink(deviceId: deviceId, probe: probe) { [weak self] state in
                MainActor.assumeIsolated {
                    guard let self else { return }
                    if case .unavailable(let reason) = state { self.unavailable = reason } else { self.unavailable = nil }
                }
            }
            link = l
            l.start()
        }
    }

    /// 5 s of still samples into a calibration, saved for this RaceBox (FBBD-89).
    func calibrate() {
        guard !calibrating, let deviceId else { return }
        calibrating = true
        calibrationError = nil
        probe.startCapture()
        Task { @MainActor [self] in
            try? await Task.sleep(nanoseconds: UInt64(RaceBoxDiagnosticsText.shared.CALIBRATION_MS) * 1_000_000)
            let samples = probe.endCapture()
            let result = ImuCalibration.companion.from(samples: samples, atMillis: Int64(Date().timeIntervalSince1970 * 1000))
            if let ok = result as? ImuCalibrationResultOk {
                do {
                    try RaceBoxCalibrationFiles.save(deviceId, ok.calibration)
                    probe.calibration(ok.calibration)
                    AppLog.i("RaceBox", "calibration saved for \(deviceId.uuidString): \(ok.calibration.encode())")
                } catch {
                    calibrationError = "Could not save the calibration: \(error.localizedDescription)"
                }
            } else if let failed = result as? ImuCalibrationResultFailed {
                calibrationError = failed.failure.text
            }
            calibrating = false
        }
    }
}

/// The screen (UI-39 A): every value the RaceBox reports, in one live list; while a session records,
/// only what the recorder receives (UI-40 B). It only reads; it never changes a RaceBox setting.
struct RaceBoxDiagnosticsPage: View {
    @EnvironmentObject private var recorder: RecordingController
    @StateObject private var model: RaceBoxDiagModel

    init(item: Equipment) {
        _model = StateObject(wrappedValue: RaceBoxDiagModel(item: item))
    }

    var body: some View {
        ShellPage(title: RaceBoxDiagnosticsText.shared.TITLE) {
            switch model.view {
            case .recording(let rows):
                ShellCard {
                    HStack(spacing: 10) {
                        Circle().fill(Palette.loss).frame(width: 10, height: 10)
                        Text("Recording. Showing what the recorder receives.").foregroundStyle(Palette.onBackground)
                    }
                }
                .accessibilityIdentifier("raceBoxDiag.recording")
                if !rows.isEmpty {
                    ShellLabel("From the recording")
                    ShellCard { rowsView(rows) }
                }
                DimText("Everything else is shown when no session is recording.").padding(.horizontal, 4)
            case .unavailable(let reason):
                ShellCard {
                    Text("Bluetooth needed").font(.headline).foregroundStyle(Palette.onBackground)
                    DimText("\(reason). Allow Bluetooth for BetterDriver in Settings to connect to the RaceBox.")
                }
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("raceBoxDiag.header")
            case .live(let header, let sections):
                ShellCard {
                    HStack(spacing: 10) {
                        Circle().fill(header.ok ? TrackColors.gain : Palette.warning).frame(width: 10, height: 10)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(header.title).font(.headline).foregroundStyle(Palette.onBackground)
                            DimText(header.detail)
                        }
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("raceBoxDiag.header")
                ForEach(Array(sections.enumerated()), id: \.offset) { _, section in
                    ShellLabel(section.title)
                    ShellCard {
                        rowsView(section.rows)
                        if let note = section.note { DimText(note).padding(.top, 4) }
                        if let action = section.action {
                            Button(action) { model.calibrate() }
                                .buttonStyle(.bordered)
                                .tint(Palette.accent)
                                .disabled(!section.actionEnabled || model.calibrating)
                                .padding(.top, 8)
                                .accessibilityIdentifier("raceBoxDiag.calibrate")
                        }
                    }
                }
                if !sections.isEmpty {
                    DimText(RaceBoxDiagnosticsText.shared.NOT_REPORTED).padding(.horizontal, 4)
                }
            case nil:
                EmptyView()
            }
        }
        .onAppear { model.start(recorder: recorder) }
        .onDisappear { model.stop() }
    }

    private func rowsView(_ rows: [DiagRow]) -> some View {
        ForEach(Array(rows.enumerated()), id: \.offset) { i, row in
            ListRow(row.label, value: row.value, divider: i > 0)
        }
    }
}

#if DEBUG
/// The diagnostics screen on the simulator, which has no Bluetooth (`-raceBoxDiagDemo`, UI test only):
/// the RaceBox document's example Data Message (rev 8, p. 7, a Mini) at about 25 Hz with made-up
/// Device Information strings, through the same shared probe and text as a real RaceBox.
enum RaceBoxDiagDemo {
    static let on = ProcessInfo.processInfo.arguments.contains("-raceBoxDiagDemo")

    private static let example = Data((
        "B5 62 FF 01 50 00 A0 E7 0C 07 E6 07 01 0A 08 33 08 37 19 00 00 00 2A AD 4D 0E 03 01 EA 0B C6 93 " +
        "E1 0D 3B 37 6F 19 61 8C 09 00 0F 01 09 00 9C 03 00 00 2C 07 00 00 23 00 00 00 00 00 00 00 D0 00 " +
        "00 00 88 A9 DD 00 2C 01 00 59 FD FF 71 00 CE 03 2F FF 56 00 FC FF 06 DB"
    ).split(separator: " ").map { UInt8($0, radix: 16)! })
    /// One refresh's worth (0.25 s): six messages, split as a default-MTU link delivers them.
    static func feed(_ probe: RaceBoxDiagProbe, first: Bool) {
        if first {
            probe.deviceInfo(id: "2A24", value: "RaceBox Mini S")
            probe.deviceInfo(id: "2A26", value: "3.4")
            probe.link(rssi: -58, mtu: 185)
            probe.state(LinkStateStreaming(hz: 25))
        }
        for _ in 0..<6 {
            probe.data(example.prefix(20))
            probe.data(example.dropFirst(20))
        }
    }
}
#endif
