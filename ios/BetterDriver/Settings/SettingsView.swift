import BetterDriverCore
import SwiftUI

/// Settings topics as on Android (UI-137 B): one short page lists them, each saying what is set, and
/// each opens a page of its own; Tracks opens the Tracks screen (FBBD-336).
enum SettingsTopic: String, CaseIterable, Identifiable {
    case equipment = "Equipment"
    case cars = "Cars"
    case tracks = "Tracks"
    case trackScreen = "Track screen"
    case video = "Video"
    case coach = "Spoken coach"
    case app = "App"
    case diagnostics = "Diagnostics"

    var id: String { rawValue }
}

/// The Settings tab (FBBD-338), the Android app's Settings page.
struct SettingsView: View {
    @EnvironmentObject private var settings: SettingsModel

    var body: some View {
        NavigationStack {
            ShellPage(title: "Settings", large: true) {
                ShellCard {
                    ForEach(Array(SettingsTopic.allCases.enumerated()), id: \.element) { i, topic in
                        NavigationLink(value: topic) {
                            ListRow(topic.rawValue, divider: i > 0, supporting: summary(topic), chevron: true)
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("settings.\(topic)")
                    }
                }
            }
            .navigationDestination(for: SettingsTopic.self) { topic in page(topic) }
        }
    }

    @ViewBuilder
    private func page(_ topic: SettingsTopic) -> some View {
        switch topic {
        case .equipment: EquipmentPage()
        case .cars: CarsPage()
        case .tracks: TracksScreen()
        case .trackScreen: TrackScreenPage()
        case .video: VideoPage()
        case .coach: CoachSettingsPage()
        case .app: AppPage()
        case .diagnostics: DiagnosticsPage()
        }
    }

    /// What a topic's line says is set, as Android's topicSummary.
    private func summary(_ topic: SettingsTopic) -> String {
        let setup = settings.setup
        switch topic {
        case .equipment:
            let names = setup.equipment.map(\.name)
            if names.isEmpty { return "None added" }
            if names.count <= 3 { return names.joined(separator: ", ") }
            return names.prefix(3).joined(separator: ", ") + " and \(names.count - 3) more"
        case .cars:
            if let car = setup.car(id: setup.session.carId) { return "\(car.name) · next session" }
            return setup.cars.isEmpty ? "No cars yet" : "\(setup.cars.count) cars"
        case .tracks:
            let count = TrackFiles.shared.read(dir: AppFolders.tracks.path).count
            return count == 1 ? "1 track and its lines" : "\(count) tracks and their lines"
        case .trackScreen:
            // The tiles first (FBBD-345); the iPhone has no second screen yet.
            let tiles = TrackTiles.shared.ids(setup: setup, carId: setup.session.carId, second: false).filter { !$0.isEmpty }
            let light = settings.fullBrightness ? "Full brightness" : "Phone's brightness"
            return "\(tiles.count) tiles · \(light) · GNSS sound \(settings.gnssSound ? "on" : "off")"
        case .video:
            let free = Storage.freeLabel
            if let cam = setup.equipment.first(where: { $0.videoMode != nil }), let mode = cam.videoMode {
                return "\(cam.name) \(mode.label) · \(free)"
            }
            return free
        case .coach:
            return coachLabel(settings.coach)
        case .app:
            return "\(AppInfo.version) · about"
        case .diagnostics:
            return "Share or clear the app's log"
        }
    }
}

/// Settings › Track screen: the layout of each car's tiles (FBBD-345), then full brightness and the
/// GNSS sound, as on Android.
struct TrackScreenPage: View {
    @EnvironmentObject private var settings: SettingsModel

    var body: some View {
        ShellPage(title: "Track screen") {
            TrackLayoutSection()
            ShellCard {
                ListRow(
                    label: "Track screen brightness",
                    value: settings.fullBrightness ? "Max" : "System",
                    divider: false,
                    trailing: { Toggle("", isOn: $settings.fullBrightness).labelsHidden().tint(Palette.accent) }
                )
                ListRow(
                    label: "Sound when GNSS is lost",
                    value: settings.gnssSound ? "On" : "Off",
                    trailing: { Toggle("", isOn: $settings.gnssSound).labelsHidden().tint(Palette.accent) }
                )
            }
        }
    }
}

/// Settings › Video: one row per camera the app sets, with its mode, and the storage left (UI-39).
struct VideoPage: View {
    @EnvironmentObject private var settings: SettingsModel
    @State private var choosing: SheetId?

    var body: some View {
        let cams = settings.setup.equipment.filter { $0.videoMode != nil }
        ShellPage(title: "Video") {
            ShellCard {
                ForEach(Array(cams.enumerated()), id: \.element.id) { i, cam in
                    ListRow(cam.name, value: cam.videoMode?.label, divider: i > 0) { choosing = SheetId(id: cam.id) }
                }
                ListRow("Storage", value: Storage.freeLabel, divider: !cams.isEmpty)
            }
        }
        .sheet(item: $choosing) { chosen in
            if let cam = settings.setup.equipment(id: chosen.id) { VideoModeSheet(item: cam) }
        }
    }
}

/// Settings › App, as on Android: the look, the spoken coach, the version and the credits.
struct AppPage: View {
    @EnvironmentObject private var settings: SettingsModel

    var body: some View {
        ShellPage(title: "App") {
            ShellCard {
                ListRow("Theme", value: "Always dark", divider: false)
                ListRow("Track screen colours", value: "Green / red")
                NavigationLink(value: SettingsTopic.coach) {
                    ListRow("Spoken coach", value: coachLabel(settings.coach), chevron: true)
                }
                .buttonStyle(.plain)
                ListRow("Version", value: AppInfo.version)
                NavigationLink { NotImplementedPage(title: "Set up") } label: { ListRow("Run setup again", chevron: true) }
                    .buttonStyle(.plain)
                NavigationLink { AboutPage() } label: { ListRow("About and credits", chevron: true) }
                    .buttonStyle(.plain)
            }
        }
    }
}

/// Settings › App › About and credits (FBBD-79), the Android page's credits that the iPhone app uses.
private struct AboutPage: View {
    private struct Credit: Hashable {
        let name: String, detail: String, license: String, url: String
    }

    private struct Section: Hashable {
        let title: String, credits: [Credit]
    }

    private static let ofl = "SIL Open Font License 1.1"
    private static let sections: [Section] = [
        Section(title: "Circuit directory", credits: [
            Credit(name: "Wikidata", detail: "Circuit names and positions, used to name a session's track",
                   license: "CC0 1.0", url: "https://www.wikidata.org"),
        ]),
        Section(title: "Fonts", credits: [
            Credit(name: "Barlow", detail: "Copyright 2017 The Barlow Project Authors", license: AboutPage.ofl,
                   url: "https://github.com/jpt/barlow"),
            Credit(name: "Saira Semi Condensed", detail: "Copyright 2016 The Saira Project Authors", license: AboutPage.ofl,
                   url: "https://openfontlicense.org"),
        ]),
        Section(title: "Ported code", credits: [
            Credit(name: "FlappedEar Telemetry", detail: "The lap analysis and the coach logic are ported from it",
                   license: "Apache 2.0", url: "https://github.com/FlappedEar/Telemetry"),
        ]),
        Section(title: "Open-source libraries", credits: [
            Credit(name: "Kotlin", detail: "Kotlin Multiplatform core", license: "Apache 2.0", url: "https://kotlinlang.org"),
            Credit(name: "kotlinx.serialization", detail: "", license: "Apache 2.0",
                   url: "https://github.com/Kotlin/kotlinx.serialization"),
        ]),
    ]

    @Environment(\.openURL) private var openURL

    var body: some View {
        ShellPage(title: "About and credits") {
            ShellCard {
                Text("BetterDriver").font(.headline).foregroundStyle(Palette.onBackground)
                DimText("Version \(AppInfo.version)")
            }
            ForEach(Self.sections, id: \.self) { section in
                ShellLabel(section.title)
                ShellCard {
                    ForEach(Array(section.credits.enumerated()), id: \.element) { i, credit in
                        VStack(spacing: 0) {
                            if i > 0 { Rectangle().fill(Palette.outline).frame(height: 1) }
                            Button { if let url = URL(string: credit.url) { openURL(url) } } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(credit.name).foregroundStyle(Palette.onBackground)
                                    if !credit.detail.isEmpty { DimText(credit.detail) }
                                    DimText(credit.license, color: Palette.accent)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.vertical, 10)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
    }
}

enum AppInfo {
    static var version: String {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let b = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        return "\(v) (build \(b))"
    }
}

enum Storage {
    /// "12 GB free": what this phone has left for sessions and video.
    static var freeLabel: String {
        let home = URL(fileURLWithPath: NSHomeDirectory())
        let values = try? home.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        let bytes = Double(values?.volumeAvailableCapacityForImportantUsage ?? 0)
        return "\(Int((bytes / 1_000_000_000).rounded())) GB free"
    }
}

/// A sheet's subject by id: Kotlin classes are not made Identifiable here.
struct SheetId: Identifiable {
    let id: String
}
