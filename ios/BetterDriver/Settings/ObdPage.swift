import BetterDriverCore
import SwiftUI

/// The car data page's connection (FBBD-261, UI-104 B), as the Android app's rememberCarLink: while a
/// session records it shows the recorder's OBD connection; otherwise, while the page is shown, it
/// opens its own to the listed adapters and the Wi-Fi one, reading every channel the car has and
/// nothing recorded, and lets go as soon as a session starts (the adapter takes one connection at a
/// time). Scan car (FBBD-344, UI-303 B) stores what the car offers with the next session's car.
@MainActor
final class ObdPageModel: ObservableObject {
    @Published private(set) var status: ObdStatus?
    @Published private(set) var recording = false
    @Published private(set) var scanning = false
    @Published private(set) var scanNote: String?
    private var link: ObdLink?
    private var timer: Timer?
    private weak var recorder: RecordingController?

    func appear(_ recorder: RecordingController) {
        self.recorder = recorder
        refresh()
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.refresh() }
        }
    }

    func disappear() {
        timer?.invalidate()
        timer = nil
        link?.stop()
        link = nil
    }

    /// Scan car, on the page's own connection; nothing while a session records.
    func scan() {
        guard let link else { return }
        link.scanCar()
        scanning = true
        scanNote = nil
    }

    var canScan: Bool { link != nil && SettingsModel.shared.setup.active().car != nil }

    private func refresh() {
        recording = recorder?.session != nil
        if recording {
            if let link {
                link.stop()
                self.link = nil
                AppLog.i("Obd", "page lets go: a session records")
            }
            status = recorder?.obdStatus()
            scanning = false
            return
        }
        if link == nil {
            let setup = SettingsModel.shared.setup
            let listed = RecordingController.listed(setup.active().carData, ModelName.obdBluetooth)
            let page = ObdLink(sink: NoSamples.shared, listed: listed, setup: setup, page: true)
            link = page
            page.start()
        }
        guard let link else { return }
        status = link.status()
        if let record = link.takeScan(), let carId = SettingsModel.shared.setup.active().car?.id {
            AppLog.i("Car", "scan stored: \(record.supported.count) PIDs, \(record.canIds.count) CAN ids")
            SettingsModel.shared.update { ObdPageKt.withCarScan(setup: $0, carId: carId, record: record) }
        }
        scanning = link.scanning
        scanNote = link.scanNote
    }
}

/// The adapter's state and the car's values (FBBD-261, UI-104 B), as the Android app's ObdScreen.
struct ObdPage: View {
    @EnvironmentObject private var settings: SettingsModel
    @EnvironmentObject private var recorder: RecordingController
    @StateObject private var model = ObdPageModel()

    var body: some View {
        ShellPage(title: settings.setup.active().car?.name ?? "Car data") {
            ObdContent(model: model)
        }
        .onAppear { model.appear(recorder) }
        .onDisappear { model.disappear() }
    }
}

/// The header, the values and the note under them, on the car data page.
struct ObdContent: View {
    @EnvironmentObject private var settings: SettingsModel
    @ObservedObject var model: ObdPageModel
    /// False on the car's page, which has Scan car under what the car offers (UI-300 A).
    var showsScan = true

    var body: some View {
        // iOS also tries the Wi-Fi adapter when none is listed (FBBD-333), so the page always connects.
        let adapter = settings.setup.active().carData.first
        let name = adapter?.name ?? "Wi-Fi adapter"
        let wifi = adapter == nil || adapter?.modelName == ModelName.obdWifi
        if let status = model.status {
            header(
                ready: status.state == ObdLinkState.connected,
                title: ObdPageText.shared.title(recording: model.recording, status: status),
                detail: ObdPageText.shared.detail(status: status, adapter: name)
            )
            if !status.readings.isEmpty {
                ShellLabel("Values from the car")
                ShellCard {
                    ForEach(Array(status.readings.enumerated()), id: \.offset) { i, r in
                        ListRow(label: r.name, divider: i > 0) {
                            Text(ObdPageText.shared.value(reading: r)).fontWeight(.semibold).foregroundStyle(Palette.onBackground)
                            Text(ObdPageText.shared.rate(reading: r))
                                .font(.caption)
                                .foregroundStyle(Palette.onSurfaceVariant)
                                .frame(width: 56, alignment: .trailing)
                        }
                    }
                }
                .accessibilityIdentifier("obdValues")
            }
            DimText(ObdPageText.shared.note(recording: model.recording, status: status, adapter: name, wifi: wifi))
        } else {
            header(ready: false, title: "Not recorded", detail: "This session does not record OBD-II.")
        }
        if showsScan, model.canScan {
            ShellTonalButton(text: model.scanning ? "Scanning…" : "Scan car", enabled: !model.scanning) { model.scan() }
                .accessibilityIdentifier("scanCar")
        }
        if showsScan, let note = model.scanNote { DimText(note) }
    }

    private func header(ready: Bool, title: String, detail: String) -> some View {
        ShellCard {
            HStack(spacing: 10) {
                StatusDot(ready: ready)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.headline).foregroundStyle(Palette.onBackground)
                    DimText(detail)
                }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("obdHeader")
    }
}
