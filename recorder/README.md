# recorder

Recording service and crash-safe session store. It runs in its own Android
process and depends only on the data sources and the session store, never on
coach, analysis, camera or UI code (FBBD-69 to FBBD-72).
