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

---

## Automated tests (run on every GitHub push)

- 49 JVM tests of the sensing engine: `cd PervasiveSense && ./gradlew :app:testDebugUnitTest`
- 24 model tests: `pytest tests/test_tflite_model.py` (see `docs/TESTING.md`)
