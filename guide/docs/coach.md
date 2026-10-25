# The spoken coach

The coach speaks short cues on the straights, timed to end before the next braking zone. It
learns from your own driving: every target is something you have already driven, at least twice.

Set it up in **Settings › Spoken coach**.

## How much it says

| Setting | What you hear |
| --- | --- |
| **Off** | Nothing. |
| **Lap times only** | Each timed lap's time. |
| **Lap times and the 3 corners losing most** | Lap times, and cues for three corners: those you picked first, then those losing most time. This is the setting for the plan. |
| **Lap times and every corner** | Lap times and cues for any corner. |

**Picked corners**: in a session's lap analysis, tap **Coach this next time** on a corner. At
"3 corners losing most", picked corners are coached first. **Clear** removes a track's picks.

**Learning laps**: at a new track the coach says only lap times until it has learned this many
laps. Back out on the same day, it needs only one.

## What the cues sound like

Corners are named by the lap analysis's numbers ("Turn 3"). A cue says the target first, then
the change, with up to two things per cue.

| Cue | Example |
| --- | --- |
| Braking point | "Turn 3, brake at 100, 10 later" (metres before the corner's start) |
| Apex speed | "Turn 3, 60 at the apex, 5 more" (km/h) |
| Line | "Turn 1, tighter at the apex" |
| Throttle | "Turn 1, throttle 5 after the apex" (needs OBD-II throttle) |
| Steering (FR-S with CAN) | "front slid, slower in", "ran wide, turn in more", "one smooth turn", "rear stepped out, gentler throttle" |
| Praise | "Turn 3, that worked" when you made the change and the corner got faster; "Turn 3, best yet" when it beat its fastest pass by 0.05 s or more. At most one per lap. |
| Today's plan | On the out-lap, once: "Today, turns 9, 1 and 4". |

When the straight is too short for the whole cue, you hear the target alone ("Turn 3, brake at
100"). Numbers from 100 to 999 are said the way track people say them ("1 50", "1 oh 5").

!!! note "Not released yet: gear in the corner"
    For a car with OBD-II engine speed, the coach can add a gear to the cue: "Turn 7, brake at 80,
    second", where a lower gear pulls clearly harder at that apex speed and still has room to rev.
    Gears are found from your own sessions in that car.

!!! note "Not released yet: beyond your best"
    Where your best lap already matches its target, the coach can ask for a braking point later
    than you have driven, in steps, as an ordinary brake cue.

### No precise advice from weak evidence

Every corner pass gets a confidence level. Low confidence never becomes a cue. At medium
confidence the coach speaks without numbers ("Turn 3, brake a little later"). It goes quiet on a
cool-down lap, in the pit lane and on the in-lap, and comes back as soon as two corners in a row
are again near your pace.

## After a cue: did it work?

The coach line on the track screen shows what came of a cue: green "Apex 1.8 m tighter ✓ ·
−0.09 s", or grey "not yet".

After the session, **Coach review** (from the session's ⋮ menu in Sessions) lists every cue with
its result, for example "Braked at 95 m, 10 m later than before" or a green **Praise** tag.

## Voice and language

- **Language**: English or Polski. In Polish every cue is said in Polish ("Trójka, hamuj na 100,
  10 później"), and the coach line and Coach review follow; the rest of the app stays in English.
- **Speech engine**: any text-to-speech engine on the phone, for example SherpaTTS with Piper
  neural voices.
- **Voice**: lists the chosen engine's offline voices of the chosen language, grouped by accent.
  ▶ plays a sample. **More voices in Android settings** opens Android's text-to-speech settings
  to download more.
- **Speed** (0.8× to 1.5×) and **Pitch** (0.7× to 1.3×).
- **Test voice** says a sample cue on the current audio output.
- **Advanced › Voice playback**: **Pre-rendered** says each word and number from clips made at
  the start of a coached session, so a cue starts sooner but sounds more clipped; **Live** speaks
  each cue whole.

## Headset delay

Nothing to do but connect the headset. At the start of every coached session, and whenever the
audio output changes, the coach measures how long the sound takes to reach the headset and says
each cue that much earlier. **Settings › Spoken coach** shows the delay measured now and on which
device. **If it can't be measured** sets the delay used when Android gives no audio clock.
