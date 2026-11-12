# Fit-to-drive check

An opt-in feature that compares the walk to the car with the user's **own**
normal walk and, if the walk is far from it, asks for a 30-second balance check
before driving. No drinking data is needed: the model only ever learns the
user's everyday, sober walking.

It flags walking that is **unusual for this person**, not alcohol itself, and it
only gives advice. It is not a breathalyser and never legal evidence. All
processing and all learned data stay on the phone.

---

## How it works

```
 everyday walking ──► gait segments (8 s, every 4 s) ──► 5-min quarantine ──► personal baseline
                                │                                                  │
 trip starts / car Bluetooth /  ▼                                                  ▼
 "Check my walk" ─────► score the walk before the trip ──► gap vs the user's own walks
                                                               │
                         normal / unusual ◄────────────────────┤
                                                               ▼ large gap
                                       30 s balance check vs the user's own reference
                                          │ normal                    │ unsteady, skipped or moved
                                          ▼                           ▼
                                   "balance looks normal"     "better not to drive":
                                                              find a cab · call / message a trusted contact
```

| Step (slide 8) | Implementation |
|---|---|
| **1. Learn normal** | While enabled, every stretch of walking that DeepSense labels WALKING is cut into 8 s segments (a new one every 4 s) and described by six gait features. During set-up (nothing can be checked yet) segments are learned at once. After that they enter the baseline only after leaving a 5-minute quarantine, and, once the baseline is established, only if the walk around them (about 1 min) is not a *large gap*. In the first week "normal" is often a single day and another sober day can look far from it, so only extreme staggering (score ≥ 5) is dropped then; judging harder discarded real day-to-day variation (sober false alarms 1/24 → 3/24 on MotionSense). So the walk being scored is never part of the "normal" it is compared with, a flagged walk is never learned, and an unsteady walk that nobody checked (say, walking home after drinking) never widens "normal" either. Test walks ("Test it") are never learned. At most 10 min of walking per day is learned, so one long walk cannot dominate. The check starts after about 2 min of walking (*provisional*) and is *established* after 10 min over 7 days. |
| **2. Watch the walk** | The segments of the last 5 minutes are kept. The walk is scored when Google Activity Recognition reports a trip has started (IN_VEHICLE), when a car audio or hands-free Bluetooth unit connects (optional; usually before the car moves), or when the user taps **Check my walk**. Each walk is scored once. |
| **3. Measure the gap** | Per-segment distance from the baseline, walk score = median over the walk, thresholds from the user's own walks (below). |
| **4. Respond** | A large gap raises a notification and asks for the 30 s balance check. A failed check, or one that could not show normal balance, advises against driving and offers a cab search and the trusted contact. |

### Test it (practice)

Once set up, the screen has a **Test it** card with three practice tests, so the
whole flow can be seen in a few minutes without being impaired. Test walks are
scored like a real check but **never learned**, and the result is **spoken**
(the phone is in the pocket, screen locked).

| Test | Do | Expected |
|---|---|---|
| A. Normal walk | Tap *Start*, lock the screen, phone in trouser pocket, walk normally ~30 s | Spoken and shown: *Normal for you* |
| B. Wobbly walk | Same, but sway side to side with uneven steps (someone next to you) | *Very different*, then the *Take the balance check* card |
| C. Balance check while swaying | Feet together, phone on the chest, sway gently from the hips for 30 s | *Better not to drive*, with *Find a cab* / *Call* / *Message* |

Each row shows ✓ when the result was as expected, or a tip to retry.
*I'm not driving* clears the warning afterwards.

### The drunk-driving case

| Situation | What the app does |
|---|---|
| Very different walk **before** the car moves (car Bluetooth, *Check my walk*) | High-priority notification: take the 30 s balance check. Unsteady, skipped or walked off → *Better not to drive now*: find a cab, call or message the trusted contact |
| Very different walk found only once the trip is detected (**already driving**) | The notification says *If you are driving, don't use your phone. Stop somewhere safe first*, with no test button. The screen shows *Stop somewhere safe first*. The balance test is refused while in a vehicle |
| **Slightly** unusual walk before driving | A gentle offer: *Your walk was a bit unusual. Take the balance check?* A failed check → *don't drive*. When found only while driving: no prompt for a mild case |
| Starts a trip **after** being warned | Reminder: *You were advised not to drive. If you are driving, stop somewhere safe.* |
| Taps **"I'm not driving"**, then drives anyway | For 1 hour after a real warning (unless a balance check passed) a trip still brings the reminder (*You said you weren't driving…*) and the **driving is watched** (below). With the opt-in contact alert, the car's Bluetooth connecting or unsteady driving texts the trusted contact after a 30 s cancel countdown |
| Drives **unsteadily** after a warning | Weaving, jerky steering or repeated hard braking: notification and voice *Your driving looks unsteady. Please stop somewhere safe*; with the opt-in alert, the contact is texted after the countdown |
| Phone in the **hand** on the walk | Not learned and not scored: the baseline is pocket walking. The proximity sensor tells pocket from hand, and the screen asks to keep the phone in the pocket |
| Tries to record a "normal balance" while possibly impaired | Refused during any warning or offer. A recording far less steady than earlier ones (≥ 1.75×) is rejected |
| Mildly drunk evenings over weeks | Only walks scored *normal* are learned, so impaired walks never drift into "normal" |
| Walks home unsteady and **never checks** (no trip) | When the walk leaves the 5-min window it is scored. Once established (7 days) a large gap is never learned; in the first week only extreme staggering is dropped. Test walks are never learned |

### After the warning: reminder, driving watch, contact alert

A real warning (a large walking gap or a failed balance check; "Test it" practice
results never count) starts a **1-hour watch**. Only a passed balance check ends it
early; "I'm not driving" hides the advice but does not end the watch.

| During the watch | What happens |
|---|---|
| A trip starts / the car's Bluetooth connects | Reminder notification (once per trip). Possibly impaired walking is never learned |
| Driving looks erratic | Notification + voice: *Your driving looks unsteady. Please stop somewhere safe* (at most every 10 min) |
| Contact alert (opt-in) | When the car's Bluetooth connects **and** a trip starts within 10 min (Bluetooth alone may be earbuds that call themselves hands-free) or the driving looks erratic: a **30 s countdown** (spoken, with *Cancel – I'm a passenger* on the screen and in the notification), then a text message to the trusted contact from the user's own phone, with a Google Maps link to the location if location was allowed. At most one alert of each kind per hour. A trip start alone never texts anyone (it may be a cab) |
| "Cancel – I'm a passenger" | The alert is not sent and the driving is no longer watched on this trip |

**Driving watch** (`DrivingWatch.kt`): works with the phone anywhere in the car,
because it only uses orientation-free signals.

| Event | Signal | Rule |
|---|---|---|
| Weaving | Turn rate = gyroscope · gravity direction (the car turning), band-passed 0.003–0.6 Hz so pedal/leg movement and long bends drop out | 6 alternating left/right swings ≥ 0.05 rad/s within 30 s (a turn is 1 swing, a lane change 2, a roundabout about 3) |
| Jerky steering | Same turn rate | Changes by ≥ 0.4 rad/s within 0.5 s (a jerk of the wheel, a swerve) |
| Harsh braking / acceleration | 0.5 s mean acceleration vector m; h = min(√(\|m\|² − g²), part of m across the 20 s "up" direction). The length ignores the phone tipping in a pocket; the cross part ignores speed breakers and potholes (vertical) | h ≥ 4 m/s² (real braking of about 6 m/s² and more) |

Driving is *erratic* after one weaving episode or 3 jerky/harsh events within 5 minutes.
A trip lasts from the first vehicle detection until 3 minutes without one, so red lights and
traffic jams (where detection drops) do not start a "new trip" that forgets *I'm a passenger*
or the driving events seen so far.

**Outside factors handled:** a practice test (*Test it*) never causes a reminder or an alert, and a
real trip clears its advice and checks the walk as usual; a real warning and the 1-hour watch are
saved at once and survive the app being killed or the phone restarting; a balance check far
steadier than the user's own recordings (ratio < 0.35: phone put on a table, or sitting) is
*not valid*, which after a warning means *better not to drive*, and cannot become a "normal
balance" recording either.
Tests (synthetic drives, 4 phone orientations): 5 min of city driving with turns,
lane changes and stops gives nothing; weaving is found in every orientation;
3 hard brakes make it erratic, 1 does not; a swerve is jerky steering; the phone
tipping over in the pocket is not braking.

**Limits.** Gentle drifting within one lane at highway speed turns the car too little to
be seen (tested: not detected). Winding roads with quick S-bends can look like weaving.
The phone cannot tell driver from passenger, so it only acts after a real warning,
always counts down before texting, and never sends anything without the user's opt-in.
The thresholds come from vehicle dynamics, not from impaired drivers; a safe check is a
sober driver doing slow slaloms between cones in an empty car park.

### Gait features

All features are independent of how the phone sits in the pocket (tested by
rotating real recordings). Gravity is the mean acceleration of the segment.
It splits each sample into a vertical part and a horizontal plane. The weaker
horizontal axis is side-to-side; the dominant one is forward-backward.

| Feature | Impairment moves it | Computed from |
|---|---|---|
| Cadence | either way | Stride time = first strong autocorrelation peak of the **leg-swing signal** |
| Step timing | up | Coefficient of variation of stride-to-stride intervals (swing peaks, sub-sample timing) |
| Stride regularity | down | Vertical-acceleration autocorrelation at one stride |
| Side-to-side sway | up | Side-to-side RMS ÷ vertical RMS |
| Irregular sway | up | Stride-to-stride change in side-to-side acceleration ÷ vertical RMS |
| Turning steadiness | up | Stride-to-stride change in turning rate (gyro about gravity); not used without a gyroscope |

**Leg-swing signal.** In a trouser pocket the thigh rotates the phone forward
and back exactly once per stride. The principal axis of the low-passed
gyroscope is therefore a clean stride-periodic signal. On phones without a
gyroscope, the same swing tilts gravity and the accelerometer is used. Gait
timing is taken from this signal, not from step peaks. The first prototype used
step-detector peaks: in a pocket they gave a 25 % step-time CV for normal
walking and up to double-counted cadence. The swing signal gives about 2 %,
which matches healthy-gait literature.

The stride-to-stride differences cancel the periodic leg swing and slow,
deliberate turns, so only aperiodic staggering remains.

### Scoring

- **Per feature:** robust z = (x − median) / max(1.4826·MAD, resolution floor, 5 % of median), counted only in the direction impairment moves that feature (walking more steadily than usual is never "far"), capped at 8.
- **Per segment:** RMS of the z-scores.
- **Per walk:** the **median** over the walk's segments (up to the last 15, about 1 min). Most of the walk has to be unusual; a sharp turn, a stop at a door or a kerb does not flag a walk.
- **Thresholds:** percentiles of the same statistic over runs of consecutive segments of the user's own learned walks. *Unusual* is the 95th percentile (at least 1.2); *large gap* is the 99th (at least 1.8). The screen reports the percentile ("more unusual than 99 % of your normal walks") and names the features that moved most.

### Balance check

The user stands with feet together, eyes open, holding the phone flat against
the chest for 30 s. Eyes stay open on purpose: closing them is the more
sensitive Romberg variant, but unsafe for someone who may be impaired. The first
5 s are for getting into position. Sway is the RMS of the gravity-free
horizontal acceleration and of the angular velocity. It is compared with the
median of up to five **personal reference** tests recorded at a normal moment.
The result fails at 1.75× the reference sway (geometric mean of the
acceleration and gyroscope ratios). A tilt change above 25° or a walking-sized
acceleration means the person moved or stepped. A beep and vibration mark the
start and end, because the screen is against the chest.

After a large walking gap, only a normal balance result clears the warning.
"Moved", "no reference" and "failed" all lead to *better not to drive*.

### Response and safeguards

- **Opt-in** with a consent dialog that states the limits. Nothing is recorded while the check is off.
- **Advice only.** *Find a cab* opens any maps app with a "taxi" search. *Call* and *Message* open the dialer and SMS app with the trusted contact's number filled in; the user sends. The only automatic message is the **opt-in** contact alert (off by default, SMS permission asked when turned on, always after a 30 s cancel countdown).
- **Never learns from a warning.** Nothing is learned while a warning or offer is active, and a balance reference cannot be recorded then.
- **Set-up, three steps in order** (each unlocks after the previous one):
  1. **Record your walk:** tap *Start walk recording*, lock the screen, walk 2 min with the phone in a trouser pocket. The phone speaks: *Recording your walk* (within about 15 s), *Halfway there*, *Your walk is recorded*; or *Nothing recorded yet…* after 1 min, and it stops after 7 min. Everyday pocket walks also count.
  2. **Record your standing balance** 3 times (stand still, not a walk); the newest 3 are kept.
  3. Trusted contact (optional, can be skipped).
- **Pocket detection with the screen off:** many phones stop reporting the proximity sensor once the screen is off, leaving it stuck at "uncovered". A walk with the screen off therefore counts as a pocket (or bag) walk. During the guided walk, steady stepping (≥ 4 steps in 3 s) also counts as walking, in case the activity model misses it.

  "Before you drive" and "Test it" appear once the walk and the balance are set up.
- **Voice-guided balance test:** the phone says what to do ("feet together, phone on your chest… now hold still… ten seconds left… done, your balance looks normal"), beeps and vibrates, and keeps the screen on. Without a voice engine, the beeps and vibration still mark each step.
- **Study mode** (for the goggle study): label a session (*Sober*, *Goggles 1*, *Goggles 2*) and a participant ID. While labelled, nothing is learned. Every walking segment (six features and its distance from normal), every walk check and every balance result is appended to a private CSV, exported with *Export CSV*.
- Advice expires after 1 hour. "I'm not driving" hides it, but trips and driving stay watched for the hour after a real warning.
- Learned data: `files/fit_to_drive.json` (written atomically). Settings: `shared_prefs/fit_to_drive.xml`. "Forget my learned walk and balance" deletes both.

---

## Evaluation

### 24 people, real sober variation (`FitToDriveEvaluationTest`)

For every one of the 24 MotionSense participants (phone in the front trouser
pocket), the real `FitToDriveMonitor` learned "normal" from two walking
sessions (wlk_7, wlk_8, about 2.5–4 min). It then scored, as a trip started:

| Walk scored | Large gap | Unusual or large |
|---|---|---|
| Their **third, held-out sober session** (wlk_15, a different recording session) | **1 / 24** | 2 / 24 |
| Same walk, simulated **strong** impairment | **24 / 24** | 24 / 24 |
| Same walk, simulated **mild** impairment | 18 / 24 | **23 / 24** |
| Another person's sober walk against this person's baseline | 18 / 48 | – |

- **The false alarm:** the one sober large gap (person 24) comes from the turning feature. That person has the smallest baseline (2.6 min), and the held-out session followed a different route. A real 1–2-week baseline covers many routes.
- **Personal, not population-based:** a different person's *normal* walk looks like a large gap 38 % of the time. One shared model would be blind to many individual differences.
- **Simulated impairment:** no labelled intoxicated recordings exist in public phone datasets. Impairment is therefore built from physics on top of each person's real held-out walk (`GaitTestSignals.kt`), scaled by severity (strong = 1.0, mild = 0.5):
  - stride-to-stride playback-rate jitter (±15 %) and 8 % slower walking;
  - band-limited 0.3–1.5 Hz side-to-side acceleration (0.8 m/s² RMS);
  - 0.5–2.5 Hz heading wobble (0.4 rad/s RMS).

  These numbers are assumptions, not measurements. The detection rates show the method responds to the gait changes reported for alcohol. They are **not** a measured accuracy for real intoxication.

Run it locally with `MOTIONSENSE_DIR=<motion-sense/data>`; CI downloads the dataset and runs it on every push.

### Unit tests (60 more, always run)

| Class | Tests | What it proves |
|---|---|---|
| `GaitSegmentExtractorTest` | 5 | Cadence within 8 % of an independent gyroscope-spectrum reference; healthy stride CV < 5 %; features identical under 3 phone rotations (this caught a sign-ambiguity bug); no segments while sitting, standing, not walking or interrupted; works without a gyroscope; impairment moves every feature the expected way |
| `GaitBaselineTest` | 4 | Thresholds are percentiles of the user's own walks; one-sided scoring; median walk score; daily quota and rolling capacity |
| `FitToDriveMonitorTest` | 39 | Held-out session normal, impaired walk large gap with an explanation; quarantine and never learning a flagged walk; not scored while learning; trip without a recent walk; each walk scored once; car-Bluetooth trigger; manual check waits for a walk or gives up; disabled = nothing learned; state survives a restart; balance sway is orientation-free and detects walking off; pass/fail against the personal reference; after a large gap only a normal balance clears it; advice expiry; progress. **Drunk-driving cases:** slightly unusual walk → balance offer and never learned; found only while driving → no prompt; driving after a warning → reminder; no balance test in a moving vehicle; implausible normal balance rejected; study session learns nothing; walks in the hand ignored. **Set-up:** the guided walk says recording / halfway / done, or nothing yet and then gives up; walking counts at once while learning; live recording status; 3 balance recordings kept. **Learning safety:** once established, an unchecked very unsteady walk is never learned, an unchecked normal walk is. **Test it:** practice walks are scored (normal → *normal*, wobbly → *large gap* and the balance check) and never learned; a practice walk gives up or can be cancelled. **After "I'm not driving":** a trip within the hour still gets one reminder, none after the hour; a passed balance check ends the watch; weaving after a warning is reported once, not for a confirmed passenger; without a real warning (or after a practice one) driving is never reported. **Outside factors:** practice tests B and C followed by the car's Bluetooth and a trip give no reminder and no alert; a warning survives a restart for the rest of its hour (and an active one is shown again); car Bluetooth alone never alerts, Bluetooth plus a trip within 10 min does; a phone on a table is not a balance pass nor a normal-balance recording; a trip goes on through red lights and ends after 3 min out |
| `DrivingWatchTest` | 8 | 5 min of city driving (turns, lane changes, stops, pedal leg movement) is not erratic in 4 phone orientations; weaving is found in all 4; gentle highway drift is not (documented limit); 3 hard brakes are erratic, 1 is not; a swerve is jerky steering; the phone tipping over in the pocket is not braking; without a gyroscope only braking is judged; speed breakers and potholes (up to 8 m/s²) are not braking in any orientation |
| `FitToDriveEngineTest` | 4 | End to end through the real-time pipeline (jittered device events → resampler → DeepSense gate → gait → trip start → large gap → advice in the snapshot; balance test refused in the car); the proximity sensor decides pocket walks; with the screen off a walk counts as a pocket walk even if proximity is stuck; the guided walk also counts steps when the activity model misses the walk |

### Dose-response

On one person's held-out walk, the walk score rises steadily with simulated impairment severity:

| Severity | 0.1 | 0.2 | 0.3 | 0.35 | 0.4 | 0.5 | 1.0 |
|---|---|---|---|---|---|---|---|
| Typical walk score | 0.4 | 0.6 | 1.0 | 1.5 (unusual) | 1.8 (large gap) | > 2 | > 4 |

The thresholds for this person were 1.2 (unusual) and 1.8 (large gap).

### Cost

Measured on the JVM (desktop):
- gait analysis: about **1.8 ms of CPU per minute of walking** (0.6 µs per sample);
- baseline statistics: refreshed only after 5 % of the baseline is new, a few ms for a full 900-segment baseline;
- memory: about 20 KB per 900-segment baseline.

---

## Limitations

- **What it detects:** unusual gait for this person. Tiredness, injury, new shoes, a heavy bag, carrying a child or icy ground can also cause a gap. This is why it only advises and asks for a balance check before suggesting not to drive.
- **Phone placement:** the evaluation and the leg-swing timing assume a trouser pocket. Carrying the phone in the hand on the walk to the car changes the features. Mixed carrying habits widen the personal baseline.
- **Timing of the trip trigger:** Activity Recognition reports a trip 30–60 s after the car starts moving. The car-Bluetooth trigger (car audio or hands-free units only) and the manual check cover "before driving". When the trip trigger is the first to fire, the app only tells the person to stop safely and never offers a test while moving.
- **Pocket detection:** the proximity sensor is covered in a pocket, but also in a bag. Bag walks are not excluded.
- **Passenger vs driver:** the check cannot tell them apart. "I'm not driving" hides the advice; "Cancel – I'm a passenger" stops the watch for the trip. The car-Bluetooth trigger usually fires only for the owner's own car.
- **Driving watch:** in-lane drift at highway speed is not visible to the phone; quick S-bends can look like weaving; thresholds are from vehicle dynamics, not impaired drivers.
- **Contact alert location:** needs the location permission; on some phones it is only available while the sensing service was started from the app.
- **Balance threshold:** the 1.75× threshold and the walking-gap percentiles are set from the method, not from intoxicated data. Both must be calibrated in the field study below.
- **Research basis:** Suffoletto et al. (*J. Stud. Alcohol Drugs* 81(4):505–510, 2020) predicted breath alcohol above 0.08 % from 10-step walks with 92.5 % accuracy (naive baseline 88.2 %). The study had 17 participants in a lab, with the phone on the lower back, and side-to-side sway as the most informative signal. It was preliminary and not replicated in the field.

## Field validation plan (impairment-simulation goggles)

Goggles that simulate a target BAC give impaired gait without anyone drinking.

1. **Baseline:** 10 volunteers carry the phone normally for 1–2 weeks with the check on (learning only), and record 3 balance references.
2. **Sessions:** each volunteer walks a fixed 30 m route to a parked car, with and without goggles (two goggle levels, order randomised), with the phone in the front trouser pocket. They then do the balance check. In the app: *Study mode*, enter the participant ID, select *Sober*, *Goggles 1* or *Goggles 2* before each walk, tap *Check my walk*, then *Balance check*. Select *Off* afterwards. *Export CSV* gives one row per segment, check and balance result, labelled with participant and condition.
3. **Measure:**
   - detection rate (large gap) with goggles;
   - false-alarm rate on sober sessions and on everyday trips over the 2 weeks;
   - balance pass/fail rates.
4. **Calibrate:** the large-gap percentile and the 1.75× balance threshold from these data, then re-run on new volunteers.

Safety: a spotter walks alongside, sessions use a flat, closed route, and nobody drives at any point.

---

## Files

| File | Role |
|---|---|
| `GaitSegmentExtractor.kt` | Segments and the six orientation-independent gait features |
| `GaitBaseline.kt` | Personal robust model, walk score, percentile thresholds |
| `BalanceTest.kt` | 30 s standing-sway measurement |
| `FitToDriveMonitor.kt` | Learning, quarantine, triggers, scoring, balance decision, advice, the 1-hour watch after a warning |
| `DrivingWatch.kt` | Weaving, jerky steering and harsh braking from an unmounted phone |
| `ContactAlert.kt` | Opt-in alert to the trusted contact: "Are you OK?" or countdown, cancel, location, SMS, call |
| `AlertActivity.kt` | The alert countdown over the lock screen (I'm OK / I'm a passenger) |
| `WatchdogReceiver.kt` | Restarts sensing if the phone stopped it (every 15 min, after a swipe) |
| `FitVoice.kt` | Spoken guidance and results |
| `FitToDriveStore.kt` | Settings and atomic persistence of learned data |
| `StudyLog.kt` | Labelled study sessions: CSV log and export |
| `FitToDriveActivity.kt` | Opt-in, progress, last check, balance test, response |
| `SensingEngine.kt` / `SensingForegroundService.kt` | Feeds every 50 Hz sample; trip-start and car-Bluetooth triggers; alert notification |

## Carrying the phone anywhere on the body (v3.0)

The walk is no longer limited to the trouser pocket. The user records their normal walk once
for each way they carry the phone (one tap: **trouser pocket**, **in the hand**, **elsewhere on
the body**: jacket, shirt, belt, bag), about 1 minute of walking each. Each way has its own
learned walk, and every check is compared with the matching one:

- *In the hand*: screen on and the proximity sensor uncovered (held and looked at).
- Otherwise the nearest of the user's own recorded positions by a 3-number, orientation-free
  signature of how the phone moves on the body (gyroscope RMS, |a| RMS, horizontal/vertical
  dynamic acceleration). On Shoaib et al. 2014 (10 people, 5 phones worn at once) this
  recognises the position 97.9 % of the time.
- Everyday walking is learned into a position only if it moves like it, and an 8 s segment
  during which the phone was picked up or put away is never learned.
- A walk carried in a way never recorded (e.g. an armband) is flagged as *unfamiliar*: a big
  gap then only offers the balance check (no warning), and the app asks to record that way.

**Accuracy per position** (`FitToDrivePositionsTest.accuracyPerPositionOnTenPeople`, CI):
each person records left pocket, wrist (hand swinging) and belt from 60 % of their walk; the
other 40 % is checked sober and with simulated impairment (x1.0).

| Phone | Position matched | Sober walk flagged "very different" | Impaired walk caught |
|---|---|---|---|
| Left trouser pocket | 10/10 | 0/10 | 10/10 |
| Right pocket (not recorded, matched to left) | 9/10 | 0/10 | 10/10 |
| Wrist / hand | 10/10 | 0/10 | 10/10 |
| Belt | 10/10 | 0/10 | 10/10 |
| Upper arm (never recorded) | unfamiliar 9/10 | 1/10 | 10/10 (balance check offered) |

Limits: impairment is simulated; held-in-hand-and-looked-at walking is not in the dataset (it
is detected from the screen and proximity sensor, not from motion); a bag swinging from the
shoulder is not attached to the body and is less reliable.

## v3.1: field-test fixes

- **Check my walk no longer says "no walking" when you did walk.** Scoring needs about 16 s of steady
  walking (3 segments of 8 s). Starting late or turning in a small room left less than that in the 30 s.
  Now, if steps were counted (or you are still walking) and too little was measured, it says "keep
  walking". It goes on for up to 90 s and scores as soon as there is enough. Standing still still
  gives "no walking" at 30 s. The hint and the spoken prompt name the way you recorded (for example,
  in the hand), not always the pocket.
- **Contact alert SMS.** The phone answered "error 4" (no service on the SIM used) while texts from
  Messages went out. The alert now tries every SIM: the default SMS SIM, then the calls SIM, the
  mobile-data SIM, and all active SIMs (with the optional phone-state permission). A real alert
  that finds no signal is tried again every 30 s, up to 4 more times. If it still fails, the screen says why in
  plain words and shows **Send it from Messages**, with the alert already written in.
- The location in the text had lost its space ("needed.Location"). Fixed.

## v3.2: a wobbly walk in the hand gets the full warning

On the phone, a wobbly walk with the phone held in the hand was flagged "a way you haven't recorded"
and downgraded from **very different** to **a bit unusual**. Wobbling swings a held phone much more
than a normal walk, so the motion alone looked like another way of carrying it. "Unfamiliar" now applies only
when the position is guessed from the motion. Held and looked at (or chosen for the set-up walk), the
position is certain and the walk gets its full result. The real-person accuracy per position is unchanged.

## v3.3: one way recorded means the check knows the position

Many phones' proximity sensor gives no reading until it is first covered, so "held and looked at" can
be missed. Check my walk with only one way of carrying recorded now treats that way as known: the
screen and voice tell the user to walk that way. A wobbly walk then gets its full result, never the
"a way you haven't recorded" downgrade. The test for this fails without the fix and passes with it.

## v3.4: no "fall" when the phone is put on a table; SMS on every SIM slot

- **Fall detector.** On the phone, putting it on a table said "Fall detected": one low-g sample, one
  table tick above 2.5 g, a tilt change (hand to flat) and stillness passed every check. Now a fall
  needs three more things:
  - a falling phase of at least 0.1 s (5 samples below 0.75 g);
  - a body's impact: 3 samples above 1.5 g within 0.4 s, where a phone hitting a table rings for one;
  - the phone not held and looked at in the 2 s before.

  Tests: the old rules still alarm on the table scenarios (the bug reproduced), the new rules never do.
  Genuine falls and the real daily-activity recordings give the same results as before.
- **SMS.** Without the phone-state permission Android hides the second SIM, so only the default one
  was tried: one failed text in the thread, not two, and the "every SIM" message was wrong. Now
  every SIM slot is tried without any permission (Android 10+), the SIM that worked last time goes
  first, and the message says how many SIMs were tried. If no SIM can text (on the test phone Messages
  could not send either), **Send on WhatsApp** sends the alert over the internet in one tap. It is on
  the screen and in the notification.

## v3.5: the contact is also phoned; driving watched after an "unusual" walk; phone use is not "erratic"

- **Call.** After the alert text, the phone calls the contact on speaker ("Also phone them", on by
  default once the call permission is allowed). The call goes through Android's phone app, so it
  works with the screen locked and the app in the background, and it can get through when a text
  cannot. A dual-SIM phone set to "ask every time" uses the SIM that last sent a text. If the call
  cannot be placed, a notification calls in one tap. The test alert calls too.
- **An "unusual" walk ignored.** Before, only a "very different" walk (or a failed balance check)
  armed the driving watch. Now a real "a bit unusual" walk also arms it for an hour, for erratic
  driving only (weaving, jerky steering, harsh braking: countdown, then text and call). No reminder
  is raised for it. A later normal walk or a passed balance check ends it; it survives a restart.
- **Phone use while driving.** Picking the phone up and using it moved the phone like a swerve: in
  the tests, 25 s of use three times made a calm drive "erratic". The hand tilts the phone fast
  (over 1.5 rad/s) or far (over 55° from the slow "up"; braking at 1 g tilts the mean acceleration
  only 45°), so nothing is judged while it is moved and for 3 s after. Picked up with the screen on, it
  counts as in the hand until the screen goes off or it rests still for 10 s (put back in a holder).
  Tests: the old rules call the phone use erratic, the new ones never do; a phone in a holder with
  navigation on still catches weaving; hard braking right after the phone is put away and emergency
  braking at 1 g are still caught.
- **Location.** The fresh fix during the 30 s countdown now comes from the network and GPS at once
  (the more accurate wins); an older fix says its age in the text.
- **Screen locked.** Sensing already ran with the screen off (foreground service with a wake lock).
  Now the app asks to be left out of battery optimisation (Samsung "sleeping apps" can stop it), and
  sensing starts again after the phone restarts or the app is updated, if it was running.
  After a restart the location is added again once the app is opened (Android does not let a
  service started at boot use the location).
- **Study mode** (labelled goggle sessions for research) is hidden. Long-press "How it works and
  its limits" to show it; it shows by itself while a session is labelled.

## v3.6: every drive is watched; "Are you OK?" first; the watch restarts itself

- **The user never checks the walk and drives anyway.** Before, weaving, jerky steering and harsh
  braking were measured on every trip but only reported within an hour of a warning. If the walk to
  the car was not measured (too short, phone in a bag), nothing was reported. Now, with the contact
  alert on, every trip is watched with a higher bar: within 5 minutes, 6 points, a weaving episode
  counting 2 and a jerky or harsh event 1 (about a minute of steady weaving, weaving plus hard
  brakes or jerks, or six hard brakes or jerks). After a warning the lower bar stays (one weaving
  episode or three events).
- **Asked first.** Without a warning, the phone speaks "Your driving looks unsteady. Are you OK?",
  vibrates, and opens a full-screen alert over the lock screen (no unlocking needed) with **I'm OK**
  and **I'm a passenger**. The question is spoken again at 30 s and 10 s. No answer within 60 s: the
  contact gets the text with the location, then the call. **I'm OK** stops the question for 15 minutes
  on that trip; **I'm a passenger** stops it for the whole trip. After a warning there is no "I'm OK"
  (someone impaired would also say they are OK): only "I'm a passenger" cancels the 30 s countdown.
- **Swiped away or stopped by the phone.** A foreground service normally survives being swiped from
  the recent apps. If the phone's battery saver stops it anyway, a watchdog alarm (every 15 minutes,
  and 5 s after the app is swiped away) starts it again. If Android refuses (no battery exemption),
  a notification says "PervasiveSense stopped" and turns it back on with one tap. The Fit to drive
  settings show whether the app may run when closed and open over the lock screen, with a **Fix**
  button (and the Samsung "Never sleeping apps" step).
- **Stop sensing** on the dashboard now really means stopped: before, the restart after a reboot
  could turn it back on.
- **Tests (6 new):** a minute of weaving with no warning asks the driver first; a short weave, three
  hard stops in traffic, ordinary city driving and a hill road with a curve every 7 s are never asked
  about; six hard brakes are; "I'm OK" rests the question for 15 minutes; a passenger is never asked.
- **Limits:** "Force stop" in the app settings (or uninstalling) stops everything until the app is
  opened again: Android allows no way around it. A very twisty road (a curve every 5 s for over a
  minute) can be asked about; the driver answers "I'm OK". Android 14 opens the alert over the lock
  screen only if the full-screen permission is allowed (the **Fix** button opens it); otherwise it
  is a heads-up notification plus the spoken question.

## v3.7: Fit to drive first on the dashboard

The Fit to drive card is now the first and largest card on the dashboard. It shows one large
headline for the state that matters now (Not turned on, Finish set-up, Ready, Normal for you,
Better not to drive now, Not watching when sensing is stopped), one line of detail (for example
the time and trigger of the last check), whether every drive is watched and who would be alerted,
and one large button for the next step: Turn on, Continue set-up, Check my walk (starts the check
on the Fit to drive screen), Balance check, Find a cab or Start sensing. The border takes the
state's colour. No button is offered while in a vehicle.

## v3.8: the phone anywhere in the car or on the body; "Test drive (no car)"

- **Anywhere.** The driving watch is now tested with the phone in a trouser pocket, a shirt or
  jacket pocket, on the belt, in a bag on the seat or floor, rattling in a cup holder or door
  pocket, in a dashboard holder, and lying on the seat. In every place, 5 minutes of city driving
  is never erratic, a minute of weaving is caught, and a real swerve is jerky steering.
- **The driver's body is not the car.** With the phone in a shirt pocket, a look over the shoulder
  turns the phone as fast as a swerve. In the tests, a shoulder check every 20 s made a calm drive
  erratic (the control, kept as a test). Now:
  - the car's forward axis in the phone is learned from braking and pulling away on a straight
    road, so sideways acceleration is measured apart from braking;
  - a jerk of the wheel needs the car to be thrown sideways (the sideways acceleration changes by
    1 m/s² within 1 s); the body turning does not do that, even in a bend or while braking;
  - a fast swing (over 0.3 rad/s) with no sideways push is the body, not a weaving swing.
  The sideways push is measured as a change of the horizontal acceleration vector: the old
  difference of two lengths of about 1 g was too noisy (up to 1.4 m/s²).
- **Test drive (no car).** Fit to drive settings, under *Send a test alert*: for 5 minutes the
  driving watch runs on the phone's real sensors as if in a vehicle. Turning left and right on a
  swivel chair (phone in a pocket, screen locked) looks like weaving to the gyroscope: the phone
  asks "Are you OK?" over the lock screen exactly as on a real trip; no answer sends the text
  (marked TEST) and the call. Sitting still asks nothing. When the 5 minutes end the phone says
  whether unsteady driving was noticed. A test never blocks a real alert. Only the vehicle
  detection is skipped, and the check that ignores body turns is off (a chair turn is a body turn).
- **Limit:** the phone **held in the hand** while driving cannot be judged (the hand moves it more
  than the car); it is judged again as soon as it is put down or away.
- **Tests (7 new, 150 JVM tests).**

## v3.9: the test drive works the way people actually try it

On the phone, turning a swivel chair did nothing. Two reasons, both reproduced in tests:

- **The legs pushing the chair** jolt a phone in the trouser pocket as fast as a hand would
  (3 rad/s). The test drive kept the hand check of a real drive, so every push wiped the turning
  seen so far (the control test still shows this with the old settings). The test drive now never
  stops judging because the phone moved, so it also works with the phone held in the hand.
- **Spinning round and round one way** is a roundabout, not weaving; weaving is left, then right.
  The test drive now coaches out loud, since the phone is in a pocket:
  - "I can feel the turning. Keep going: left, then right." at the first turn;
  - "You are turning round one way. Turn back the other way." after 4 s of spinning one way;
  - "Score 2 of 6", "Score 4 of 6"... each time the score goes up; at 6 it asks "Are you OK?".
  The score also shows on the dashboard card and the Fit to drive screen.

Tests (6 new, 156 JVM tests): realistic chair turning (random 15-60 degree swings over 0.6-1.5 s,
legs pushing at every reversal), five attempts each with the phone in a trouser pocket, a shirt
pocket and held in the hand with the screen on, all reach the score; spinning one way never does
and is told to turn back; and the whole pipeline from raw, jittered sensor events (resampling,
engine, fit-to-drive) asks "Are you OK?" exactly once.

## v3.10: the fall card says why a drop was not a fall

On the phone, dropping it 1 m onto a pile of books gave no fall and no explanation. That is by
design: since v3.4 a fall needs a body's impact (three samples above 1.5 g within 0.4 s); a phone
landing on books or a table rings for one sample, exactly like the table put-down that once said
"Fall detected". And a phone dropped from the hand with the app on screen counts as being put down.
But the screen said nothing. Now, after every drop of about 45 cm or more (0.3 s of free fall), the
Fall detection card says, for 10 minutes, *Last drop at 19:42 (3.4 g):* and one of: a fall; not
counted (in the hand with the screen on, lock the screen to test); not a fall, a hard landing (use a
cushion or mattress); not a fall, it moved again within 8 s; not a fall, it landed the same way up;
not judged, sensor data missing. Steps, jogging and stairs never show a reason (a step in a pocket
stays light for about 0.2 s; real recordings tested).

Tests (2 new, 158 JVM tests): every kind of drop gets its reason; everyday movement never does.

## v3.11: a fall alerts the contact; the alert opens on every phone; status lines finish

- **A fall now reaches the trusted contact.** Before, a confirmed fall only posted a notification.
  Now it asks "Did you fall? Are you OK?" over the lock screen, out loud on the alarm volume, with
  a buzz that repeats until answered: **I'm OK** (acknowledged), **Get help now** (the text and the
  call at once), or no answer in 60 s: the text with the location, then the call. A person who gets
  up and walks calls it off. Falls are never limited to one alert an hour. Without a contact the
  phone still asks out loud and shows the alert.
- **Test fall detection** (Fall detection card, 3 minutes): a drop from the hand with the screen on
  counts, and each drop is explained out loud ("Impact felt", "That landing was hard, like a table",
  "too soft", "it moved", "it landed the same way up"); a detected fall sends a TEST alert.
- **Too soft:** a real drop that never lands hard (a deep pillow) now says so.
- **The alert opens on every phone:** Xiaomi, Oppo, Realme, Vivo and others block full-screen
  notifications from the background. With "Display over other apps" allowed, the alert screen is
  opened directly. The settings status now checks four things (runs when closed, notifications and
  the alert channel, lock screen, pop-up over other apps) with one **Fix** button, plus the brand's
  own switches (Samsung, Xiaomi, Oppo/Realme/OnePlus, Vivo).
- **Heard on silent:** alert speech plays on the alarm volume (silent, vibrate, Do Not Disturb with
  alarms allowed).
- **"Calling ..." no longer stays:** after the call ends (or a minute, without the phone-state
  permission) it becomes "Alert sent to ... at HH:MM: text and call.", and status lines clear after
  10 minutes. A failed text stays until it is sent another way.
- Tests (4 new, 162 JVM tests): a drop from the hand is ignored normally and counts in a fall test
  (end to end from raw sensor events), a hard landing in a test is explained, a fall clears itself
  when the person walks, a deep-pillow drop is "too soft".

## v3.12: a fall never calls

- **A fall alert is the text only.** No answer in 60 s (or **Get help now**) sends the text with the
  location ("I may have fallen... Please call me now."); the phone no longer calls the contact
  afterwards, even with **Also phone them** on. The phone may be lying away from the person, and a
  call on speaker from the floor helps nobody; the contact calls back instead. Driving alerts still
  text and then call. The status line reads "Alert sent to ... at HH:MM".
- Tests (1 new, 163 JVM tests): a fall alert never calls; every driving alert and the test alert may.
