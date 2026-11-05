# Phone test plan

Use this checklist to verify every feature on a real Android phone. Each test
states exactly what to do and what counts as a pass. Record PASS/FAIL and take
a screenshot of anything unexpected.

**Setup**
1. Uninstall any older PervasiveSense (different signing key).
2. Install the APK from the latest GitHub Release.
3. Open the app, tap **Start sensing**, allow **Notifications** and **Physical activity**.
4. Tap **Show sensor details** (bottom of the dashboard) for the technical checks.

The model was trained with the phone in a **front trouser pocket** or on the
**waist**. Do the movement tests with the phone in a front trouser pocket unless
stated otherwise, then check the screen.

---

## A. Start-up & live operation

| # | Do this | Pass if |
|---|---|---|
| A1 | Tap **Start sensing** with the phone on a table | Status pill shows *Starting*, then *Live*; the top card shows *Calibrating* with a rising bar, then an activity within ~3 s |
| A2 | Tap **Show sensor details** | Sensor rate ≈ 50–60 Hz, inference a few ms, the run count increasing about twice per second |
| A3 | Watch the acc/gyro values in the sensor details | Values change continuously (10 updates per second) |
| A4 | Tap **Stop sensing** | Pill shows *Stopped*, the top card says *Not running*, the button says *Start sensing* |

## B. Activity recognition (phone in front trouser pocket)

| # | Do this | Pass if |
|---|---|---|
| B1 | Phone on a table face up, then face down, then on its side (10 s each) | **Still** every time |
| B2 | Stand still 10 s, then walk normally 30 s | **Walking** appears within ~3 s of starting to walk; the Walking bar is the longest under *How sure the model is* |
| B3 | Walk 20 s, then stop | **Still** within ~4 s of stopping |
| B4 | Jog / run 20 s | **Running** |
| B5 | Climb a flight of stairs | **Stairs up** for most of the climb (brief *Walking* at landings is normal) |
| B6 | Walk down a flight of stairs | **Stairs down** for most of the descent |
| B7 | After B2–B6, check Activity log | Each change listed with its own time; the count increases |

## C. Steps

| # | Do this | Pass if |
|---|---|---|
| C1 | Note the step count, walk exactly **100 steps** (count them), stop, wait 10 s | Count increased by **90–110** |
| C2 | Sensor details, "Steps from:" | Shows *hardware step counter* on most phones (otherwise the accelerometer detector) |

## D. Posture & sitting (phone in front trouser pocket, sitting on a chair)

| # | Do this | Pass if |
|---|---|---|
| D1 | Sit upright for 1 min | Posture shows *Upright*; Sitting time starts counting |
| D2 | Slouch forward / lean back for 30 s | The posture angle changes by ≥ 10°. The label (*Slouching* / *Reclined*) depends on how the phone sits in the pocket; beyond ±25° it changes |
| D3 | Sit still vs. tap your foot / shift constantly | Restlessness shows *Calm* when still, *Restless* when moving |
| D4 | Stand up and walk a few steps, then sit again | Sitting time resets to 0 min |
| D5 | (Optional) Sit 45 min | *Time for a break* on the card and a break-reminder notification |

## E. Fall detection (**do not fall yourself**)

Simulate with the phone only, onto something soft but firm (sofa cushion or mattress):

| # | Do this | Pass if |
|---|---|---|
| E1 | Hold the phone upright at ~1 m, drop it onto the cushion, **don't touch it for 10 s** | Card shows *Checking impact…*, then **Fall detected** after ~7.5 s; a fall-alert notification appears |
| E2 | Tap **I'm OK** (on the card or in the notification) | Card returns to *Monitoring*; the alert disappears |
| E3 | Jump in place, then stand still | No fall alert (body orientation did not change). *Checking impact…* may flash briefly, then returns to *Monitoring* |
| E4 | Walk, sit down firmly on a chair | No fall alert |

If E1 does not trigger, the landing was too soft (impact below 2.5 g). Try a
firmer cushion. Safety score in Daily wellness drops by 25 per confirmed fall.

## F. Vehicle & road hazards (**as a passenger only**)

| # | Do this | Pass if |
|---|---|---|
| F1 | Ride in a car/bus/auto for a few minutes | The top card shows **In vehicle** (with "body movement: Still"); Road hazards says *In a vehicle, watching the road* |
| F2 | Pass over speed breakers / rough patches | Counts increase for real events; smooth road does not add counts |
| F3 | Get out and walk | Road hazards says *Paused until you are in a vehicle*; walking never adds potholes |

Vehicle detection comes from Google Play services and can take 30–60 s to
report after the vehicle starts moving.

## G. Background, energy & persistence

| # | Do this | Pass if |
|---|---|---|
| G1 | Lock the screen with the phone in your pocket, walk 1 min, unlock | Steps and walking minutes increased; the notification showed the current activity |
| G2 | Leave the phone still with the dashboard closed for 2 min, then pull down the notification shade | The PervasiveSense notification ends with *eco mode* |
| G2b | Now open the dashboard | Pill shows *Live* immediately; sensor details show mode *live* |
| G3 | Stop and start the service | Daily wellness numbers (steps, minutes) are kept for the same day |
| G4 | Pull down notifications, tap **Stop** | Sensing stops; the dashboard shows *Stopped* |

## H. Permissions

| # | Do this | Pass if |
|---|---|---|
| H1 | Deny *Physical activity* (Android 14+) | Clear message above the button explaining it is required; no crash |
| H2 | Deny *Physical activity* (Android 10–13) | Sensing starts; Road hazards says it needs the permission |

## I. Fit-to-drive check (**never drive for these tests**, about 10 min)

| # | Do this | Pass if |
|---|---|---|
| I1 | Dashboard, tap the **Fit to drive** card, turn the switch on, tap *Turn on* | *Set up (0 of 3 done)* appears |
| I2 | Tap **Start walk recording**, lock the screen, phone in a front trouser pocket, walk normally | Within about 15 s the phone says *Recording your walk*, then *Halfway there*, then *Your walk is recorded* (about 2 min); Step 1 shows ✓ and Step 2 unlocks |
| I3 | Tap **Record balance (voice guided)** 3 times: **stand still**, feet together, phone flat on your chest, follow the voice | Step 2 shows ✓; Step 3 (contact) unlocks; *Before you drive* and *Test it* appear |
| I4 | **Test it → A. Normal walk → Start**, lock the screen, walk normally ~30 s | The phone says *your walk looks normal*; row A shows ✓ |
| I5 | **B. Wobbly walk → Start**, with someone next to you sway side to side with uneven steps ~30 s | The phone says *very different*; a notification and the *Take the balance check* card appear; row B shows ✓ |
| I6 | **C. Balance check while swaying → Start** (or *Take the balance check*), sway gently from the hips | *Better not to drive now* with *Find a cab* (and *Call* / *Message* if a contact is saved); row C shows ✓. Tap *I'm not driving* to clear |

Extra checks (optional):

| # | Do this | Pass if |
|---|---|---|
| I7 | Walk with the phone in your **hand** | Banner: *Not recording: put the phone in your trouser pocket* |
| I8 | As a **passenger**, walk 30 s to a car and ride | When the trip is detected a check runs (*Trip started*) |
| I9 | Turn on *Check when my car's Bluetooth connects*, allow Nearby devices, connect to a car | A check runs (*Car Bluetooth*) before the car moves |
| I10 | Study mode: participant ID, *Goggles 1*, walk 1 min, balance check, *Export CSV* | The CSV has `segment`, `walk_check` and `balance_check` rows labelled GOGGLES_LOW |
| I11 | Save a trusted contact (your own second number or a friend who knows), turn on **Alert my contact if I drive after a warning**, allow SMS (and location), tap **Send a test alert** | A 10 s countdown with *Cancel*, then the contact gets *PervasiveSense test…* with a map link; tapping *Cancel* during the countdown sends nothing |
| I12 | After a real *Check my walk* large gap (walk unsteadily without *Test it*), tap *I'm not driving*, then ride as a **passenger** | Banner *…trips and driving are being watched*; when the trip is detected: reminder *You said you weren't driving…* |
| I13 | Same, with the car's Bluetooth connecting (passenger, engine off) | A 30 s spoken countdown *Texting … in 30 s*; tap **Cancel – I'm a passenger**: nothing is sent |
| I14 | Turn the switch off | The dashboard card shows *Off* |

---|---|---|
| I1 | Dashboard, tap the **Fit to drive** card, turn the switch on | A consent dialog explains the limits; after *Turn on* the *Set up (0 of 3 done)* card appears |
| I2 | Put the phone in a front trouser pocket and walk normally for 2 min | Step 1 shows *x of 2 min recorded* rising right away; with the phone in the hand the banner says *Not recording* |
| I3 | Tap **Record balance (voice guided)** three times (feet together, phone flat on chest) | The phone speaks each step and the result; Step 2 shows *1 of 3*, *2 of 3*, then ✓; *Before you drive* appears when steps 1 and 2 are done |
| I4 | Walk normally 30 s, tap **Check my walk** | *Normal for you* (or *Unusual*); the time and walking seconds are shown |
| I5 | Wear impairment-simulation goggles (with a spotter) or walk deliberately unsteadily, swaying side to side with irregular steps, for 30 s; tap **Check my walk** | *Very different from your normal*, an amber *Take the balance check* card, the features that differed most |
| I6 | Balance check while swaying | *Better not to drive now* with *Find a cab*; with a trusted contact saved, *Call* and *Message* open the dialer / SMS app with the number filled in |
| I7 | Balance check standing still | *Your balance looks normal* |
| I8 | As a **passenger**, walk 30 s to a car and ride | When the trip is detected, a check runs (*Trip started*); with goggles or unsteady walking, a notification offers the balance check |
| I9 | (Optional) Turn on *Check when my car's Bluetooth connects*, allow Nearby devices, connect to a car | A check runs (*Car Bluetooth*) before the car moves |
| I10 | Walk with the phone in your **hand** | The screen says walks with the phone in the hand are not checked |
| I11 | After a large-gap warning, as a **passenger**, start a trip | A reminder notification *You were advised not to drive*; the screen shows *Stop somewhere safe first* with no test button while moving |
| I12 | Walk slightly unsteadily, then **Check my walk** | If rated *Unusual for you*: a gentle *Your walk was a bit unusual* offer with *Balance check* / *Not now* |
| I13 | Study mode: enter a participant ID, tap *Goggles 1*, walk 1 min, do a balance check, tap *Export CSV* | Banner *learning is paused*; the exported CSV has `segment`, `walk_check` and `balance_check` rows labelled GOGGLES_LOW |
| I14 | Turn the switch off | Nothing is learned; the dashboard card shows *Off* |

---

## Automated tests (run on every GitHub push)

- 114 JVM tests of the sensing engine: `cd PervasiveSense && ./gradlew :app:testDebugUnitTest`
- 24 model tests: `pytest tests/test_tflite_model.py` (see `docs/TESTING.md`)
