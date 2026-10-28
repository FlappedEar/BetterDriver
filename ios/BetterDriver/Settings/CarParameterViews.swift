import BetterDriverCore
import SwiftUI

/// An add row's look, for a NavigationLink's label: "+ text" in the accent colour, as AddRow.
struct AddRowLabel: View {
    let text: String
    var divider = true

    var body: some View {
        VStack(spacing: 0) {
            if divider { Rectangle().fill(Palette.outline).frame(height: 1) }
            Text("+ \(text)")
                .fontWeight(.semibold)
                .foregroundStyle(Palette.accent)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 12)
                .contentShape(Rectangle())
        }
    }
}

/// The mode 01 PIDs the car reported in its last scan (FBBD-344, UI-301 B), each with a switch to
/// record it; a PID the app has no formula for offers to add a parameter for it.
struct CarPidsPage: View {
    let carId: String
    @EnvironmentObject private var settings: SettingsModel

    var body: some View {
        let setup = settings.setup
        let core = CarPageCore.shared
        ShellPage(title: "OBD-II") {
            if let scan = setup.car(id: carId)?.scan {
                DimText("Reported by the car · scanned \(scanTime(scan.atMs))")
                let recorded = Set(core.recordedPids(setup: setup, carId: carId).map { $0.int32Value })
                let pids = core.reportedPids(scan: scan).map { $0.int32Value }
                ShellCard {
                    ForEach(Array(pids.enumerated()), id: \.element) { i, pid in
                        let code = "OBD-II \(core.pidCode(pid: pid))"
                        if let name = core.standardName(pid: pid) {
                            ListRow(
                                label: name, divider: i > 0, supporting: code,
                                trailing: {
                                    Toggle("", isOn: Binding(
                                        get: { recorded.contains(pid) },
                                        set: { on in
                                            settings.update { core.recordPid(setup: $0, carId: carId, pid: pid, on: on) }
                                        }
                                    ))
                                    .labelsHidden()
                                    .tint(Palette.accent)
                                    .accessibilityIdentifier("pid\(pid)")
                                }
                            )
                        } else {
                            NavigationLink {
                                ParameterSteps(carId: carId, key: nil, start: ParameterDraft.companion.pid(pid: pid))
                            } label: {
                                ListRow(
                                    "PID \(core.pidCode(pid: pid).dropFirst(3))", value: "Add", divider: i > 0,
                                    supporting: "\(code) · no formula in the app"
                                )
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                DimText(
                    "Switched on: recorded in every session with this car. Off: not asked, so the rest are read more "
                        + "often. Until a switch is turned, the app reads the pedals, RPM, temperatures and your tiles' values."
                )
            } else {
                DimText("Not scanned yet: Scan car on the car's page lists the PIDs the car reports.")
            }
        }
    }
}

/// The CAN ids heard at the car's OBD port in its last scan (FBBD-344, UI-301): how often each came,
/// which bytes moved, and what reads it; tapping one starts a new parameter on it.
struct CarCanPage: View {
    let carId: String
    @EnvironmentObject private var settings: SettingsModel

    var body: some View {
        let setup = settings.setup
        let core = CarPageCore.shared
        ShellPage(title: "CAN bus") {
            if let scan = setup.car(id: carId)?.scan {
                if scan.canHeard {
                    let full = scan.bufferFull ? " · the adapter dropped frames, so rates are low" : ""
                    DimText("Heard at the OBD port · scanned \(scanTime(scan.atMs))\(full)")
                    ShellCard {
                        ForEach(Array(scan.canIds.enumerated()), id: \.offset) { i, record in
                            let names = core.canIdNames(setup: setup, carId: carId, id: record.id)
                            let what = (names + [core.changingText(changing: record.changing)]).joined(separator: " · ")
                            NavigationLink {
                                ParameterSteps(carId: carId, key: nil, start: ParameterDraft.companion.can(canId: record.id))
                            } label: {
                                ListRow(
                                    "CAN \(core.canIdText(id: record.id))", value: names.isEmpty ? "Add" : "in use",
                                    divider: i > 0, supporting: "\(what) · \(core.rate(perSecond: record.perSecond))"
                                )
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    DimText("Tap an id to add a parameter on it, with the id filled in.")
                } else {
                    DimText("Nothing heard in the last scan: \(core.noCanReason(scan: scan)).")
                }
            } else {
                DimText("Not scanned yet: Scan car on the car's page lists the CAN ids at its port.")
            }
        }
    }
}

/// The fields of a parameter being added or changed, as typed.
private struct DraftFields {
    var source: CarParameterSource
    var canId: String
    var header: String
    var request: String
    var equation: String
    var unit: String
    var fast: Bool
    var name: String

    init(_ d: ParameterDraft) {
        source = d.source
        canId = d.canId
        header = d.header
        request = d.request
        equation = d.equation
        unit = d.unit
        fast = d.fast
        name = d.name
    }

    var draft: ParameterDraft {
        ParameterDraft(
            source: source, canId: canId, header: header, request: request, equation: equation, unit: unit,
            fast: fast, name: name
        )
    }
}

/// Adding or changing one of the driver's own parameters in three steps (FBBD-344, UI-302 B): where
/// it comes from, its formula with a value to check it by, and its name. [key] is nil when adding.
/// Trying it on the car comes with the iPhone's car link.
struct ParameterSteps: View {
    let carId: String
    let key: String?
    @EnvironmentObject private var settings: SettingsModel
    @Environment(\.dismiss) private var dismiss
    @State private var fields: DraftFields
    @State private var step = 0

    init(carId: String, key: String?, start: ParameterDraft) {
        self.carId = carId
        self.key = key
        _fields = State(initialValue: DraftFields(start))
    }

    var body: some View {
        let draft = fields.draft
        let frame = draft.source == .can
            ? CarPageCore.shared.scanFrame(setup: settings.setup, carId: carId, canId: draft.canIdValue)
            : nil
        ShellPage(title: key == nil ? "New parameter" : "Edit parameter") {
            switch step {
            case 0:
                DimText("Step 1 of 3 · Where it comes from")
                HStack(spacing: 8) {
                    sourceChip(.can, "CAN broadcast")
                    sourceChip(.obd, "OBD request")
                    Spacer()
                }
                if draft.source == .can {
                    hexField("CAN id", "for example 0D4", \.canId).accessibilityIdentifier("canId")
                    if let frame { FrameBytes(frame: frame) }
                    DimText("A frame the car sends by itself, read whenever it comes. 11-bit ids, 000 to 7FF.")
                } else {
                    hexField("Header (ECU)", "empty = every ECU", \.header)
                    hexField("Mode and PID", "for example 01 06 or 22 F190", \.request).accessibilityIdentifier("request")
                    DimText("A request the adapter sends; the ECU's answer is read with the formula.")
                }
                nextButton(draft.sourceValid) { step = 1 }
            case 1:
                DimText("Step 2 of 3 · Formula")
                TextField("Formula", text: $fields.equation)
                    .textFieldStyle(.roundedBorder)
                    .font(.body.monospaced())
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .accessibilityIdentifier("formula")
                DimText(
                    "A, B… are the data bytes, or raw, as in RaceChrono",
                    color: !fields.equation.isEmpty && !draft.equationValid ? Palette.loss : Palette.onSurfaceVariant
                )
                TextField("Unit", text: $fields.unit)
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()
                if draft.source == .obd {
                    ShellCard {
                        ListRow(
                            label: "Read often", divider: false, supporting: "As often as the pedals; off: like a temperature",
                            trailing: { Toggle("", isOn: $fields.fast).labelsHidden().tint(Palette.accent) }
                        )
                    }
                }
                if draft.source == .can, frame != nil {
                    ShellCard {
                        ListRow("On the scan's last frame", value: draft.valueOn(frame: frame) ?? "–", divider: false)
                    }
                }
                nextButton(draft.equationValid) { step = 2 }
                ShellTonalButton(text: "Back") { step = 0 }
            default:
                DimText("Step 3 of 3 · Name")
                TextField("Name", text: $fields.name)
                    .textFieldStyle(.roundedBorder)
                    .accessibilityIdentifier("parameterName")
                DimText("Recorded in every session with this car and in its VBO export.")
                nextButton(!fields.name.trimmingCharacters(in: .whitespaces).isEmpty, text: "Save") {
                    settings.update { CarPageCore.shared.saveParameter(setup: $0, carId: carId, key: key, draft: draft) }
                    dismiss()
                }
                ShellTonalButton(text: "Back") { step = 1 }
            }
            if let key {
                RemoveButton(text: "Remove parameter") {
                    settings.update { CarPageCore.shared.removeParameter(setup: $0, carId: carId, key: key) }
                    dismiss()
                }
            }
        }
    }

    private func sourceChip(_ source: CarParameterSource, _ label: String) -> some View {
        let on = fields.source == source
        return Button {
            fields.source = source
        } label: {
            Text(label)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(on ? Palette.onAccent : Palette.onBackground)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(on ? Palette.accent : Palette.surfaceVariant, in: Capsule())
        }
        .buttonStyle(.plain)
    }

    private func hexField(_ label: String, _ hint: String, _ field: WritableKeyPath<DraftFields, String>) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            DimText(label)
            TextField(hint, text: Binding(get: { fields[keyPath: field] }, set: { fields[keyPath: field] = $0.uppercased() }))
                .textFieldStyle(.roundedBorder)
                .font(.body.monospaced())
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
        }
    }

    private func nextButton(_ enabled: Bool, text: String = "Next", action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text)
                .fontWeight(.semibold)
                .foregroundStyle(Palette.onAccent)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(Palette.accent.opacity(enabled ? 1 : 0.4), in: Capsule())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityIdentifier(text == "Save" ? "saveParameter" : "nextStep")
    }
}

/// The last frame of the id heard in the scan, the bytes that moved lit.
private struct FrameBytes: View {
    let frame: CanIdRecord

    var body: some View {
        let changing = Set(frame.changing.map { $0.intValue })
        let bytes = stride(from: 0, to: frame.last.count - 1, by: 2).map { i -> String in
            let a = frame.last.index(frame.last.startIndex, offsetBy: i)
            return String(frame.last[a ..< frame.last.index(a, offsetBy: 2)])
        }
        HStack(spacing: 4) {
            ForEach(Array(bytes.enumerated()), id: \.offset) { i, b in
                Text(b)
                    .font(.caption.monospaced())
                    .foregroundStyle(Palette.onBackground)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
                    .background(
                        changing.contains(i) ? Palette.accent.opacity(0.35) : Palette.surfaceVariant,
                        in: RoundedRectangle(cornerRadius: 5)
                    )
            }
        }
        let core = CarPageCore.shared
        DimText(
            "Last frame in the scan, byte 0 first · \(core.changingText(changing: frame.changing)) · "
                + core.rate(perSecond: frame.perSecond)
        )
    }
}
