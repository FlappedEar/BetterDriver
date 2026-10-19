package com.flappedear.betterdriver

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.flappedear.betterdriver.coach.CoachLanguage
import com.flappedear.betterdriver.coach.CoachPicksRepo
import com.flappedear.betterdriver.coach.CoachPrefs
import com.flappedear.betterdriver.coach.CoachVoices
import com.flappedear.betterdriver.coach.MeasuredDelay
import com.flappedear.betterdriver.coach.OutputDelayProbe
import com.flappedear.betterdriver.coach.TtsVoice
import com.flappedear.betterdriver.coach.Voice
import com.flappedear.betterdriver.coach.VoiceMode
import com.flappedear.betterdriver.recording.tracksDir
import com.flappedear.betterdriver.shell.CoachSettingsScreen
import com.flappedear.betterdriver.shell.CoachVoiceScreen
import com.flappedear.betterdriver.shell.EngineRow
import com.flappedear.betterdriver.shell.PickedCorners
import com.flappedear.betterdriver.shell.VoiceRow
import com.flappedear.betterdriver.shell.sampleCue
import com.flappedear.betterdriver.timing.track.TrackStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Settings › App › Spoken coach (FBBD-98, UI-64 B): saved as it changes, read at the next session start.
 * The corners picked in the lap analysis (FBBD-148) are read with the track names off the main thread.
 * The headset's delay (FBBD-49) is measured while the page is open, to show it.
 */
internal fun NavGraphBuilder.coachSettings(nav: NavHostController, padding: PaddingValues) {
    composable(Routes.CoachSettings) {
        val context = LocalContext.current
        val coach = remember(context) { CoachPrefs.get(context) }
        val picks = remember(context) { CoachPicksRepo.get(context) }
        val byTrack by picks.picks.collectAsState()
        var names by remember { mutableStateOf<Map<String, String>?>(null) }
        LaunchedEffect(Unit) {
            picks.loadAll()
            names = withContext(Dispatchers.IO) {
                TrackStore(tracksDir(context)).all().associate { it.id to it.displayName }
            }
        }
        val picked = names?.let { n ->
            byTrack.filterValues { it.isNotEmpty() }
                .map { (id, p) -> PickedCorners(id, n[id] ?: "A track no longer saved", p.map { it.name }) }
                .sortedBy { it.trackName }
        }
        val voice = rememberVoice()
        val settings = coach.settings
        LaunchedEffect(voice, settings.voice, settings.speech, settings.language) {
            voice.use(settings.voice, settings.speech, settings.language)
        }
        val phone = rememberVoices(voice, settings.speech.engine)
        val label = phone?.let { p ->
            val chosen = CoachVoices.choose(settings.speech.voiceName, p.voices, settings.language)
            chosen?.let { CoachVoices.label(it, p.voices) } ?: "Engine default"
        }
        Box(Modifier.padding(padding)) {
            CoachSettingsScreen(
                coach.settings,
                coach::update,
                onBack = { nav.popBackStack() },
                picked = picked,
                onClearPicked = picks::clear,
                delayNow = rememberDelayNow(coach.settings.voice == VoiceMode.PreRendered),
                voiceLabel = label,
                onVoice = { nav.navigate(Routes.CoachVoice) },
                onTest = { voice.sample(settings.speech, settings.language, sampleCue(settings.language)) },
                engines = phone?.engines,
                defaultEngine = phone?.defaultEngine,
            )
        }
    }
    coachVoice(nav, padding)
}

/** Spoken coach › Voice (FBBD-240, UI-90 A): picking a voice saves it and says the sample cue in it. */
private fun NavGraphBuilder.coachVoice(nav: NavHostController, padding: PaddingValues) {
    composable(Routes.CoachVoice) {
        val context = LocalContext.current
        val coach = remember(context) { CoachPrefs.get(context) }
        val voice = rememberVoice()
        val speech = coach.settings.speech
        val language = coach.settings.language
        val voices = rememberVoices(voice, speech.engine)?.voices
        val groups = voices?.let { all ->
            CoachVoices.byAccent(all, language).map { (accent, vs) ->
                accent to vs.map { VoiceRow(it.name, "Voice ${CoachVoices.number(it, all)}") }
            }
        }
        val sample = sampleCue(language)
        LaunchedEffect(voice, speech.engine) { voice.use(VoiceMode.Live, speech, language) }
        Box(Modifier.padding(padding)) {
            CoachVoiceScreen(
                groups,
                selected = voices?.let { CoachVoices.choose(speech.voiceName, it, language)?.name },
                onPick = { name ->
                    coach.update(coach.settings.copy(speech = speech.copy(voiceName = name)))
                    voice.sample(speech.copy(voiceName = name), language, sample)
                },
                onPlay = { name -> voice.sample(speech.copy(voiceName = name), language, sample) },
                onMoreVoices = { openTtsSettings(context) },
                onBack = { nav.popBackStack() },
                language = if (language == CoachLanguage.Polish) "Polish" else "English",
            )
        }
    }
}

/** A voice of the page's own, for Test voice and the samples, shut down when the page goes. */
@Composable
private fun rememberVoice(): Voice {
    val context = LocalContext.current
    val voice = remember(context) { Voice(context) }
    DisposableEffect(voice) { onDispose { voice.shutdown() } }
    return voice
}

/** What the phone's speech engine offers (UI-101 A): its voices, the phone's engines and its default one. */
private data class PhoneSpeech(val voices: List<TtsVoice>, val engines: List<EngineRow>, val defaultEngine: String?)

/**
 * The voices of [voice]'s engine [engine] and the phone's engines, once the engine has started; null
 * until then, empty if it never does. Read again each time the page comes back, so a voice or engine
 * installed meanwhile shows at once, and when another engine is picked.
 */
@Composable
private fun rememberVoices(voice: Voice, engine: String?): PhoneSpeech? {
    var phone by remember(voice) { mutableStateOf<PhoneSpeech?>(null) }
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    LaunchedEffect(voice, resumes, engine) {
        var waited = 0L
        while (true) {
            val read = withContext(Dispatchers.IO) {
                voice.voices(engine)?.let { vs ->
                    val engines = voice.engines(engine).orEmpty().map { EngineRow(it.first, it.second) }
                    PhoneSpeech(vs, engines, voice.defaultEngine(engine))
                }
            }
            if (read != null || waited >= VoicesWaitMillis) {
                phone = read ?: phone ?: PhoneSpeech(emptyList(), emptyList(), null)
                break
            }
            delay(VoicesPollMillis)
            waited += VoicesPollMillis
        }
    }
    return phone
}

/** Android's text-to-speech settings, where more voices are downloaded; the system settings if absent. */
private fun openTtsSettings(context: Context) {
    val tts = Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(tts)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * The line saying the headset's delay (FBBD-49), measured while the page is open and again every few
 * seconds, so connecting a headset shows its delay; on the low-latency path when cues are pre-rendered
 * clips ([lowLatency]), as the coach times them. Null until the first measurement.
 */
@Composable
private fun rememberDelayNow(lowLatency: Boolean): String? {
    val context = LocalContext.current
    var line by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(context, lowLatency) {
        while (true) {
            line = withContext(Dispatchers.IO) { delayLine(OutputDelayProbe.measure(lowLatency)) }
            delay(DelayCheckMillis)
        }
    }
    return line
}

private fun delayLine(m: MeasuredDelay?): String = when {
    m == null -> "Android gives no delay for this output, so the coach uses the one below."
    m.headset != null -> "${m.millis} ms now, on ${m.headset}"
    else -> "${m.millis} ms now, on the phone speaker (no headset connected)"
}

private const val DelayCheckMillis = 5_000L
private const val VoicesPollMillis = 100L
private const val VoicesWaitMillis = 10_000L
