package com.flappedear.betterdriver.recorder.store

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.SampleSet

/** One record of a chunk file ([Frames] on the JVM). */
sealed interface Record {
    data class Channel(val def: ChannelDef) : Record
    data class Samples(val set: SampleSet) : Record
    data class Event(val event: RecorderEvent) : Record
    /** Written on a clean stop. A session without it was interrupted. */
    data class End(val tMicros: Long, val reason: String) : Record
}
