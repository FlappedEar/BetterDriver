package com.flappedear.betterdriver.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.flappedear.betterdriver.core.ui.TrackTheme
import com.flappedear.betterdriver.obd.ObdReading
import com.flappedear.betterdriver.obd.valueText
import com.flappedear.betterdriver.setup.CanIdRecord
import com.flappedear.betterdriver.setup.CarParameterSource
import com.flappedear.betterdriver.setup.CarProfile
import java.util.Locale

/**
 * The PIDs the car reported in its last scan (FBBD-344, UI-301 B), each with its live value and a
 * switch: on, it is recorded in every session with the car; off, it is not asked, so the others are
 * read more often. [recorded] are the PIDs on. A PID the app has no formula for offers Add, which opens
 * a new parameter on it ([onAdd]).
 */
@Suppress("LongParameterList") // One callback per action on the page.
@Composable
fun CarPidsPage(
    car: CarProfile,
    recorded: Set<Int>,
    readings: List<ObdReading>,
    onSwitch: (pid: Int, on: Boolean) -> Unit,
    onAdd: (pid: Int) -> Unit,
    onBack: () -> Unit,
) {
    val scan = car.scan
    SubPage("OBD-II", onBack) {
        if (scan == null) {
            DimText("Not scanned yet: Scan car on the car's page lists the PIDs the car reports.")
            return@SubPage
        }
        DimText("Reported by the car · scanned ${scanTime(scan.atMs)}")
        val live = readings.associateBy { it.id }

        val pids = reportedPids(scan)
        ShellCard {
            pids.forEachIndexed { i, pid ->
                val name = standardName(pid)
                val code = "OBD-II 01 ${"%02X".format(Locale.ROOT, pid)}"
                if (name == null) {
                    ListRow(
                        "PID ${"%02X".format(Locale.ROOT, pid)}",
                        "Add",
                        divider = i > 0,
                        onClick = { onAdd(pid) },
                        supporting = "$code · no formula in the app",
                    )
                } else {
                    val value = standardId(pid)?.let { live[it] }?.valueText()
                    ListRow(name, value, divider = i > 0, supporting = code) {
                        Switch(checked = pid in recorded, onCheckedChange = { on -> onSwitch(pid, on) })
                    }
                }
            }
        }
        DimText(
            "Switched on: recorded in every session with this car. Off: not asked, so the rest are read more " +
                "often. Until a switch is turned, the app reads the pedals, RPM, temperatures and your tiles' values.",
        )
    }
}

/**
 * The CAN ids heard at the car's OBD port in its last scan (FBBD-344, UI-301): how often each came,
 * which bytes moved, and what reads it; tapping one starts a new parameter on it ([onAdd]).
 */
@Composable
fun CarCanPage(car: CarProfile, frsCar: Boolean, onAdd: (canId: Int) -> Unit, onBack: () -> Unit) {
    val scan = car.scan
    SubPage("CAN bus", onBack) {
        when {
            scan == null -> DimText("Not scanned yet: Scan car on the car's page lists the CAN ids at its port.")
            !scan.canHeard -> DimText("Nothing heard in the last scan: ${noCanReason(scan)}.")
            else -> {
                val full = if (scan.bufferFull) " · the adapter dropped frames, so rates are low" else ""
                DimText("Heard at the OBD port · scanned ${scanTime(scan.atMs)}$full")
                ShellCard {
                    scan.canIds.forEachIndexed { i, id ->
                        val names = canIdNames(car, id.id, frsCar)
                        val what = (names + changingText(id.changing)).joinToString(" · ")
                        ListRow(
                            "CAN ${hex3(id.id)}",
                            if (names.isEmpty()) "Add" else "in use",
                            divider = i > 0,
                            onClick = { onAdd(id.id) },
                            supporting = "$what · ${rate(id.perSecond)}",
                        )
                    }
                }
                DimText("Tap an id to add a parameter on it, with the id filled in.")
            }
        }
    }
}

/** Which step of adding a parameter is shown (UI-302 B). */
enum class ParameterStep { Source, Formula, Name }

/**
 * Adding or changing one of the driver's own parameters in three steps (FBBD-344, UI-302 B): where
 * it comes from, its formula with a value to check it by, and its name. [frame] is the last frame
 * the scan heard of the draft's CAN id; [live] the value the car sends for the draft while it is
 * being tried on the car ([onTry], null when the adapter cannot be reached now). [onRemove] is null
 * when adding.
 */
@Suppress("LongParameterList", "LongMethod") // One callback per action; the three steps in one place.
@Composable
fun ParameterSteps(
    draft: ParameterDraft,
    step: ParameterStep,
    frame: CanIdRecord?,
    live: ObdReading?,
    trying: Boolean,
    onChange: (ParameterDraft) -> Unit,
    onStep: (ParameterStep) -> Unit,
    onTry: (() -> Unit)?,
    onSave: () -> Unit,
    onRemove: (() -> Unit)?,
    onBack: () -> Unit,
) {
    val title = if (onRemove == null) "New parameter" else "Edit parameter"
    SubPage(title, onBack) {
        when (step) {
            ParameterStep.Source -> {
                DimText("Step 1 of 3 · Where it comes from")
                SourceChoice(draft.source) { onChange(draft.copy(source = it)) }
                if (draft.source == CarParameterSource.CAN) {
                    HexField("CAN id", draft.canId, "for example 0D4") { onChange(draft.copy(canId = it)) }
                    if (frame != null) FrameBytes(frame)
                    DimText("A frame the car sends by itself, read whenever it comes. 11-bit ids, 000 to 7FF.")
                } else {
                    HexField("Header (ECU)", draft.header, "empty = every ECU") { onChange(draft.copy(header = it)) }
                    HexField("Mode and PID", draft.request, "for example 01 06 or 22 F190") {
                        onChange(draft.copy(request = it))
                    }
                    DimText("A request the adapter sends; the ECU's answer is read with the formula.")
                }
                NextButton(draft.sourceValid) { onStep(ParameterStep.Formula) }
            }
            ParameterStep.Formula -> {
                DimText("Step 2 of 3 · Formula")
                OutlinedTextField(
                    value = draft.equation,
                    onValueChange = { onChange(draft.copy(equation = it)) },
                    label = { Text("Formula") },
                    supportingText = { Text("A, B… are the data bytes, or raw, as in RaceChrono") },
                    isError = draft.equation.isNotBlank() && !draft.equationValid,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.unit,
                    onValueChange = { onChange(draft.copy(unit = it)) },
                    label = { Text("Unit") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (draft.source == CarParameterSource.OBD) {
                    val often = "As often as the pedals; off: like a temperature"
                    ListRow("Read often", divider = false, supporting = often) {
                        Switch(checked = draft.fast, onCheckedChange = { onChange(draft.copy(fast = it)) })
                    }
                }
                ValueCheck(draft, frame, live, trying, onTry)
                NextButton(draft.equationValid) { onStep(ParameterStep.Name) }
            }
            ParameterStep.Name -> {
                DimText("Step 3 of 3 · Name")
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { onChange(draft.copy(name = it)) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                DimText("Recorded in every session with this car and in its VBO export.")
                Button(onClick = onSave, enabled = draft.name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text("Save")
                }
            }
        }
        if (onRemove != null) {
            OutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                Text("Remove parameter", color = TrackTheme.colors.loss)
            }
        }
    }
}

@Composable
private fun SourceChoice(source: CarParameterSource, onPick: (CarParameterSource) -> Unit) {
    val options = listOf(CarParameterSource.CAN to "CAN broadcast", CarParameterSource.OBD to "OBD request")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (s, label) ->
            FilterChip(selected = s == source, onClick = { onPick(s) }, label = { Text(label) })
        }
    }
}

@Composable
private fun HexField(label: String, value: String, hint: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.uppercase()) },
        label = { Text(label) },
        placeholder = { Text(hint) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The last frame of the id heard in the scan, the bytes that moved lit. */
@Composable
private fun FrameBytes(frame: CanIdRecord) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        frame.last.chunked(2).forEachIndexed { i, b ->
            val lit = i in frame.changing
            Box(
                Modifier
                    .weight(1f)
                    .background(
                        if (lit) {
                            MaterialTheme.colorScheme.primary.copy(alpha = LitAlpha)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        RoundedCornerShape(5.dp),
                    )
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(b, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        }
    }
    DimText("Last frame in the scan, byte 0 first · ${changingText(frame.changing)} · ${rate(frame.perSecond)}")
}

/** The formula's value, to check it: on the scan's last frame, and from the car once tried. */
@Composable
private fun ValueCheck(
    draft: ParameterDraft,
    frame: CanIdRecord?,
    live: ObdReading?,
    trying: Boolean,
    onTry: (() -> Unit)?,
) {
    ShellCard {
        if (draft.source == CarParameterSource.CAN && frame != null) {
            val v = draft.valueOn(frame)
            ListRow("On the scan's last frame", v?.let { valueText(it, draft.unit) } ?: "–", divider = false)
        }
        when {
            live != null -> ListRow("Now, from the car", live.valueText(), divider = frame != null)
            trying -> ListRow("Now, from the car", "waiting…", divider = frame != null)
        }
    }
    if (onTry != null) {
        val ready = draft.sourceValid && draft.equationValid
        OutlinedButton(onClick = onTry, enabled = ready, modifier = Modifier.fillMaxWidth()) {
            Text(if (trying) "Try again on the car" else "Try on the car")
        }
        DimText("Connects again and reads it from the car, before it is saved.")
    }
}

@Composable
private fun NextButton(enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Next") }
}

@Composable
private fun SubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ShellTopBar(title, onBack = onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

private const val LitAlpha = 0.35f


private fun valueText(v: Double, unit: String) = ObdReading("", "", unit, v, 0.0).valueText()
