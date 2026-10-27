import AudioToolbox
import BetterDriverCore
import CoreLocation
import SwiftUI
import UIKit

/// The receiver a session records from, picked on the sheet at START (UI-285 B).
enum Receiver: Equatable {
    case raceBox(FoundRaceBoxDevice)
    case phone

    var name: String {
        switch self {
        case .raceBox(let device): device.name
        case .phone: "Phone GPS"
        }
    }
}

/// Where the app keeps its files (agreed with the iPad review): sessions in Application Support/sessions,
/// tracks in Application Support/tracks/<id>.json, as the Android app's folders.
enum AppFolders {
    static var base: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    }

    static var sessions: URL { folder("sessions") }
    static var tracks: URL { folder("tracks") }
    /// The coach's references from earlier sessions, as coach/references on Android (FBBD-333).
    static var coachReferences: URL { folder("coach/references") }

    private static func folder(_ name: String) -> URL {
        let url = base.appendingPathComponent(name, isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}

/// The circuit directory bundled with the app (timing's circuits.json, FBBD-79), read once.
enum BundledCircuits {
    static let list: CircuitList = {
        let url = Bundle.main.url(forResource: "circuits", withExtension: "json")
        let text = url.flatMap { try? String(contentsOf: $0, encoding: .utf8) }
        return TrackFiles.shared.circuits(json: text)
    }()
}

/// Runs one recording on the iPhone (FBBD-332), as the Android recorder service does: the session
/// with its live timing (LiveSession), the receiver's link feeding it, and the track screen's values
/// read four times a second. The screen stays on while recording.
@MainActor
final class RecordingController: ObservableObject {
    @Published private(set) var session: LiveSession?
    @Published private(set) var screen: TrackScreenModel?
    @Published private(set) var error: String?
    @Published private(set) var raceBoxState: RaceBoxLinkState = .off
    @Published private(set) var phoneState: PhoneGpsState = .off
    @Published private(set) var cameraStatus: CameraStatus?

    private var receiver: Receiver?
    private var raceBox: RaceBoxLink?
    private var phone: PhoneGpsLink?
    private var camera: PhoneCamera?
    private var obd: ObdLink?
    private var heartRate: HeartRateStrapLink?
    private var voice: CoachVoice?
    private var timer: Timer?
    /// The GNSS state at the last refresh, for the lost and back sounds.
    private var lastGnss: GnssState?
    private let activity = RecordingActivity()

    var isRecording: Bool { session != nil }

    /// The receiver of the next session as chosen in Settings (FBBD-338), or why it cannot record:
    /// the iPhone records from a RaceBox or its own GPS.
    static func receiver(_ setup: Setup) -> Result<Receiver, StartError> {
        guard let gnss = setup.active().gnss else { return .failure(.noReceiver) }
        switch gnss.modelName {
        case ModelName.phoneGps:
            return .success(.phone)
        case ModelName.raceBox:
            guard let id = gnss.address.flatMap(UUID.init(uuidString:)) else { return .failure(.notOnIphone(gnss.name)) }
            return .success(.raceBox(FoundRaceBoxDevice(id: id, name: gnss.name, rssi: 0)))
        default:
            return .failure(.notOnIphone(gnss.name))
        }
    }

    enum StartError: Error, Equatable {
        case noReceiver
        case notOnIphone(String)

        var text: String {
            switch self {
            case .noReceiver: "Add a GNSS receiver to start"
            case .notOnIphone(let name): "\(name) can't record on the iPhone. Choose a RaceBox or Phone GPS."
            }
        }
    }

    /// Starts the next session as Settings has it: its receiver, car, cameras, sensors and coach.
    func start(settings: SettingsModel) {
        switch Self.receiver(settings.setup) {
        case .success(let receiver): start(receiver, settings: settings)
        case .failure(let problem): error = problem.text
        }
    }

    private func start(_ receiver: Receiver, settings: SettingsModel) {
        guard session == nil else { return }
        error = nil
        let setup = settings.setup
        let active = setup.active()
        let info = SessionInfo(
            startUtcMillis: Int64(Date().timeIntervalSince1970 * 1_000),
            car: active.car?.name ?? "",
            track: "",
            devices: [receiver.name],
            startFinish: nil,
            channels: [],
            imported: nil
        )
        let live: LiveSession
        do {
            live = try LiveSession.companion.start(
                sessionsDir: AppFolders.sessions.path,
                tracksDir: AppFolders.tracks.path,
                circuits: BundledCircuits.list,
                info: info,
                receiverHasG: receiver != .phone
            )
        } catch {
            self.error = "Could not start recording: \(error.localizedDescription)"
            return
        }
        self.receiver = receiver
        session = live
        switch receiver {
        case .raceBox(let device):
            let link = RaceBoxLink(deviceId: device.id, deviceName: device.name, sink: live) { [weak self] state in
                self?.raceBoxState = state
            }
            raceBox = link
            link.start()
        case .phone:
            let link = PhoneGpsLink(sink: live) { [weak self] state in
                self?.phoneState = state
                // Without location access the phone records nothing: end the session and say why.
                if state == .notAllowed, let self, self.session != nil {
                    self.stop()
                    self.error = "Location access is off. Allow it in Settings › BetterDriver › Location."
                }
            }
            phone = link
            link.start()
        }
        // The link can end the session as it starts (phone GPS without location access).
        guard session != nil else { return }
        // OBD-II from the Bluetooth LE adapters in Settings (FBBD-333, FBBD-338); with none listed, one nearby.
        let car = ObdLink(sink: live, listed: Self.listed(active.carData, ModelName.obdBluetooth))
        obd = car
        car.start()
        // The heart-rate strap records alongside whichever receiver, as on Android (FBBD-333).
        let strap = HeartRateStrapLink(sink: live, listed: Self.listed(active.sensors, ModelName.heartRate).first)
        heartRate = strap
        strap.start()
        startCoach(live, settings.coach)
        if SettingsCore.shared.phoneCameraOn(setup: setup) {
            let mode = PhoneVideoMode.named(SettingsCore.shared.phoneVideoLabel(setup: setup))
            let cam = PhoneCamera(log: PhoneVideoLog(session: live), mode: mode) { [weak self] in self?.cameraStatus = $0 }
            camera = cam
            cam.start()
        }
        UIApplication.shared.isIdleTimerDisabled = true
        activity.start(at: Date())
        refresh()
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.refresh() }
        }
    }

    /// Settings items of [model] that iOS reaches by their Bluetooth identifier.
    private static func listed(_ items: [Equipment], _ model: String) -> [(id: UUID, name: String)] {
        items.compactMap { item in
            guard item.modelName == model, let id = item.address.flatMap(UUID.init(uuidString:)) else { return nil }
            return (id, item.name)
        }
    }

    /// The live coach (FBBD-333) as Settings › Spoken coach has it (FBBD-338), unless it is off; its cues
    /// are timed for the output delay iOS reports now, else for the delay set there.
    private func startCoach(_ live: LiveSession, _ coach: CoachSettings) {
        guard SettingsCore.shared.coachOn(s: coach) else { return }
        let phoneDelay = CoachVoice.outputDelayMillis
        let settings = SettingsCore.shared.sessionCoach(s: coach, phoneDelayMillis: Int32(phoneDelay))
        let voice = CoachVoice(settings: settings)
        self.voice = voice
        live.startCoach(
            sessionsDir: AppFolders.sessions.path,
            referencesDir: AppFolders.coachReferences.path,
            settings: settings,
            audioDelayFromPhone: phoneDelay > 0,
            speaker: voice
        )
    }

    /// The hold on the track screen (UI-16): ends the session cleanly and closes the screen.
    func stop() {
        guard let live = session else { return }
        raceBox?.stop()
        phone?.stop()
        camera?.stop()
        obd?.stop()
        heartRate?.stop()
        _ = live.stop()
        end()
    }

    private func end() {
        activity.end()
        timer?.invalidate()
        timer = nil
        voice?.shutdown()
        voice = nil
        raceBox = nil
        phone = nil
        camera?.stop()
        camera = nil
        cameraStatus = nil
        obd = nil
        heartRate = nil
        session = nil
        lastGnss = nil
        screen = nil
        receiver = nil
        raceBoxState = .off
        phoneState = .off
        UIApplication.shared.isIdleTimerDisabled = false
    }

    /// A short sound when GNSS goes from OK to lost and another when it is back (FBBD-64), when
    /// Settings › Track screen has it on, as on Android (GnssSound, gnssCue).
    private func gnssSound(_ now: GnssState) {
        defer { lastGnss = now }
        guard SettingsModel.shared.gnssSound, let before = lastGnss else { return }
        if before == GnssState.ok && now == GnssState.lost {
            AudioServicesPlaySystemSound(1053)
        } else if before == GnssState.lost && now == GnssState.ok {
            AudioServicesPlaySystemSound(1054)
        }
    }

    private func refresh() {
        guard let live = session, let receiver else { return }
        let now = live.screen()
        if let reason = now.stoppedReason, reason != "user" {
            // Ended by the recorder itself (storage full, write error): the files stay for recovery.
            raceBox?.stop()
            phone?.stop()
            obd?.stop()
            heartRate?.stop()
            end()
            error = reason == "storage_full" ? "Recording stopped: storage full" : "Recording stopped: write error"
            return
        }
        gnssSound(now.gnss)
        let obdOn = obd?.status().state == ObdLinkState.connected
        let model = TrackScreenModel(
            now, receiverHasG: receiver != .phone, obdConnected: obdOn, heartRate: heartRate?.status, camera: cameraStatus
        )
        screen = model
        activity.update(model)
    }
}

/// Everything the track screen shows, from one LiveScreen; the Android app's liveTrackState.
struct TrackScreenModel: Equatable {
    struct Status: Equatable, Identifiable {
        let label: String
        let value: String
        let ok: Bool
        /// An SF Symbol in place of the dot and the label (UI-110 B); the label is then only read out.
        var icon: String? = nil
        /// The value stays on the quiet strip while ok, as for the heart rate (UI-141 B).
        var keepValue = false
        var id: String { label }
    }

    struct Tile: Equatable {
        let label: String
        let value: String
        let unit: String
        /// No value now: drawn dim, as the delta's dash.
        let dim: Bool
    }

    /// The coach's last cue with its corner, or whether it is learning (CoachStatus).
    struct Coach: Equatable {
        let corner: String?
        let text: String?
        /// A praise or a cue that worked, drawn green; a cue not done yet, grey (UI-173 A).
        let praise: Bool
        let dim: Bool
    }

    struct Alert: Equatable {
        let title: String
        let detail: String
        let critical: Bool
    }

    let status: [Status]
    /// The coach line (FBBD-98); nil when the coach is off, so the tiles take its place (UI-142 B).
    let coach: Coach?
    let recordingTime: String
    let alert: Alert?
    let deltaSeconds: Double?
    let lapNumber: Int?
    let lapMillis: Int64?
    let lastMillis: Int64?
    let bestMillis: Int64?
    /// The row of six slots (UI-120 A) with the Android defaults, speed and G; nil is an empty slot,
    /// drawn as an outline (UI-125 B). A tile that cannot have data here is left out (UI-126 B).
    let tiles: [Tile?]

    static let noValue = "–"
    static let slots = 6

    /// [obdConnected]: OBD data arrives, shown as the car icon (FBBD-268, UI-110 B). [heartRate] is the
    /// strap's, when one records (FBBD-333). [camera]: the phone camera's line, when it records video.
    init(_ s: LiveScreen, receiverHasG: Bool, obdConnected: Bool = false, heartRate: HeartRateStatus? = nil, camera: CameraStatus? = nil) {
        let live = s.live
        let ok = s.gnss == GnssState.ok
        let gnss: Status = switch s.gnss {
        case GnssState.waiting: Status(label: "GNSS", value: "Waiting", ok: false)
        case GnssState.lost: Status(label: "GNSS", value: "Lost", ok: false)
        default: Status(label: "GNSS", value: "\(s.shownRateHz) Hz · \(live.sats.map { "\($0.intValue)" } ?? Self.noValue) sats", ok: true)
        }
        // The quiet strip (UI-141 B) names the track only when a saved track has no timing.
        let place = s.place.flatMap { name in
            s.placeSaved && s.timing == nil ? Status(label: "Track", value: name, ok: false) : nil
        }
        let obd = obdConnected ? Status(label: "OBD", value: "Connected", ok: true, icon: "car.fill") : nil
        // Heart rate (FBBD-268): shown only while it arrives, the strap's last value, never older than 5 s.
        let heart = heartRate?.bpm.map { bpm in
            Status(label: "Heart rate", value: "\(bpm.intValue)", ok: true, icon: "heart.fill", keepValue: true)
        }
        // Phone video, when the camera reports (UI-07): green while it records, amber when it does not.
        let video = camera.map { Status(label: "Camera", value: $0.text, ok: $0.ok) }
        status = [place, gnss, video, obd, heart].compactMap { $0 }
        coach = s.coach.map { Coach(corner: $0.corner, text: $0.text, praise: $0.praise, dim: $0.dim) }
        recordingTime = Self.clock(micros: live.elapsedMicros)
        if s.gnss == GnssState.lost {
            alert = Alert(title: "GNSS signal lost", detail: "Recording continues", critical: true)
        } else if let low = live.storageLowBytes {
            alert = Alert(title: "Storage low", detail: "\(low.int64Value / 1_000_000) MB free", critical: false)
        } else {
            alert = nil
        }
        let timing = s.timing
        deltaSeconds = timing?.deltaSeconds?.doubleValue
        lapNumber = timing.map { Int($0.lapNumber) }
        lapMillis = timing?.lapMillis?.int64Value
        lastMillis = timing?.lastMillis?.int64Value
        bestMillis = timing?.bestMillis?.int64Value
        // The receiver's last speed stays known after the fix is lost; it is not the car's speed now.
        let speed = ok ? live.speedKmh.map { "\(Int($0.doubleValue.rounded()))" } : nil
        let g = live.gCombined.map { String(format: "%.2f", $0.doubleValue) }
        let speedTile = Tile(label: "Speed", value: speed ?? Self.noValue, unit: "km/h", dim: speed == nil)
        let gTile = Tile(label: "G", value: g ?? Self.noValue, unit: "g", dim: g == nil)
        let filled: [Tile?] = receiverHasG ? [speedTile, gTile] : [speedTile]
        tiles = filled + Array(repeating: nil, count: Self.slots - 2)
    }

    /// Recording time as m:ss, or h:mm:ss from one hour (formatClock).
    static func clock(micros: Int64) -> String {
        let s = max(micros, 0) / 1_000_000
        let h = s / 3_600, m = (s % 3_600) / 60, sec = s % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%d:%02d", m, sec)
    }
}
