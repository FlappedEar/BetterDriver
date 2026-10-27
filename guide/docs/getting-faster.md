# Getting faster

This is the loop the app is built around: find where the time is, drive the plan with the coach,
and see in the next session what came of it.

## Where to find time

Open a session's **Map** tab and choose **Where to find time**.

- The whole lap with the **time to find per corner**, and a list of corners ranked by it.
- Tap a corner in the list: the map zooms to it, with the road your laps used, your lap coloured
  by the pedals and the target as a thin green line. Below, it says **what to do there in words**:
  where to brake, where to be at the apex, where to go back to the throttle, and on an FR-S with
  the CAN bus, what the steering wheel did, with your wheel trace against the target's.
- The target in each corner is the **second fastest pass over every session at the track**: a
  pass you have driven, not a guess. Less than 0.05 s is left out as timing noise.

One line plan is kept per track, from the latest analysed session. At that track, the
[spoken coach](coach.md) aims at the plan's target in each corner until you beat it twice, and on
the out-lap it names today's corners once: "Today, turns 9, 1 and 4". The track screen's coach
line shows the same, for example "Today: T9 · T1 · T4 · ≈ 1.0 s to find".

Where your best lap already matches its target, *Where to find time* adds the corners braked
well short of the track's own braking limit as their own group, worded as the whole room and
then the step: "Brake up to 18 m later · start with 10". These fill the focus places the
driven targets leave, and the coach asks for the step.

With OBD-II engine speed, a corner can say "2nd at the apex" with the reason from your own
laps, where a lower gear pulls clearly harder at the apex speed. The gearbox is worked out
from the car's own sessions.

## Perfect lap in 3D

**▶ Perfect lap in 3D** (on a phone, **▶ 3D**) on *Where to find time* plays the plan's perfect
lap, following the car from behind and above, with your best lap as a ghost.

- The line is a car-wide band coloured by the pedals, with BRAKE and GAS bars across the road.
- Play and pause, full or half speed, jump to a corner.
- Pinch to move the camera nearer or farther (0.5 to 3 times); double tap puts it back.
- With a MapTiler key (see [Tracks](drive.md#satellite-map)) the ground is the satellite photo,
  else the drawn track.
- **Save video** writes a 1920×1080, 30 fps MP4 to *Movies/BetterDriver*; **Send** shares it.

## Next session: did it work?

At the same track, the next session's Summary has **Last focus · what came of it**: the time won
back in the focus corners, the time left to the target and whether the coach spoke there.
**Earlier gains · did they hold** marks older gains as **Learned**, **Partly held** or **Lost**.

## Progress over every session

**Progress over every session here** (on the Summary) shows the track over time: best, typical
and spread of the laps, and per corner the median time, braking point, minimum and exit speed,
throttle pickup and repeatability, plus the coaching results. Group by session, track day or
month, with a trend from three points on. Imported sessions take part too.

## Track day

When a track was driven in two or more sessions on one day, the day's heading in Sessions has a
**Track day ›** link. The track day page shows:

- the day's numbers and a chart of its sessions,
- each corner in words with its kind and a small trend line,
- per corner: the day's target (the second fastest pass of the day), what the best lap and a
  typical lap lose there, and what to do,
- what to do next time there, and what came of the last day.

The first session at that track on the next day gets the day's three corners as the coach's
focus; later sessions use the line plan as usual.
