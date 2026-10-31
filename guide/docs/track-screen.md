# The track screen

The track screen shows while a session records. It is built to be read at a glance in sunlight:
true black, large numbers, and every text at a contrast of at least 7:1. While it shows, the
screen stays at full brightness (you can turn that off in **Settings › Track screen**).

## Screen 1: timing

<figure markdown>
![Track screen, first screen](img/track_screen.png){ .shot }
<figcaption>Delta vs best on the left; lap number, running lap time, last and best lap on the
right; the coach line; six tiles.</figcaption>
</figure>

- **Delta vs best**: green when you are ahead of your best lap, red when you are behind. Before a
  best lap exists it shows a grey dash.
- **Lap number and running time** of the current lap, then **Last** and **Best** (best in purple).
- **The coach line** shows what the coach just said, for example "T4 Brake at 100 m · 10 later".
  A praise shows in green. With the spoken coach off, the line is left out and the tiles grow
  into its place.
- **Tiles**: one row of six.

### The status strip

The strip at the top stays quiet while all is well: a green dot and a name for the receiver and
the camera, a car icon while the car answers over OBD-II, a heart with your heart rate, the
recording time, and dots that show which screen is on. Anything wrong shows in amber.

When the receiver loses its fix, a red banner says so; lap timing pauses and video carries on.
**Settings › Track screen › Sound when GNSS is lost** adds a short sound when the fix goes and
comes back.

<figure markdown>
![GNSS signal lost banner](img/track_screen_gnss_lost.png){ .shot }
<figcaption>GNSS signal lost: lap timing paused, video still recording.</figcaption>
</figure>

## Screen 2: G radar and more tiles

Swipe left for the second screen.

<figure markdown>
![Track screen, second screen with G radar](img/track_screen_second_radar.png){ .shot }
<figcaption>The G radar beside nine tiles.</figcaption>
</figure>

The **G radar** shows 0.5 g and 1 g rings, no numbers. The dot moves the way you are pushed:
braking moves it up, a right turn moves it left, with a half-second trail. The dot goes away when
the fix is lost.

With **Phone GPS** the radar and the G tile use the phone's own accelerometer. The phone can sit
any way in its mount: the app levels it while the car stands still, then works out which way is
forward from the GPS over about the first minute of driving. Until then the dot shows the G from
GPS, which is right but later.

## Screen 3: the next corner

Swipe left again for a map of the next corner: the road your laps used there, the line plan's
target line through it coloured by what to do (red brake, grey coast, green throttle) and where
the car is.

<figure markdown>
![Track screen, next corner map](img/track_screen_map.png){ .shot }
<figcaption>The next corner (T1): the target line by pedal, and the car (amber dot).</figcaption>
</figure>

## Choosing what a tile shows

Set the tiles before you drive, in **Settings › Track screen**. Each car has its own layout: pick
the car under **Layout for** (a car without its own starts from the tiles you had). Both screens
are drawn as they look in the car; tap a tile and pick what it shows from a list grouped by source,
or **Reset tiles to default**. The FR-S CAN channels (steering angle, brake pressure) are in the
list too. The track screen itself has no gesture for tiles: holding 3 s anywhere only stops the
session.

A tile can show speed, G, satellites, delta, lap time, last, best or predicted lap, lap number,
any OBD-II value the car sends (coolant, oil, intake air, RPM, throttle, battery voltage and the
rest), the gear, or heart rate.

**Gear** is in the car's group of the list, after RPM. The app works it out from RPM and speed:
in each gear the engine turns at a fixed speed per km/h, so after about a minute of driving with
RPM the session knows the car's gears (the FR-S's 6-speed automatic). It shows a dash during a
shift, while the converter slips, and before the gears are known. A car whose engine follows no
fixed gears, such as the Corolla hybrid, has no gear, so the tile is left out there.

<figure markdown>
![Track screen on a new install](img/track_screen_new_install.png){ .shot }
<figcaption>A new install shows Speed and G; the dashed tiles are empty until you choose.</figcaption>
</figure>

While driving, a tile whose channel sends nothing in this session is left out and the others
widen; a value that stops arriving shows a dash. Live values are smoothed so they can be read:
G over the last 0.5 s, RPM and the other car values over the last 0.3 s.
