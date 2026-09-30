# PervasiveSense — DeepSense Multi-Context & Ergonomics Engine

[![Build & test](https://github.com/Saurav-Gupta-9741/DeepSense-MPC-/actions/workflows/build.yml/badge.svg)](https://github.com/Saurav-Gupta-9741/DeepSense-MPC-/actions/workflows/build.yml)

Real-time, on-device human context sensing for Android: physical activity
(DeepSense TinyML model), posture & sedentary health, fall detection, road-hazard
mapping in vehicles, and a daily wellness score — all from the phone's IMU,
running continuously in a foreground service with the screen locked.

> Project description and novelties: [`docs/PROJECT_OVERVIEW.md`](docs/PROJECT_OVERVIEW.md) ·
> Phone test checklist: [`docs/PHONE_TEST_PLAN.md`](docs/PHONE_TEST_PLAN.md)

> **Version 2.x.** v1 could not respond in real time and its model never loaded on
> a phone. The root causes, the fixes and the evidence are in
> [What changed in v2](#what-changed-in-v2). Build the APK from source (see
> [Build & run](#build--run)); the v1 APKs were removed because they contain the
> broken model.

---

## Headline results (all reproducible, see [Testing](#testing))

| What | Result |
|---|---|
| Activity accuracy on **15 people never seen in training**, phone in **random orientations** | **97.3 %** (macro-F1 0.954) |
| Per-class F1 | STILL 0.999 · WALKING 0.976 · RUNNING 0.954 · STAIRS_UP 0.941 · STAIRS_DOWN 0.900 |
| Prediction agreement when the phone is arbitrarily re-oriented | 98.7 – 99.4 % |
| Time for a real activity change to appear on screen (real model, 6 unseen people) | **2.1 – 3.6 s** |
| Dashboard refresh / inference cadence | 10 Hz / every 0.5 s |
| Model | 82.8 KB dynamic-range INT8, 0.3 ms per inference (desktop CPU), 99.95 % argmax agreement with float |
| Step counting on real walking / jogging / stairs (vs an independent gyroscope reference) | within 1 – 5 % |
| Automated tests | 49 JVM tests of the real Kotlin engine + 24 model tests in the app's TFLite runtime |

---

## What changed in v2

The dashboard showed `UNKNOWN / Confidence 0%` and barely updated. Investigation
found three independent failures:

**1. The model never loaded on the phone.** It was converted with a newer
TensorFlow (it needs runtime ≥ 2.17: `FULLY_CONNECTED` op v12) while the app
bundles `tensorflow-lite:2.16.1`. The interpreter failed at start-up and every
window fell back to `UNKNOWN 0%`. The v1 "79/79 tests" ran on desktop TF (and on
Python copies of the algorithms), so they could not catch it.
*Fix:* the model is now converted with TF 2.16.1 and a test loads it in exactly
the runtime version declared in `build.gradle.kts`.

**2. The model could not have worked anyway.** It was trained on synthetic sine
waves with gravity always on the Y axis. Loaded in a compatible runtime, a phone
lying flat on a desk was classified **METRO at 100 %**, and a phone in a
sideways pocket as CAR.
*Fix:* retrained on real recordings from 54 people (see [Model](#model)) with
subject-disjoint evaluation and random-rotation augmentation.

**3. The pipeline was not real-time.**

| v1 | v2 |
|---|---|
| Non-overlapping 128-sample windows: at most one update per 2.56 s | Sliding window, inference every 0.5 s, dashboard at 10 Hz |
| "Adaptive sampling" dropped to ~5 Hz: one update per **25.6 s**, and a 50 Hz model cannot read 5 Hz walking | Always 50 Hz (the model's rate); energy saving via sensor-hub batching and a longer inference stride instead |
| Raw events used as if exactly 50 Hz (phones deliver 50–60 Hz with jitter) | Timestamp-based resampler fuses accel + gyro onto an exact 50 Hz grid |
| Posture, fidget and road detectors received **one sample per window** | Every detector processes every 50 Hz sample |
| Sensor callbacks and inference on the main thread | Dedicated high-priority sensing thread |
| UI state via implicit broadcasts; start/stop race ("SERVICE STOPPED" next to a STOP button) | In-process `StateFlow`; UI always shows current state |

Other defects fixed:

- **Steps:** v1 displayed `seconds × 2`. v2 uses the hardware step counter, falling back to a validated accelerometer detector.
- **Fall detector:** v1 included the impact bounces in its immobility test, so it rejected real falls. It also auto-reset after 15 s because a motionless person reads ~1 g. v2 adds a settle window, a posture-change check, and a latched alert with an "I'm OK" action.
- **Road detector:** v1 assumed the raw z-axis is vertical. v2 projects onto gravity, so it works in any orientation, and low-passes the signal to reject engine vibration.
- **Transitions:** v1 showed the current time instead of the event time. v2 records each transition's own timestamp and debounces with hysteresis.
- **Wellness score:** v1 truncated to whole minutes and never reset. v2 accumulates real elapsed time, resets daily and persists across restarts.
- **Session start:** first samples no longer carry zero gyro values.

### Vehicle contexts (BUS / CAR / METRO)

v1's vehicle classes existed only in the synthetic generator; no real bus, car or
metro IMU recordings were available to train on. v2 therefore detects
**IN_VEHICLE** with Google Activity Recognition (a production-trained system) and
uses it to gate road-hazard mapping. It cannot distinguish bus from car from
metro. Collecting labelled transit recordings is the path to restoring those
classes (see [Limitations](#limitations)).

---

## Architecture

```
 Phone IMU (accel + gyro, ~50-60 Hz, jittered)      Google Activity Recognition
            │  SensorEvent timestamps                        │ IN_VEHICLE confidence
            ▼                                                ▼
 ImuResampler ──► exact 50 Hz 6-channel stream ──► SensingEngine (pure Kotlin, sensing thread)
                                                    │ every sample:  posture/fidget · fall FSM ·
                                                    │                road shocks · step detector
                                                    │ every 0.5 s:   DeepSense TFLite on last 2.56 s
                                                    │                → smoothing + hysteresis
                                                    │                → transitions · wellness · eco mode
                                                    ▼
                                    SensingRepository (StateFlow) ──► MainActivity (10 Hz)
                                                    └──► notifications (status · fall alert · break)
```

| File | Role |
|---|---|
| `ImuResampler.kt` | Fuses asynchronous accel/gyro events onto an exact 50 Hz grid; detects sensor stalls |
| `ActivityStreaming.kt` | Sliding window; probability smoother with hysteresis; `ActivityClassifier` / `ImuSink` interfaces |
| `SensingEngine.kt` | Orchestrates the whole pipeline; emits `SensingSnapshot`s. No Android types, fully unit-tested |
| `DeepSenseClassifier.kt` | TFLite model; reads labels from assets; validates tensor shapes; reports load errors |
| `ErgonomicPostureTracker.kt` | Tilt, fidget index (O(1) running variance), sedentary bouts & break prompts |
| `FallDetector.kt` | Low-g → impact → settle → immobility + orientation change |
| `RoadAnomalyDetector.kt` | Gravity-projected vertical shocks → potholes / speed breakers (vehicle only) |
| `StepDetector.kt` | Orientation-independent pedometer (fallback for phones without a step counter) |
| `WellnessScoreEngine.kt` | Daily score `0.3·Diversity + 0.3·Ergonomic + 0.2·Movement + 0.2·Safety` |
| `SensingForegroundService.kt` | Sensing thread, eco batching, wake lock, notifications, persistence |
| `SensorDataSource.kt` | `PhoneImuSource`; interface for adding wearable IMUs |

**Energy (eco) mode.** When the user has been STILL for 60 s and the dashboard is
closed, inference slows from every 0.5 s to every 1.28 s and the sensors switch to
FIFO batching. If the phone has wake-up accelerometer/gyroscope variants, the
wake lock is released and the sensor hub wakes the CPU per batch. Sampling
stays at 50 Hz because the model needs it. Opening the dashboard exits eco mode
immediately. The energy saving has **not** been measured on hardware.

---

## Model

| | |
|---|---|
| Input | `float32 [1, 128, 6]` = 2.56 s at 50 Hz of (ax, ay, az m/s², gx, gy, gz rad/s), Android sensor conventions |
| Output | softmax over `STILL, WALKING, RUNNING, STAIRS_UP, STAIRS_DOWN` (`assets/deepsense_labels.txt`) |
| Architecture | Dual-branch (accel / gyro) 1-D CNN with in-graph rotation-invariant magnitude channels, dilated temporal convolutions; 61.8 k parameters |
| Training data | [MotionSense](https://github.com/mmalekzadeh/motion-sense) (iPhone in trouser pocket, 24 subjects) + [UCI-HAR](https://archive.ics.uci.edu/dataset/240) (Android phone on waist, 30 subjects), both 50 Hz |
| Evaluation | Subject-disjoint: 15 test subjects (6 MotionSense + the 9 official UCI test subjects) never seen in training |
| Robustness | Each training window is rotated by a uniformly random 3-D rotation, applied identically to accel and gyro |

Unit and sign conventions were verified, not assumed. The iOS-to-Android
conversion `a = −g₀·(gravity + userAcceleration)` passes a rigid-body kinematics
check `dĝ/dt = −ω × ĝ` with fitted sign +0.963 over 144 recordings, and the
negative control (gyro sign flipped) gives −0.966.

| Test set (unseen subjects) | Accuracy | Macro-F1 |
|---|---|---|
| All, native orientation (TFLite INT8) | 97.37 % | 0.954 |
| All, **random orientation** (TFLite INT8) | **97.33 %** | **0.954** |
| MotionSense (pocket) | 96.98 % | 0.946 |
| UCI-HAR (waist) | 98.54 % | 0.977 |

Full confusion matrices: `model/model_metrics.json`.

Retrain:
```bash
pip install "tensorflow==2.16.1" "keras==3.3.3"
python model/train_export_deepsense.py --motionsense <motion-sense/data> --uci "<UCI HAR Dataset>"
cp model/deepsense_int8.tflite model/deepsense_labels.txt PervasiveSense/app/src/main/assets/
```
The TensorFlow version must match `org.tensorflow:tensorflow-lite` in `app/build.gradle.kts`.

---

## Testing

See [`docs/TESTING.md`](docs/TESTING.md) for what each test proves.

**1. Engine: 49 JVM tests** (`PervasiveSense/app/src/test`). These exercise the
real Kotlin code, mostly by replaying real MotionSense recordings delivered like
a phone does: separate accel/gyro streams at 57.3 / 48.7 Hz with ±2 ms jitter.
```bash
cd PervasiveSense && ./gradlew :app:testDebugUnitTest
```

**2. Model: 24 tests** in the app's TFLite runtime (`tests/test_tflite_model.py`).
```bash
pip install "tensorflow==2.16.1" "keras==3.3.3" pytest
MOTIONSENSE_DIR=<motion-sense/data> UCI_DIR="<UCI HAR Dataset>" pytest -v tests/test_tflite_model.py
```
Without the dataset variables, the 16 tests that need no data still run (runtime
compatibility, asset integrity, I/O contract, robustness, resting phone in any
orientation). The 8 data-dependent tests are skipped with a reason.

Both suites run automatically in CI on every push; the model job fails if any
test is skipped, so the real-data tests cannot silently drop out.

**Negative controls.** The model suite fails the v1 model three independent ways,
including the exact on-device error. The resampler, step and latency tests were
checked against deliberately broken inputs.

---

## Get the APK

Every push is built and tested by GitHub Actions (`.github/workflows/build.yml`):
49 engine tests, 24 model tests on real data, then the debug APK.

- **Latest build:** Actions tab → newest green *Build & test* run → *Artifacts* → `PervasiveSense-debug-apk` (a zip containing the APK).
- **Release download:** pushing a tag such as `v2.0.0` publishes a GitHub Release with the APK attached.

Uninstall any older PervasiveSense first: APKs built on different machines are
signed with different debug keys, and Android refuses to update across keys.

## Build & run

Requirements: Android Studio (Koala or newer) or JDK 17, Android SDK 34.

```bash
cd PervasiveSense
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug       # onto a connected device
```

On first start the app asks for **Notifications** and **Physical activity**
permissions. Physical activity is required on Android 14+ (the "health"
foreground-service type) and enables vehicle detection and the hardware step
counter.

Checking it is live: open the app and tap **Start sensing**. The status pill
turns **LIVE** and the dashboard shows *Calibrating* for 2.56 s, then the
activity. Walk with the phone in a trouser pocket: *Walking* appears within
about 2–3 s and the model-output bars update 10 times per second. Tap
**Show sensor details** to see the device's real sensor rate and the inference time.
The full feature-by-feature checklist is in
[`docs/PHONE_TEST_PLAN.md`](docs/PHONE_TEST_PLAN.md).

---

## Repository structure

```
PervasiveSense/                 Android app (Kotlin, minSdk 26, targetSdk 34)
  app/src/main/java/...          sources (table above)
  app/src/main/assets/           deepsense_int8.tflite, deepsense_labels.txt
  app/src/test/                  49 JVM tests + real-data fixtures (MotionSense excerpts)
model/
  har_datasets.py                dataset loaders -> Android units, frame verification
  train_export_deepsense.py      training + TF 2.16.1 export + evaluation
  export_test_fixtures.py        builds the JVM test fixtures + independent cadence reference
  deepsense_int8.tflite          model (identical to the app asset; a test enforces this)
  model_metrics.json             held-out metrics and confusion matrices
tests/test_tflite_model.py      model validation in the app's runtime
.github/workflows/build.yml     CI: tests + APK artifact + Release on tags
docs/                           overview & novelties, phone test plan, testing guide; v1 notes (historical)
```

---

## Limitations

- **Vehicle subtype.** Only IN_VEHICLE is detected (via Google Play services); bus/car/metro need labelled real recordings. On phones without Play services, vehicle detection and road-hazard mapping are unavailable, and the dashboard says so.
- **Falls.** Validated on physics-based scenarios plus zero false alarms on real daily activities. No labelled real fall recordings were available. A dropped phone that lies still can resemble a fall.
- **Road anomalies.** Thresholds follow published smartphone road-sensing magnitudes and are validated on constructed shock profiles. They should be calibrated with real drives.
- **Posture.** Tilt semantics assume a trouser pocket while seated.
- **Energy.** Eco mode is implemented but its battery saving has not been measured.
- **Training data** covers two phone placements (pocket, waist); a phone held in the hand is less represented.

## Credits

MotionSense: Malekzadeh et al., *Mobile Sensor Data Anonymization*, IoTDI 2019.
UCI-HAR: Anguita et al., *A Public Domain Dataset for Human Activity Recognition Using Smartphones*, ESANN 2013.
