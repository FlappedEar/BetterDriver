import AudioToolbox
import AVFoundation
import BetterDriverCore
import CoreLocation
import SwiftUI
import UIKit

/// The receiver a session records from, picked on the sheet at START (UI-285 B).
enum Receiver: Equatable {
    case raceBox(FoundRaceBoxDevice)
    case phone
    /// The UI smoke test's receiver (UITestMode).
    case simulated

    var name: String {
        switch self {
        case .raceBox(let device): device.name
        case .phone: "Phone GPS"
        case .simulated: "Simulated GPS"
        }
    }

    /// A RaceBox measures G itself; otherwise the phone's accelerometer gives it (FBBD-337).
    var hasG: Bool {
        if case .raceBox = self { return true }
        return false
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
    /// The line plans the review keeps for the coach, as coach/lines on Android (FBBD-333).
    static var coachLines: URL { folder("coach/lines") }

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
    private var simulated: SimulatedGpsLink?
    private var imu: PhoneImuLink?
    private var camera: PhoneCamera?
    /// The GNSS state at the last refresh, for the lost and back sounds.
    private var lastGnss: GnssState?
    /// The main screen's tiles of the session's car (FBBD-345), as Settings had them at the start.
    private var tileIds = TrackScreenModel.defaultTiles
    /// The second screen's tiles of the session's car (FBBD-262), as Settings had them at the start.
    private var secondTileIds: [String] = []
    /// The Open GoPro cameras turned on for the session (FBBD-343).
    private var goPros: OpenGoPros?
    private var obd: ObdLink?
    private var heartRate: HeartRateStrapLink?
    private var voice: CoachVoice?
    private var timer: Timer?
    private let activity = RecordingActivity()

    var isRecording: Bool { session != nil }

    init() {
        // A Live Activity left by a session the app died in would count on the lock screen.
        RecordingActivity.endLeftOver()
        // Swiped away while recording: close the session's files and its Live Activity as a stop does.
        NotificationCenter.default.addObserver(
            forName: UIApplication.willTerminateNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.stop() }
        }
    }

    /// The RaceBox used last, so the sheet can mark it (UI-285 B).
    var lastReceiverName: String {
        get { UserDefaults.standard.string(forKey: "lastReceiverName") ?? "" }
        set { UserDefaults.standard.set(newValue, forKey: "lastReceiverName") }
    }

    /// Phone video with each session (FBBD-21), turned on on the START sheet. Off until the iPhone
    /// camera has a device test: a new key, so a camera left on by an earlier build starts off too.
    var phoneCamera: Bool {
        get { UserDefaults.standard.object(forKey: "phoneCameraOn") as? Bool ?? false }
        set { UserDefaults.standard.set(newValue, forKey: "phoneCameraOn") }
    }

    /// The phone video mode (FBBD-88), as on Android: 1080p · 60 until another is chosen.
    var phoneVideoMode: PhoneVideoMode {
        get { PhoneVideoMode.named(UserDefaults.standard.string(forKey: "phoneVideoMode")) }
        set { UserDefaults.standard.set(newValue.label, forKey: "phoneVideoMode") }
    }

    func start(_ receiver: Receiver) {
        guard session == nil else { return }
        error = nil
        let info = SessionInfo(
            startUtcMillis: Int64(Date().timeIntervalSince1970 * 1_000),
            car: SettingsModel.shared.setup.active().car?.name ?? "",
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
                receiverHasG: receiver.hasG
            )
        } catch {
            self.error = "Could not start recording: \(error.localizedDescription)"
            return
        }
        self.receiver = receiver
        let setup = SettingsModel.shared.setup
        tileIds = TrackTiles.shared.ids(setup: setup, carId: setup.session.carId, second: false)
        secondTileIds = TrackTiles.shared.ids(setup: setup, carId: setup.session.carId, second: true)
        session = live
        AppLog.session = live.id
        AppLog.i("Recording", "start receiver=\(receiver.name) camera=\(phoneCamera)")
        // The phone's accelerometer records in every session, as on Android; with phone GPS it gives the G.
        let imu = PhoneImuLink(sink: live)
        self.imu = imu
        imu.start()
        switch receiver {
        case .raceBox(let device):
            lastReceiverName = device.name
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
        case .simulated:
            let link = SimulatedGpsLink(sink: live)
            simulated = link
            link.start()
        }
        // The link can end the session as it starts (phone GPS without location access).
        guard session != nil else { return }
        let active = SettingsModel.shared.setup.active()
        // OBD-II from the Bluetooth LE adapters in Settings (FBBD-333, FBBD-338), with none listed one
        // nearby, and the Wi-Fi adapter.
        let car = ObdLink(
            sink: live, listed: Self.listed(active.carData, ModelName.obdBluetooth),
            setup: SettingsModel.shared.setup, tiles: tileIds + secondTileIds
        )
        obd = car
        car.start()
        // The heart-rate strap records alongside whichever receiver, as on Android (FBBD-333); only a
        // listed one.
        if let listed = Self.listed(active.sensors, ModelName.heartRate).first {
            let strap = HeartRateStrapLink(sink: live, listed: listed)
            heartRate = strap
            strap.start()
        }
        startCoach(live, SettingsModel.shared.coach)
        if phoneCamera {
            let cam = PhoneCamera(log: PhoneVideoLog(session: live), mode: phoneVideoMode) { [weak self] in self?.cameraStatus = $0 }
            camera = cam
            cam.start()
        }
        startGoPros(live)
        UIApplication.shared.isIdleTimerDisabled = true
        activity.start(at: Date())
        refresh()
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.refresh() }
        }
    }

    /// Settings items of [model] that iOS reaches by their Bluetooth identifier.
    /// The session's OBD-II connection as the car data page shows it (FBBD-261), nil while none records.
    func obdStatus() -> ObdStatus? { obd?.status() }

    static func listed(_ items: [Equipment], _ model: String) -> [(id: UUID, name: String)] {
        items.compactMap { item in
            guard item.modelName == model, let id = item.address.flatMap(UUID.init(uuidString:)) else { return nil }
            return (id, item.name)
        }
    }

    /// The live coach (FBBD-333) as Settings › Spoken coach has it (FBBD-338), unless it is off; its cues
    /// are timed for the output delay iOS reports now, else for the delay set there.
    /// What the session's coach runs with: Settings › Spoken coach with the delay iOS reports, or nil
    /// when the coach is off, so nothing speaks.
    static func sessionCoach(_ coach: CoachSettings, phoneDelayMillis: Int) -> CoachSettings? {
        guard SettingsCore.shared.coachOn(s: coach) else { return nil }
        return SettingsCore.shared.sessionCoach(s: coach, phoneDelayMillis: Int32(phoneDelayMillis))
    }

    private func startCoach(_ live: LiveSession, _ coach: CoachSettings) {
        let phoneDelay = CoachVoice.outputDelayMillis
        guard let settings = Self.sessionCoach(coach, phoneDelayMillis: phoneDelay) else {
            AppLog.i("CoachService", "coach off")
            return
        }
        let voice = CoachVoice(settings: settings)
        self.voice = voice
        // For the headset delay test (FBBD-49), Android's lines (FBBD-354, FBBD-355): the output's delay
        // and what the cues are timed for.
        AppLog.i("OutputDelay", CoachVoice.delayLine)
        AppLog.i(
            "CoachService",
            "coaching session \(live.id): \(settings), audio delay \(phoneDelay > 0 ? "measured" : "set") on \(CoachVoice.output.type)"
        )
        // A headset that connects or drops mid-session: the cues are timed for the new output, or for the
        // delay set in Settings when iOS reports none.
        let setDelay = coach.audioDelayMillis
        voice.onDelay = { [weak live] ms, output in
            live?.audioDelay(millis: Int32(ms > 0 ? ms : Int(setDelay)), measured: ms > 0, output: output)
        }
        live.startCoach(
            sessionsDir: AppFolders.sessions.path,
            referencesDir: AppFolders.coachReferences.path,
            linePlansDir: AppFolders.coachLines.path,
            settings: settings,
            audioDelayFromPhone: phoneDelay > 0,
            speaker: voice
        )
    }

    /// Starts recording on every Open GoPro camera turned on for this session, as Android's GoPros;
    /// one that is not there fails on its own and the session records on.
    private func startGoPros(_ live: LiveSession) {
        let on = OpenGoPros.companion.listed(setup: SettingsModel.shared.setup).filter(\.on)
        guard !on.isEmpty else { return }
        let cameras = OpenGoPros()
        goPros = cameras
        for goPro in on {
            AppLog.i("GoPro", "start \(goPro.sourceId) mode=\(goPro.video?.label ?? "camera's own")")
            cameras.start(session: live, goPro: goPro, link: GoProBleLink(address: goPro.address))
        }
    }

    /// The hold on the track screen (UI-16): ends the session cleanly and closes the screen.
    func stop() {
        guard let live = session else { return }
        raceBox?.stop()
        phone?.stop()
        simulated?.stop()
        imu?.stop()
        camera?.stop()
        obd?.stop()
        heartRate?.stop()
        _ = live.stop()
        AppLog.i("Recording", "stop")
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
        simulated = nil
        imu = nil
        camera?.stop()
        camera = nil
        // Gives the other apps' audio back also when no coach spoke: the phone camera's microphone
        // activates the audio session too.
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        cameraStatus = nil
        // The cameras answer in their own time; the stop is noted in the ended session's events.
        goPros?.stop()
        goPros = nil
        obd = nil
        heartRate = nil
        session = nil
        lastGnss = nil
        AppLog.session = nil
        screen = nil
        receiver = nil
        raceBoxState = .off
        phoneState = .off
        UIApplication.shared.isIdleTimerDisabled = false
        // The session is on disk: the Sessions list reads it again.
        NotificationCenter.default.post(name: .sessionsChanged, object: nil)
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
        guard let live = session else { return }
        let now = live.screen()
        if let reason = now.stoppedReason, reason != "user" {
            // Ended by the recorder itself (storage full, write error): the files stay for recovery.
            raceBox?.stop()
            phone?.stop()
            simulated?.stop()
            imu?.stop()
            obd?.stop()
            heartRate?.stop()
            end()
            error = reason == "storage_full" ? "Recording stopped: storage full" : "Recording stopped: write error"
            return
        }
        gnssSound(now.gnss)
        let obdOn = obd?.status().state == ObdLinkState.connected
        // A RaceBox's accelerometer or, with phone GPS, the phone's own (FBBD-337): G either way.
        let model = TrackScreenModel(
            now, receiverHasG: true, obdConnected: obdOn, heartRate: heartRate?.status, camera: cameraStatus,
            tileIds: tileIds, secondTileIds: secondTileIds
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
        /// The channel can have data in this session; one that cannot is left out while driving (UI-126 B).
        var hasData = true
    }

    /// The car's acceleration in g: [forward] positive speeding up, negative braking; [left] positive
    /// in a left turn (RadarPoint).
    struct RadarPoint: Equatable {
        let forward: Double
        let left: Double
    }

    /// The G radar's dot and its trail of the half second before it, oldest first (FBBD-318).
    struct Radar: Equatable {
        let now: RadarPoint
        let trail: [RadarPoint]
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
    /// The row of six slots (UI-120 A) of the car's layout (FBBD-345), by default speed and G; nil is an
    /// empty slot, drawn as an outline (UI-125 B). A tile that cannot have data here is left out (UI-126 B).
    let tiles: [Tile?]
    /// The second screen's slots (UI-121 B), three rows of four, or with the G radar the first nine in
    /// three rows of three (UI-240 A); each keeps whether it has data, so a row can drop it (shownRows).
    let secondTiles: [Tile?]
    /// The second screen shows the G radar (FBBD-318): the session's receiver, or the phone, gives G.
    let radarOn: Bool
    /// The radar's dot and trail; nil before the first reading.
    let radar: Radar?
    /// Every tile is drawn, one with no data too, so each can be tapped (Settings › Track screen).
    let editable: Bool

    /// How many screens a swipe goes through: the main one and the second (UI-121).
    let pages = 2

    /// UI-121 B: three rows of four on the second screen.
    static let secondColumns = 4
    static let secondRows = 3
    /// UI-240 A: with the G radar, the second screen's first nine tiles, three rows of three.
    static let radarColumns = 3

    static let noValue = TrackTiles.shared.NO_VALUE
    /// A fresh install's main screen: speed, G and four empty slots.
    static let defaultTiles = TrackTiles.shared.ids(setup: SettingsCore.shared.freshSetup(id: "default"), carId: nil, second: false)

    /// [obdConnected]: OBD data arrives, shown as the car icon (FBBD-268, UI-110 B). [heartRate] is the
    /// strap's, when one records (FBBD-333). [camera]: the phone camera's line, when it records video.
    /// [tileIds]: the main screen of the session's car (TrackTiles.ids), "" for an empty slot;
    /// [secondTileIds] its second screen.
    init(
        _ s: LiveScreen, receiverHasG: Bool, obdConnected: Bool = false, heartRate: HeartRateStatus? = nil,
        camera: CameraStatus? = nil, tileIds: [String] = TrackScreenModel.defaultTiles, secondTileIds: [String] = []
    ) {
        let live = s.live
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
        // The car's layout (FBBD-345): a tile that cannot have data here is left out (UI-126 B).
        tiles = tileIds
            .map { TrackTiles.shared.tile(id: $0, live: live, timing: timing, receiverHasG: receiverHasG) }
            .filter { $0?.hasData != false }
            .map { $0.map { Tile(label: $0.label, value: $0.value, unit: $0.unit, dim: $0.value == Self.noValue) } }
        secondTiles = secondTileIds
            .map { TrackTiles.shared.tile(id: $0, live: live, timing: timing, receiverHasG: receiverHasG) }
            .map { $0.map { Tile(label: $0.label, value: $0.value, unit: $0.unit, dim: $0.value == Self.noValue, hasData: $0.hasData) } }
        // The radar shows with a receiver that gives G, as the G tile does (UI-126 B).
        radarOn = receiverHasG
        radar = Self.radar(now: live.g, trail: live.gTrail)
        editable = false
    }

    /// The track screen as Settings › Track screen draws it (layoutTrackState, FBBD-345): no session, so
    /// a dash for every value, and every tile drawn, one without data too, so each can be tapped.
    init(layout tileIds: [String], secondIds: [String] = [], receiverHasG: Bool, coachOn: Bool) {
        status = []
        coach = coachOn ? Coach(corner: nil, text: nil, praise: false, dim: false) : nil
        recordingTime = Self.clock(micros: 0)
        alert = nil
        deltaSeconds = nil
        lapNumber = nil
        lapMillis = nil
        lastMillis = nil
        bestMillis = nil
        tiles = tileIds
            .map { TrackTiles.shared.tile(id: $0, live: nil, timing: nil, receiverHasG: receiverHasG) }
            .map { $0.map { Tile(label: $0.label, value: $0.value, unit: $0.unit, dim: true) } }
        secondTiles = secondIds
            .map { TrackTiles.shared.tile(id: $0, live: nil, timing: nil, receiverHasG: receiverHasG) }
            .map { $0.map { Tile(label: $0.label, value: $0.value, unit: $0.unit, dim: true) } }
        radarOn = receiverHasG
        radar = nil
        editable = true
    }

    /// The G radar's dot and trail (radarOf), or nil before the first G.
    static func radar(now: GPoint?, trail: [GPoint]) -> Radar? {
        guard let now else { return nil }
        let point = { (p: GPoint) in RadarPoint(forward: p.forward, left: p.left) }
        return Radar(now: point(now), trail: trail.map(point))
    }

    /// The second screen's tiles: the first nine beside the radar, else all twelve.
    var secondShown: [Tile?] { radarOn ? Array(secondTiles.prefix(Self.radarColumns * Self.secondRows)) : secondTiles }

    /// The second screen's columns: three beside the radar, else four.
    var secondShownColumns: Int { radarOn ? Self.radarColumns : Self.secondColumns }

    /// The slots each row of [tiles] shows, rows with none left out (shownRows): while driving a tile
    /// that cannot have data is left out and the row's others share its width; an empty slot stays.
    static func shownRows(_ tiles: [Tile?], columns: Int, rows: Int, editing: Bool) -> [[Int]] {
        (0..<rows).compactMap { r in
            guard r * columns < tiles.count else { return nil }
            let slots = (r * columns..<min((r + 1) * columns, tiles.count))
                .filter { tiles[$0]?.hasData != false || editing }
            return slots.isEmpty ? nil : slots
        }
    }

    /// Recording time as m:ss, or h:mm:ss from one hour (formatClock).
    static func clock(micros: Int64) -> String {
        let s = max(micros, 0) / 1_000_000
        let h = s / 3_600, m = (s % 3_600) / 60, sec = s % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%d:%02d", m, sec)
    }
}
