package com.flappedear.betterdriver.debug

import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.flappedear.betterdriver.camera.PhoneHeat
import com.flappedear.betterdriver.shell.DimText
import com.flappedear.betterdriver.shell.ListRow

/**
 * Settings › Diagnostics › Ignore phone temperature (FBBD-231), off by default: for Arek's heat
 * stress test, video keeps its chosen mode however hot the phone gets. Applies from the next session.
 */
@Composable
fun PhoneHeatRow() {
    val context = LocalContext.current
    var ignored by remember { mutableStateOf(PhoneHeat.ignored(context)) }
    ListRow("Ignore phone temperature", if (ignored) "On" else "Off") {
        Switch(checked = ignored, onCheckedChange = { on ->
            ignored = on
            PhoneHeat.setIgnored(context, on)
        })
    }
    DimText(
        if (ignored) {
            "Heat stress test: video never stops for heat. The log keeps every reading. " +
                "Switch off after the test."
        } else {
            "Off: video keeps its quality and stops only at a 60 °C battery."
        },
    )
}
