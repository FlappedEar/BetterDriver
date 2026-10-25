# Car

**Car** in the bottom bar is the next session's car, titled with its name: the OBD-II adapter's
state and the car's live values. Live values show on the next session's car, because the adapter
reads the car it is plugged into.

- **Edit** changes the car's name and notes. The name goes into each session and its VBO export.
- Your other cars are in **Settings › Cars**: **Add car**, **Use for the next session**,
  **Remove car**.

## Car data over OBD-II

Add an OBD-II adapter as equipment (see [Equipment](equipment.md#obd-ii-adapters)). While it is
listed, every session reads the car through it. To keep the readings fast, a session records what
the app uses: RPM, the accelerator pedal, the brake where the car offers it, the temperatures and
any value you put on a [tile](track-screen.md#choosing-what-a-tile-shows). If the
adapter drops out or the engine is switched off, the recording carries on and the app reconnects
on its own.

The **Live values** page (from the Car tab, or **Settings › Equipment › Car data**) shows whether
the adapter answers, the adapter and protocol, the battery voltage at the OBD port, then every
value the car sends with how many times a second it arrives. With no session recording, the page
connects to the adapter itself, so you can check it before driving: switch the ignition on and
open the page. It lets go as soon as a session starts.

## FR-S, GT86 and BRZ (gen 1)

On a first-generation FR-S/86/BRZ, the app listens to the car's own CAN bus through the adapter
and records steering angle, brake pressure, brake, accelerator, throttle, RPM, oil and coolant
temperature at 20 to 100 times a second. Only ATF and intake air temperatures are still polled.
An STN adapter such as the vLinker listens to everything at once; a plain ELM327 listens to one
value at a time.

That data feeds the steering advice from the coach and on the Map tab, and the steering and brake
pressure cards on the analysis **Car** tab.

The car is recognised from its VIN, or from a car profile named FR-S, GT86, FT86, Toyota 86 or
BRZ.
