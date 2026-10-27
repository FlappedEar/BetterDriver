package com.flappedear.betterdriver.recorder.vbo

/**
 * A channel worked out from the recording, written as one VBO column [name] in [unit]: [values] at
 * session times [tMicros] (ascending, the same clock as the sample sets). The exporter holds each
 * value until the next, as it does for recorded channels; before the first, the first is used.
 */
class VboDerivedChannel(
    val name: String,
    val unit: String,
    val decimals: Int,
    val tMicros: LongArray,
    val values: DoubleArray,
) {
    init {
        require(tMicros.size == values.size) { "${tMicros.size} times for ${values.size} values" }
        require(name.isNotBlank() && name.none { it.isWhitespace() }) { "bad column name '$name'" }
    }
}
