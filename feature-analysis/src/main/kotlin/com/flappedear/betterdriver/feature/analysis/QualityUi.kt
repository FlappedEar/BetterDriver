package com.flappedear.betterdriver.feature.analysis

/** This state with the Data quality card, once the report is read ([quality] not null). */
fun AnalysisState.withQuality(quality: QualityUi?): AnalysisState = when {
    quality == null -> this
    this is AnalysisState.Ready -> AnalysisState.Ready(ui.copy(quality = quality))
    this is AnalysisState.Unavailable -> copy(quality = quality)
    else -> this
}
