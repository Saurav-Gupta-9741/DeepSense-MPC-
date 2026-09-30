# PervasiveSense — Project Overview

## What it is

PervasiveSense is an Android app that turns an ordinary smartphone into a
continuous, private health-and-context sensor. Using only the phone's built-in
motion sensors (accelerometer and gyroscope), it works out, in real time and
entirely on the device:

- **what you are doing**: still, walking, running, climbing or descending stairs, or travelling in a vehicle;
- **how you are sitting**: posture tilt, fidgeting, and how long you have been sitting without a break;
- **whether you may have fallen**, raising an alert if so;
- **road-surface hazards** (potholes, speed breakers) while you travel in a vehicle;
- **a daily wellness score** summarising activity variety, posture, movement and safety.

It runs as a foreground service, so it keeps sensing with the screen locked and
the phone in a pocket. No data leaves the phone and no internet connection is
needed for sensing.

## How it works (one paragraph)

The phone's sensors report at slightly irregular rates, so a resampler first
aligns the accelerometer and gyroscope onto an exact 50 Hz timeline. Every
sample feeds lightweight algorithms (posture, fidget, falls, road shocks,
steps). Every 0.5 s a small neural network (DeepSense, 82.8 KB) classifies the
last 2.56 s of motion; its outputs are smoothed so the displayed activity is
stable without being slow. A dashboard shows the results 10 times per second,
and notifications report status, falls and break reminders.

## Who it is for

Office workers and students (sedentary behaviour and posture), elderly users
(fall alerts), commuters (road-quality mapping), and researchers who need a
reproducible, on-device context-sensing platform.

---

## Novelties

The individual building blocks (CNN activity recognition, threshold fall
detection, pothole detection) exist in the literature. What this project
contributes is how they are made robust, real-time and verifiable on an
ordinary phone, and how they are combined into one context-aware system.

### 1. Real-time streaming activity recognition on the phone
Most smartphone activity-recognition work evaluates fixed, pre-cut windows. PervasiveSense runs a continuous streaming pipeline instead:
- a timestamp-based resampler fuses the two unaligned sensor streams onto an exact 50 Hz grid;
- inference runs on a sliding window every 0.5 s;
- probabilities are smoothed with switching hysteresis.

**Measured:** a change of activity appears on screen within 2.1–3.6 s for people the model never saw, and the smoothing adds at most 1 s.

### 2. Orientation-invariant DeepSense TinyML model
The dual-branch 1-D CNN (accelerometer and gyroscope branches) computes rotation-invariant magnitude channels inside the model. It is trained with uniformly random 3-D rotations applied identically to both sensors, so the result does not depend on how the phone sits in the pocket.

**Measured:** 97.3 % accuracy on 15 unseen people in random orientations. Predictions stay 98.7–99.4 % identical under arbitrary re-orientation. The model is 82.8 KB.

### 3. Physics-verified multi-dataset training
Two public datasets recorded on different phone platforms (iOS in a pocket, Android on the waist) were converted into one Android sensor convention. The conversion was checked, not assumed, with a rigid-body kinematics test (dĝ/dt = −ω × ĝ): the fitted sign is +0.963, and a deliberately flipped sign gives −0.966. Evaluation is subject-disjoint, so the reported accuracy reflects a new user.

### 4. Pocket-based ergonomic and sedentary monitoring
From a phone in the trouser pocket the app estimates:
- posture tilt (upright, slouching or reclined);
- a fidget index, whose threshold was calibrated on 12,568 real stationary windows;
- continuous sitting bouts, where changing posture counts as a micro-break.

It raises a break reminder after 45 minutes of sitting.

### 5. Multi-phase, low-false-alarm fall detection
A fall is confirmed only after four phases in sequence:
1. a low-g phase;
2. an impact above 2.5 g;
3. a settling window, deliberately excluded because post-impact bounces are not stillness;
4. both immobility and a change in body orientation.

The alert stays up until the user taps "I'm OK" or is seen walking again.

**Measured:** no false alarms on any of the five real daily-activity recordings (walking, jogging, stairs, sitting, standing).

### 6. Context-gated, orientation-independent road-hazard mapping
Road shocks are measured along the true vertical (the acceleration projected onto the gravity direction), so they work in any phone orientation. The signal is low-passed to reject engine vibration. Detection runs only while the user is in a vehicle, as reported by Google Activity Recognition, which prevents walking steps from being mistaken for potholes.

### 7. Daily composite wellness score
The score combines four components: activity diversity (contexts sustained for at least 60 s), ergonomics (share of sitting time not slouched), movement (real steps) and safety (falls). It uses real elapsed time, resets at midnight and survives app restarts.

### 8. Energy-aware operation
When the user has been still for 60 s and nobody is viewing the dashboard, the app enters eco mode:
- inference slows from every 0.5 s to every 1.28 s;
- sensors switch to hardware FIFO batching;
- on phones with wake-up sensors, the CPU may sleep between batches.

Sampling stays at 50 Hz because the model requires it. The battery saving has not yet been measured.

### 9. Verified, reproducible engineering
- The model is validated in the exact TFLite runtime version the app ships. The v1 model failed this check on the phone itself.
- 49 JVM tests exercise the real Kotlin engine by replaying real sensor recordings, delivered with realistic timing jitter.
- 24 model tests run on held-out real data.
- Every push to GitHub runs all tests and builds the APK automatically.

---

## Limitations (stated honestly)

- **Vehicle type:** vehicle subtype (bus / car / metro) is not distinguished; only "in vehicle" is detected. The app needs Google Play services for this.
- **Falls and road hazards:** both are validated on physics-based scenarios. No labelled real recordings of these events were available.
- **Phone placement:** the model was trained on pocket and waist placements, so a phone held in the hand is less reliable.
- **Energy:** the eco-mode battery saving is implemented but not yet measured.
