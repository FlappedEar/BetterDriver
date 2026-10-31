@file:Suppress("MagicNumber") // Arek's sessions and the shares of time they are read at.

package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.gear.LiveGear
import com.flappedear.betterdriver.timing.overlay.OverlayData
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The gear held between shifts on Arek's nine FR-S sessions (Jastrząb 29 Aug, Silesia Ring 27 Sep).
 * Read only where engine speed over road speed sits within 4 % of a gear, the gear showed a dash for
 * about a fifth of the time the car was moving, in runs of seconds: the torque converter slips 4 to
 * 8 % under braking and back on the throttle. Held until the engine speed steps to another gear
 * (Arek, 31 Oct: "po wykresie RPM można znaleźć gdzie jest zmiana biegu"), the dash is left only
 * standing and on the first metres before a reading sits on a gear.
 */
class GearShiftRealTest {
    @TempDir
    lateinit var tmp: File

    /** Tallies of the moments read while moving. */
    private class Read {
        var moving = 0
        var dashes = 0
        var agree = 0
        var sure = 0
        private var run = 0
        private var longest = 0
        val dashShare get() = dashes.toDouble() / moving
        val agreeShare get() = agree.toDouble() / sure
        val longestDashS get() = longest / 10.0

        /** One moving moment: [g] the gear shown, [plain] the gear the ratio alone sits on (or null). */
        fun add(g: Int?, plain: Int?) {
            moving++
            if (g == null) {
                dashes++
                longest = maxOf(longest, ++run)
                return
            }
            run = 0
            // Where the ratio itself sits on a gear and a gear is shown, it is that gear.
            if (plain != null) {
                sure++
                if (g == plain) agree++
            }
        }

        fun stop() {
            run = 0
        }
    }

    /** Every 100 ms while moving at 18 km/h or more: how often [gearAt] gives none, and the longest run of none. */
    private fun read(data: OverlayData, start: Long, end: Long, gearAt: (Long) -> Int?): Read {
        val box = assertNotNull(data.gearbox)
        val read = Read()
        for (t in start..end step 100_000) {
            val frame = data.at(t)
            val kmh = frame.speedKmh
            val rpm = frame.car.rpm
            if (kmh != null && kmh >= 18.0 && rpm != null) {
                read.add(gearAt(t), box.gearAt(rpm, kmh / 3.6))
            } else {
                read.stop()
            }
        }
        return read
    }

    @Test
    fun `the gear is held through the converter's slip and changes where the RPM steps, on all nine sessions`() {
        val store = SessionStore(File(tmp, "sessions"))
        val lines = mutableListOf<String>()
        val checks = mutableListOf<() -> Unit>()
        for (name in RealCorpus.JASTRZAB_DAY + RealCorpus.SILESIA_DAY) {
            val id = RealCorpus.import(store, File(tmp, "staging"), name).id
            val session = store.read(id)
            val sets = session.sampleSets.sortedBy { it.tMicros }
            val data = OverlayData(sets, laps = null, channels = session.channels.values.toList())
            val box = assertNotNull(data.gearbox, name)
            val start = sets.first().tMicros
            val end = sets.last().tMicros

            val plain = read(data, start, end) { t ->
                val f = data.at(t)
                val rpm = f.car.rpm
                val kmh = f.speedKmh
                if (rpm != null && kmh != null) box.gearAt(rpm, kmh / 3.6) else null
            }
            val video = read(data, start, end) { data.at(it).car.gear }

            // The Gear tile, fed the session as it was recorded.
            var now = 0L
            val live = LiveGear { now }
            session.channels.values.forEach(live::onChannel)
            val tile = HashMap<Long, Int?>()
            var next = start
            for (s in sets) {
                live.onSamples(s)
                now = s.tMicros
                while (next <= now) {
                    tile[next] = live.gear()
                    next += 100_000
                }
            }
            val tileRead = read(data, start, end) { tile[it] }

            lines += "$name: moving ${plain.moving / 10} s; " +
                "dash before ${pct(plain.dashShare)}, longest ${plain.longestDashS} s; " +
                "video now ${pct(video.dashShare)}, longest ${video.longestDashS} s; " +
                "tile now ${pct(tileRead.dashShare)}, longest ${tileRead.longestDashS} s; " +
                "gear as the plain reading: video ${pct(video.agreeShare)}, tile ${pct(tileRead.agreeShare)}"

            checks += {
                assertTrue(plain.dashShare > 0.1, "$name: the plain reading shows a dash for ${pct(plain.dashShare)}")
                assertTrue(video.dashShare < 0.03, "$name: video dash ${pct(video.dashShare)}")
                assertTrue(video.agreeShare >= 0.99, "$name: held gear agrees ${video.agree} of ${video.sure}")
                // The tile only reads a gear once its gearbox fits, about a minute in.
                assertTrue(tileRead.dashShare < 0.08, "$name: tile dash ${pct(tileRead.dashShare)}")
                assertTrue(tileRead.agreeShare >= 0.99, "$name: tile agrees ${tileRead.agree} of ${tileRead.sure}")
            }
        }
        println(lines.joinToString("\n"))
        checks.forEach { it() }
    }

    private fun pct(x: Double) = "%.1f %%".format(x * 100)
}
