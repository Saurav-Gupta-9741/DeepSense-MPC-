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
| **1. Learn normal** | While enabled, every stretch of walking that DeepSense labels WALKING is cut into 8 s segments (a new one every 4 s) and described by six gait features. Segments enter the baseline only after leaving a 5-minute quarantine unflagged, so the walk being scored is never part of the "normal" it is compared with, and a flagged walk is never learned. At most 10 min of walking per day is learned, so one long walk cannot dominate. The check starts after about 2 min of walking (*provisional*) and is *established* after 10 min over 7 days. |
| **2. Watch the walk** | The segments of the last 5 minutes are kept. The walk is scored when Google Activity Recognition reports a trip has started (IN_VEHICLE), when a car audio or hands-free Bluetooth unit connects (optional; usually before the car moves), or when the user taps **Check my walk**. Each walk is scored once. |
| **3. Measure the gap** | Per-segment distance from the baseline, walk score = median over the walk, thresholds from the user's own walks (below). |
| **4. Respond** | A large gap raises a notification and asks for the 30 s balance check. A failed check, or one that could not show normal balance, advises against driving and offers a cab search and the trusted contact. |

### The drunk-driving case

| Situation | What the app does |
|---|---|
| Very different walk **before** the car moves (car Bluetooth, *Check my walk*) | High-priority notification: take the 30 s balance check. Unsteady, skipped or walked off → *Better not to drive now*: find a cab, call or message the trusted contact |
| Very different walk found only once the trip is detected (**already driving**) | The notification says *If you are driving, don't use your phone. Stop somewhere safe first*, with no test button. The screen shows *Stop somewhere safe first*. The balance test is refused while in a vehicle |
| **Slightly** unusual walk before driving | A gentle offer: *Your walk was a bit unusual. Take the balance check?* A failed check → *don't drive*. When found only while driving: no prompt for a mild case |
| Starts a trip **after** being warned | Reminder: *You were advised not to drive. If you are driving, stop somewhere safe.* Nothing is sent to anyone; the app only nudges |
| Phone in the **hand** on the walk | Not learned and not scored: the baseline is pocket walking. The proximity sensor tells pocket from hand, and the screen asks to keep the phone in the pocket |
| Tries to record a "normal balance" while possibly impaired | Refused during any warning or offer. A recording far less steady than earlier ones (≥ 1.75×) is rejected |
| Mildly drunk evenings over weeks | Only walks scored *normal* are learned, so impaired walks never drift into "normal" |

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
- **Advice only.** *Find a cab* opens any maps app with a "taxi" search. *Call* and *Message* open the dialer and SMS app with the trusted contact's number filled in; the user sends. No call or message is ever made automatically, and no extra permissions are needed.
- **Never learns from a warning.** Nothing is learned while a warning or offer is active, and a balance reference cannot be recorded then.
- **Setup:** turning the check on asks for 3 normal-balance recordings. A setup checklist (normal walk, normal balance 0–3, trusted contact) stays visible until all three are done.
- **Study mode** (for the goggle study): label a session (*Sober*, *Goggles 1*, *Goggles 2*) and a participant ID. While labelled, nothing is learned. Every walking segment (six features and its distance from normal), every walk check and every balance result is appended to a private CSV, exported with *Export CSV*.
- Advice expires after 1 hour. "I'm not driving" dismisses it.
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

### Unit tests (32 more, always run)

| Class | Tests | What it proves |
|---|---|---|
| `GaitSegmentExtractorTest` | 5 | Cadence within 8 % of an independent gyroscope-spectrum reference; healthy stride CV < 5 %; features identical under 3 phone rotations (this caught a sign-ambiguity bug); no segments while sitting, standing, not walking or interrupted; works without a gyroscope; impairment moves every feature the expected way |
| `GaitBaselineTest` | 4 | Thresholds are percentiles of the user's own walks; one-sided scoring; median walk score; daily quota and rolling capacity |
| `FitToDriveMonitorTest` | 21 | Held-out session normal, impaired walk large gap with an explanation; quarantine and never learning a flagged walk; not scored while learning; trip without a recent walk; each walk scored once; car-Bluetooth trigger; manual check waits for a walk or gives up; disabled = nothing learned; state survives a restart; balance sway is orientation-free and detects walking off; pass/fail against the personal reference; after a large gap only a normal balance clears it; advice expiry; progress. **Drunk-driving cases:** slightly unusual walk → balance offer and never learned; found only while driving → no prompt; driving after a warning → reminder; no balance test in a moving vehicle; implausible normal balance rejected; study session learns nothing; walks in the hand ignored |
| `FitToDriveEngineTest` | 2 | End to end through the real-time pipeline (jittered device events → resampler → DeepSense gate → gait → trip start → large gap → advice in the snapshot; balance test refused in the car); the proximity sensor decides pocket walks |

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
- **Passenger vs driver:** the check cannot tell them apart. "I'm not driving" dismisses it. The car-Bluetooth trigger usually fires only for the owner's own car.
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
| `FitToDriveMonitor.kt` | Learning, quarantine, triggers, scoring, balance decision, advice |
| `FitToDriveStore.kt` | Settings and atomic persistence of learned data |
| `StudyLog.kt` | Labelled study sessions: CSV log and export |
| `FitToDriveActivity.kt` | Opt-in, progress, last check, balance test, response |
| `SensingEngine.kt` / `SensingForegroundService.kt` | Feeds every 50 Hz sample; trip-start and car-Bluetooth triggers; alert notification |
