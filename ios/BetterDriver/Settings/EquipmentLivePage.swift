import BetterDriverCore
import SwiftUI

/// The shared live data (EquipmentLiveData, FBBD-440) behind an Equipment page: fed by the device's
/// link on its own queue and read on the main one, so every call holds the lock.
final class LiveDataFeed: NSObject, SampleSink {
    private let lock = NSLock()
    private let data = EquipmentLiveData()

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    func defineChannel(def: ChannelDef) { locked { data.defineChannel(def: def) } }
    func submit(set: SampleSet) { locked { data.submit(set: set) } }
    func event(kind: String, text: String) { locked { data.event(kind: kind, text: text) } }
    func nowMicros() -> Int64 { data.nowMicros() }
    func snapshot() -> EquipmentLiveSnapshot { locked { data.snapshot() } }
    func reset() { locked { data.reset() } }
}

/// What the page shows: as Android's DiagnosticsView.
enum LiveDataView: Equatable {
    case recording
    case unavailable(String)
    case live(DiagHeaderText, [DiagSection])
}

/// An Equipment item's live data without a session, as on Android (FBBD-440, Arek 2026-11-03): while
/// open and no session records, the device's own recording link (phone GPS, heart-rate strap, tyre
/// bridge, the demo receivers) runs into the shared live data, or for a paired GoPro the shared
/// read-only status (GoProLive). A session starting takes the device back; leaving closes the link.
@MainActor
final class LiveDataModel: ObservableObject {
    @Published private(set) var view: LiveDataView?

    let item: Equipment
    private let feed = LiveDataFeed()
    private var stopLink: (() -> Void)?
    private var goPro: GoProLive?
    private var unavailable: String?
    private var timer: Timer?

    private static let utc: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "HH:mm:ss.S"
        return f
    }()

    init(item: Equipment) {
        self.item = item
    }

    var isGoPro: Bool { item.modelName == ModelName.goPro }

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
        close()
    }

    private func close() {
        if stopLink != nil || goPro != nil { AppLog.i("LiveData", "close item=\(item.id) model=\(item.modelName)") }
        stopLink?()
        stopLink = nil
        goPro?.stop()
        goPro = nil
        feed.reset()
    }

    private func refresh(_ recorder: RecordingController) {
        if recorder.session != nil {
            // The recording owns the device: nothing connects.
            close()
            view = .recording
            return
        }
        if stopLink == nil && goPro == nil && unavailable == nil { open() }
        if let unavailable {
            view = .unavailable(unavailable)
            return
        }
        let text: (DiagHeaderText, [DiagSection])
        if let goPro {
            let s = goPro.snapshot()
            text = (GoProLiveText.shared.header(name: item.name, s: s), GoProLiveText.shared.sections(s: s))
        } else {
            let s = feed.snapshot()
            let sections = EquipmentLiveText.shared.sections(s: s) { ms in
                Self.utc.string(from: Date(timeIntervalSince1970: Double(ms.int64Value) / 1000))
            }
            text = (EquipmentLiveText.shared.header(name: item.name, s: s), sections)
        }
        view = .live(text.0, text.1)
    }

    /// The link that records this item in a session, made as RecordingController makes it.
    private func open() {
        let device = item.address.flatMap(UUID.init(uuidString:))
        let listed = device.map { (id: $0, name: item.advertisedName ?? item.name) }
        AppLog.i("LiveData", "open item=\(item.id) model=\(item.modelName)")
        switch item.modelName {
        case ModelName.goPro:
            guard let address = item.address else { return noIdentifier() }
            let live = OpenGoPros.companion.live(link: GoProBleLink(address: address))
            live.start()
            goPro = live
        case ModelName.heartRate:
            guard let listed else { return noIdentifier() }
            let link = HeartRateStrapLink(sink: feed, listed: listed)
            link.start()
            stopLink = link.stop
        case ModelName.tpmsBridge:
            guard let listed else { return noIdentifier() }
            let link = TpmsBridgeLink(sink: feed, listed: listed)
            link.start()
            stopLink = link.stop
        case ModelName.phoneGps:
            let link = PhoneGpsLink(sink: feed) { [weak self] state in
                MainActor.assumeIsolated {
                    if state == .notAllowed {
                        self?.unavailable = "Location access is off. Allow it in Settings › BetterDriver › Location."
                    }
                }
            }
            link.start()
            stopLink = link.stop
        case ModelName.demoGnss:
            let link = SimulatedGpsLink(sink: feed)
            link.start()
            stopLink = link.stop
        case ModelName.demoTrack:
            do {
                let link = try DemoTrackLink(sink: feed)
                link.start()
                stopLink = link.stop
            } catch {
                unavailable = "Could not start the Demo track: \(error.localizedDescription)"
            }
        default:
            unavailable = "This device has no live data on the iPhone yet."
        }
    }

    private func noIdentifier() {
        unavailable = "This device has no Bluetooth identifier on this phone. Add it again from Equipment."
    }
}

/// The page (FBBD-440), in the RaceBox diagnostics page's look (UI-39 A): a header card, the values
/// the device sends in groups, its rate and its link's events. It only reads.
struct EquipmentLivePage: View {
    @EnvironmentObject private var recorder: RecordingController
    @StateObject private var model: LiveDataModel

    init(item: Equipment) {
        _model = StateObject(wrappedValue: LiveDataModel(item: item))
    }

    var body: some View {
        ShellPage(title: EquipmentLiveText.shared.TITLE) {
            switch model.view {
            case .recording:
                ShellCard {
                    HStack(spacing: 10) {
                        Circle().fill(Palette.loss).frame(width: 10, height: 10)
                        Text(EquipmentLiveText.shared.RECORDING).foregroundStyle(Palette.onBackground)
                    }
                }
                .accessibilityIdentifier("liveData.recording")
                DimText(EquipmentLiveText.shared.RECORDING_NOTE).padding(.horizontal, 4)
            case .unavailable(let reason):
                ShellCard {
                    Text("Cannot connect").font(.headline).foregroundStyle(Palette.onBackground)
                    DimText(reason)
                }
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("liveData.header")
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
                .accessibilityIdentifier("liveData.header")
                ForEach(Array(sections.enumerated()), id: \.offset) { _, section in
                    ShellLabel(section.title)
                    ShellCard {
                        ForEach(Array(section.rows.enumerated()), id: \.offset) { i, row in
                            ListRow(row.label, value: row.value, divider: i > 0)
                        }
                    }
                }
                DimText(model.isGoPro ? GoProLiveText.shared.NOTE : EquipmentLiveText.shared.NOTE)
                    .padding(.horizontal, 4)
            case nil:
                EmptyView()
            }
        }
        .onAppear { model.start(recorder: recorder) }
        .onDisappear { model.stop() }
    }
}
