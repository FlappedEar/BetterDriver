package com.flappedear.betterdriver.obd

private const val MillisPerSecond = 1_000L

/** Characters of an answer quoted in the log. */
private const val AnswerLogged = 40

/** A group's channels as the log names them: `coolant_temp+intake_air_temp`. */
internal fun names(group: List<PidChannel>): String = group.joinToString("+") { it.id }

/**
 * The log line for what became of [request] (FBBD-382), whose channels are [group] and whose last
 * answer was [text] (null: none came): dropped, tried again in vain, or answering again; null when it
 * was polled as usual. On 2026-10-29 the temperatures stopped for 30 minutes and the log said nothing.
 */
internal fun outcomeLine(
    outcome: PollScheduler.Outcome,
    request: Request,
    group: List<PidChannel>,
    text: String?,
    scheduler: PollScheduler,
): String? {
    val answer = text?.trim()?.replace('\r', ' ')?.take(AnswerLogged) ?: "no answer"
    val wait = scheduler.retryWaitMs(request) / MillisPerSecond
    return when (outcome) {
        PollScheduler.Outcome.Dropped ->
            "dropped ${names(group)} after ${scheduler.missesInARow(request)} misses ($answer), retry in $wait s"
        PollScheduler.Outcome.StillDropped -> "retry ${names(group)}: $answer, next in $wait s"
        PollScheduler.Outcome.Back -> "${names(group)} answer again"
        PollScheduler.Outcome.Answered, PollScheduler.Outcome.Missed -> null
    }
}
