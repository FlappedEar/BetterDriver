@file:Suppress("MagicNumber") // Example data.

package com.flappedear.betterdriver.feature.analysis

/**
 * A track day as the page shows it, numbers from Arek's six Jastrząb sessions of 29 Aug 2026 as the
 * day analysis gives them (FBBD-315), with an example of what came of an earlier day: the page's
 * preview and layout tests.
 */
val PreviewTrackDayUi: TrackDayUi by lazy {
    val t3 = TrackDayCornerUi(
        "T3", 0.59, "Your best lap does it: repeat it", false,
        listOf("Brake 13 m later", "Apex 2 m tighter", "Carry 4 km/h more through the slowest point"),
        listOf(12.24, 11.96, 12.08, 10.35, 9.67, 10.13),
    )
    val t1 = TrackDayCornerUi(
        "T1", 0.53, "Even your best lap loses 0.11 s", true,
        listOf("Brake 6 m earlier", "Carry 2 km/h more through the slowest point", "Back on the throttle 7 m later"),
        listOf(18.36, 19.73, 16.41, 14.26, 14.03, 14.63),
    )
    val t7 = TrackDayCornerUi(
        "T7", 0.41, "Your best lap does it: repeat it", false,
        listOf("Brake 14 m later", "Apex 7 m earlier and 2 m wider", "Carry 2 km/h more through the slowest point"),
        listOf(13.23, 12.87, 12.69, 12.88, 11.18, 11.09),
    )
    val t5 = TrackDayCornerUi(
        "T5", 0.38, "Your best lap does it: repeat it", false,
        listOf("Brake 22 m earlier", "Apex 11 m earlier", "Back on the throttle 18 m later"),
        listOf(17.77, 17.98, 15.59, 14.18, 13.99, 14.38),
    )
    TrackDayUi(
        title = "Jastrząb · Fri 29 Aug",
        subtitle = "6 sessions · 25 laps · 8 at the day's pace",
        best = "1:49.91",
        typical = "1:54.34",
        perfect = "1:49.50",
        loss = "A typical lap at your pace loses 2.80 s to your targets; your best lap leaves 0.41 s.",
        sessions = listOf(
            TrackDaySessionUi("10:00", 138.64, 142.52),
            TrackDaySessionUi("11:29", 141.80, 144.23),
            TrackDaySessionUi("13:03", 124.06, 127.28),
            TrackDaySessionUi("14:37", 113.27, 114.60),
            TrackDaySessionUi("16:06", 109.91, 112.58),
            TrackDaySessionUi("17:20", 111.24, 115.40),
        ),
        overDay = "Your typical lap got 30 s faster from 10:00 to 16:06; at 17:20 it was 2.82 s slower than at 16:06.",
        corners = listOf(t3, t1, t7, t5),
        next = listOf(t3, t1, t7),
        coachLine = "The coach starts your next day here on T3, T1, T7; " +
            "later sessions work on what the session before them found.",
        proof = TrackDayProofUi(
            "Last day here (Sat 23 Aug) · what came of it", "3.09 s", true,
            listOf(
                TrackDayProofRowUi("T2", "Faster", ProofTone.Better, "1.21 s back a lap · typical 7.43 → 6.22 s"),
                TrackDayProofRowUi("T3", "Faster", ProofTone.Better, "1.88 s back a lap · typical 11.52 → 9.64 s"),
            ),
        ),
    )
}
