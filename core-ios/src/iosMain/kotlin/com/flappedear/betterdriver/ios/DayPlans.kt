package com.flappedear.betterdriver.ios

/** Where the day plans (DayPlanStore, shared with Android) are kept under the app's folder [base]: coach/days. */
internal fun dayPlansDir(base: String) = "$base/coach/days"

/** The day plans beside the line plans in [linePlansDir] (coach/lines and coach/days). */
internal fun dayPlansBeside(linePlansDir: String) = linePlansDir.trimEnd('/').substringBeforeLast('/') + "/days"
