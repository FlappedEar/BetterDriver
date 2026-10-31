# BetterDriver

BetterDriver is a track-day coach for Android phones and the iPhone. The phone in the car records
the session, shows live timing and speaks short coaching cues through your helmet headset. After
the session, the phone or a tablet (an Android tablet or an iPad) shows where the lap time went,
what to do differently next time, and whether the last change worked. Tablets are for analysis
only: they do not record. The iPhone and iPad app looks and works like the Android app and comes
through TestFlight; what it does not have yet is listed on [iPhone and iPad](ios.md).

<figure markdown>
![The track screen during a lap](img/track_screen.png){ .shot }
<figcaption>The track screen: delta to your best lap, the lap number and running time, last and
best lap, the coach line and six tiles.</figcaption>
</figure>

## One loop: drive, read, change, prove

Everything in the app serves one question: what will you do differently on the next lap, and how
does the next session show that it gained time.

1. **Record.** Start a session on the Drive screen. The app times laps and sectors at a saved
   track and records GNSS, G-forces, video, car data and heart rate when you have them.
2. **Read.** Open the session in Sessions. Lap analysis shows your best actual lap, the realistic
   optimal lap and where time goes, corner by corner.
3. **Plan.** The Map tab's *Where to find time* ranks the corners by time to find and says, in
   words, where to brake, where to be and where to go back to the throttle.
4. **Drive the plan.** At the same track, the spoken coach aims at that plan: it names the
   braking point, the apex speed, the line and the throttle point for the corners that matter.
5. **Prove.** The next session's analysis says how much of the gap you won back in the corners
   the coach worked on.

## What you need

| You need | Why |
| --- | --- |
| An Android phone or an iPhone (12 or newer) | Records, shows the track screen, speaks the cues. A tablet (Android or iPad) is optional, for review only. |
| A GNSS receiver | Lap timing. A RaceBox (Mini, Mini S or Micro) is the main one; other Bluetooth or Wi-Fi receivers and the phone's own GPS also work. See [Equipment](equipment.md). |
| A helmet headset (optional) | Hears the coach. The app measures its audio delay by itself. |
| An OBD-II adapter (optional) | Car data: RPM, throttle, pedals, temperatures. On a gen-1 FR-S/86/BRZ it also reads steering and brake pressure from the CAN bus. |
| A camera (optional) | The phone's own camera by default; a GoPro or another action camera too. |
| A heart-rate strap (optional) | Heart rate on the track screen and in the analysis. |

No receiver yet? Add **Demo track** and the app drives a made-up car around a circuit so you can
try timing and coaching anywhere. See [Try it without a track](demo.md).

## About this guide

This guide describes **BetterDriver 1.22.1**, the latest Android release, and the iPhone and iPad
app on TestFlight. It marks Android features that are merged but not yet released like this:

!!! note "Not released yet"
    Merged after 1.22.1. It ships in the next release.

The pictures are the app's own screenshot tests, drawn from test data, so the numbers in them are
examples. Screen and button names in **bold** are the app's own words.
