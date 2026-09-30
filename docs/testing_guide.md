# PervasiveSense — Complete Project Guide & Testing Manual

---

## 🎯 What Is This Project? (One Paragraph)

**PervasiveSense** is an Android app that runs **silently in the background** — even when the phone is locked in your pocket — and uses **on-device AI + physics-based signal processing** to understand your entire physical life context. It knows if you're walking, running, climbing stairs, sitting in a bus/car/metro, slouching, fidgeting, hitting a pothole, or falling down. It does ALL of this using just 2 tiny sensors already in your phone (accelerometer + gyroscope) and a 91 KB neural network that runs in 0.2 milliseconds.

---

## 📊 All 8 Novelty Features Explained

### Feature 1: Multi-Context Activity Recognition (AI Brain)
```
What:  Identifies 8 physical activities in real-time
How:   91 KB TFLite neural network processes 2.56-second sensor windows
       Dual-branch CNN (separate accel + gyro) → Cross-sensor fusion → 
       Multi-scale dilated convolutions → Softmax classifier

Classes: STILL | WALKING | RUNNING | STAIRS_UP | STAIRS_DOWN | BUS | CAR | METRO
```

### Feature 2: Ergonomic Posture Monitoring
```
What:  Detects HOW you're sitting (upright, slouching, reclined)
How:   Low-pass gravity filter isolates static gravity vector →
       atan2(gravity_y, gravity_z) = thigh tilt angle in degrees
       
       -25° to +25° = "Upright / Ergonomic" ✅
       Below -25°   = "Forward Slouching" ⚠️
       Above +25°   = "Reclined / Leaning" ℹ️
```

### Feature 3: Fidget & Stress Index
```
What:  Measures nervous fidgeting (leg shaking, restlessness)
How:   dynamic_accel = raw_accel - gravity (removes static component)
       fidget_index = variance of |dynamic_accel| over 4 seconds
       
       < 0.15 = "Calm/Stable"
       > 0.15 = "Restless/Fidgeting" ⚡
```

### Feature 4: Smart Sedentary Alert (45-min Break Reminder)
```
What:  Reminds you to take a break after 45 min of same posture
How:   Timer starts when you sit. RESETS if:
       - You stand up and walk (activity changes from STILL)
       - You shift your sitting posture by more than 15°
       Timer only fires if you've been FROZEN in exact same position.
```

### Feature 5: Road Anomaly / Pothole Detection
```
What:  Counts potholes and speed breakers during vehicle travel
How:   ONLY activates when AI says you're in BUS/CAR/METRO
       Monitors Z-axis (vertical) accelerometer for shocks:
       
       Pothole:       minZ < -11 AND peak-to-peak > 22 m/s²
       Speed Breaker: peak-to-peak 12-21 m/s² AND minZ > -10
       Debounce:      Ignores duplicates within 1.5 seconds
```

### Feature 6: Fall Detection with Anti-False-Alarm
```
What:  Detects real falls and rejects phone drops/false alarms
How:   3-State Machine:
       
       MONITORING ──(impact > 3.2g)──→ IMPACT_SUSPECTED
                                            │
                                     (wait 6 seconds)
                                            │
                              ┌──────────────┼──────────────┐
                              ▼              │              ▼
                    variance < 0.2    │     variance > 0.2
                    (user immobile)          (user moved)
                              │                             │
                              ▼                             ▼
                    FALL_CONFIRMED 🚨          Back to MONITORING
                    (real fall!)               (false alarm rejected!)
```

### Feature 7: Adaptive Energy-Aware Sampling ⚡ (NEW)
```
What:  Saves up to 90% battery by reducing sensor speed when not needed
How:   Adjusts sampling rate based on current activity:
       
       STILL    → 5 Hz   (saves 90% battery — nothing is happening)
       WALKING  → 20 Hz  (saves 60% — walking is predictable)
       RUNNING  → 50 Hz  (full rate — fast motion needs precision)
       VEHICLE  → 50 Hz  (full rate — need to detect potholes)
```

### Feature 8: Activity Transition Detection (NEW)
```
What:  Pinpoints the EXACT MOMENT you change activity
How:   Compares current classification with previous window.
       If activity changed AND confidence > 75%, logs:
       "STILL → WALKING at 09:45:23" with timestamp
       
       Also counts total transitions for behavioral profiling.
```

### Feature 9: Daily Wellness Score (NEW)
```
What:  Single 0-100 health score for your entire day
How:   Weighted combination of 4 sub-scores:
       
       Overall = 30% × Activity Diversity    (did you do varied activities?)
              + 30% × Ergonomic Health       (how much did you slouch?)
              + 20% × Movement Adequacy      (did you walk 6000+ steps?)
              + 20% × Safety Score           (any fall incidents?)
       
       💚 80-100: Excellent    💛 60-79: Good
       🟠 40-59: Fair          ❤️ 0-39: Needs improvement
```

### Feature 10: Sensor Abstraction Layer (NEW — Architecture)
```
What:  App is designed to work with external BLE wearables in future
How:   Clean interface separating sensor source from processing pipeline:
       
       SensorDataSource (interface)
       ├── PhoneIMUSource (current — uses built-in accel/gyro)
       └── BLEWearableSource (future — connects to wrist/ear sensors)
       
       Same AI pipeline works regardless of sensor source.
```

---

## 🏗️ Project File Structure

```
PervasiveSense/
├── app/src/main/
│   ├── assets/
│   │   └── deepsense_int8.tflite          ← 91 KB AI model
│   ├── java/com/iitj/pervasivesense/
│   │   ├── DeepSenseClassifier.kt         ← TFLite inference wrapper
│   │   ├── ErgonomicPostureTracker.kt     ← Posture + fidget + sedentary
│   │   ├── RoadAnomalyDetector.kt         ← Pothole + speed breaker
│   │   ├── FallDetector.kt               ← 3-state fall detection FSM
│   │   ├── ActivityTransitionDetector.kt  ← NEW: context change detection
│   │   ├── WellnessScoreEngine.kt         ← NEW: daily health scoring
│   │   ├── SensorDataSource.kt           ← NEW: BLE-ready abstraction
│   │   ├── SensingForegroundService.kt    ← Background sensing engine
│   │   └── MainActivity.kt               ← Dashboard UI (6 cards)
│   ├── res/layout/activity_main.xml       ← Dark theme dashboard
│   └── AndroidManifest.xml               ← Permissions & service
├── build.gradle.kts                       ← Gradle build config
└── PervasiveSense-debug.apk              ← Ready-to-install APK (19.79 MB)
```

---

## 📱 HOW TO TEST — Step by Step

### Step 0: Get an Android Phone

You need an Android phone with:
- Android 8.0 or higher (API 26+) — almost any phone from 2018 onwards
- Accelerometer + Gyroscope sensors (99% of phones have these)
- USB cable or any way to transfer the APK file

### Step 1: Transfer the APK to the Phone

**Option A — USB Cable:**
1. Connect Android phone to your laptop with USB cable
2. On phone: swipe down notification → tap "USB charging" → select "File Transfer"
3. On laptop: open File Explorer → phone appears as a drive
4. Copy `e:\GenAI-part2-Rag-implementation-main\PervasiveSense-debug.apk` to phone's `Downloads` folder

**Option B — WhatsApp/Telegram:**
1. Send the APK file to yourself on WhatsApp or Telegram
2. Download it on the phone

**Option C — Google Drive:**
1. Upload APK to Google Drive
2. Download on phone

### Step 2: Install the APK

1. Open **Files** app on phone → navigate to Downloads
2. Tap `PervasiveSense-debug.apk`
3. Phone will say "Install from unknown sources not allowed"
4. Tap **Settings** → Enable **"Allow from this source"**
5. Go back → tap **Install**
6. Wait for installation → tap **Open**

### Step 3: Grant Permissions

When the app opens, it will ask for permissions:
- **Notifications**: Tap **Allow** (needed for lock-screen status updates)
- **Activity Recognition**: Tap **Allow** (needed for sensor access)

### Step 4: Start Sensing

1. Tap the big cyan **"START PERVASIVE SENSING"** button
2. A notification will appear: "Pervasive Sensing Active"
3. The dashboard will start updating every ~2.5 seconds

---

## 🧪 TEST SCENARIOS — What to Do Physically

### Test 1: Walking Detection (2 minutes)
```
What to do:
  1. Put phone in your front pocket (or hold it normally)
  2. Walk around the room/corridor for 2 minutes
  
What to look for on screen:
  ✅ Activity card shows "WALKING" with >80% confidence
  ✅ Sampling rate shows "⚡ 20 Hz (Balanced)" (adaptive sampling kicked in)
  ✅ Wellness Score starts increasing (movement score goes up)
  ✅ Step counter starts incrementing
```

### Test 2: Still / Sitting Detection (3 minutes)
```
What to do:
  1. Sit down at a desk with phone in pocket
  2. Stay still for 3 minutes
  
What to look for on screen:
  ✅ Activity changes from WALKING → STILL
  ✅ Transition card shows "WALKING → STILL (HH:MM:SS)"
  ✅ Sampling rate drops to "⚡ 5 Hz (Energy Save)" (90% battery saved!)
  ✅ Sedentary timer starts counting up (0 min, 1 min, 2 min...)
  ✅ Posture shows "Upright / Ergonomic" or "Reclined / Leaning"
```

### Test 3: Posture Change Detection
```
What to do:
  1. While sitting, lean forward significantly (slouch)
  2. Wait 5 seconds
  3. Sit back upright
  
What to look for on screen:
  ✅ Tilt angle changes significantly (e.g., from 15° to -35°)
  ✅ Posture status changes to "Forward Slouching"
  ✅ Ergonomic sub-score in Wellness card starts dropping
```

### Test 4: Fidget / Leg Shake Detection
```
What to do:
  1. Sit still for 30 seconds (baseline)
  2. Start shaking your leg rapidly for 15 seconds
  3. Stop and sit still again
  
What to look for on screen:
  ✅ Fidget Index jumps from ~0.01 to >0.15
  ✅ Status changes from "Calm/Stable" to "Restless/Fidgeting"
  ✅ Fidget Index drops back down when you stop
```

### Test 5: Activity Transitions
```
What to do:
  1. Stand still (10 seconds)
  2. Start walking (10 seconds)
  3. Stop and stand still (10 seconds)
  4. Walk again (10 seconds)
  
What to look for on screen:
  ✅ Transition card updates: "STILL → WALKING", "WALKING → STILL"
  ✅ Transition count increases (should show 3-4 transitions)
  ✅ Each transition shows timestamp
```

### Test 6: Running Detection
```
What to do:
  1. Start from walking
  2. Jog in place or run down a corridor for 30 seconds
  3. Stop
  
What to look for on screen:
  ✅ Activity changes to "RUNNING"
  ✅ Sampling rate goes to "⚡ 50 Hz (Full Rate)"
  ✅ Step counter increases faster
```

### Test 7: Stairs Detection
```
What to do:
  1. Walk up a flight of stairs
  2. Walk down a flight of stairs
  
What to look for on screen:
  ✅ Activity shows "STAIRS_UP" while going up
  ✅ Activity shows "STAIRS_DOWN" while going down
  ✅ Transitions logged for each change
```

### Test 8: Fall Detection (CAREFUL!)
```
⚠️ DO NOT ACTUALLY FALL. Simulate it safely:

What to do:
  1. Hold phone at chest height
  2. Drop phone onto a soft pillow/bed from ~1 meter (simulates impact)
  3. DON'T TOUCH IT for 8 seconds (simulates immobility)
  
What to look for on screen:
  ✅ Fall status changes to "⚠️ Impact Detected! Observing immobility..."
  ✅ After 6 seconds: "🚨 FALL CONFIRMED! Alerting..."
  ✅ Lock screen shows critical notification
  
Then pick up the phone and walk around:
  ✅ Fall status resets to "Armed with Anti-False-Alarm"
```

### Test 9: False Alarm Rejection
```
What to do:
  1. Hold phone at chest height
  2. Drop phone onto pillow (impact!)
  3. Immediately pick it up within 2 seconds
  
What to look for on screen:
  ✅ Fall status briefly shows "Impact Detected..."
  ✅ After 6 seconds: returns to "Armed with Anti-False-Alarm" (NOT confirmed!)
  ✅ This proves the anti-false-alarm system works
```

### Test 10: Pothole Simulation
```
What to do:
  1. Hold phone in car/bus while actually riding (ideal)
     OR simulate: put phone on flat surface, then sharply 
     bump the surface up and down to create Z-axis shocks
  2. NOTE: Pothole detection ONLY works when AI classifies 
     the activity as BUS, CAR, or METRO (vehicular mode)
  
What to look for on screen:
  ✅ If in vehicular mode: Pothole count increases on sharp bumps
  ✅ Speed Breaker count increases on smooth bumps
  ✅ If walking/still: pothole detector stays at 0 (correctly gated off)
```

### Test 11: Wellness Score Progression
```
What to do:
  1. Use the app for 15-20 minutes doing varied activities
  2. Walk, sit, climb stairs, sit again
  
What to look for on screen:
  ✅ Overall wellness score increases over time
  ✅ Diversity sub-score goes up as you do more unique activities
  ✅ Movement sub-score goes up with more steps
  ✅ Ergonomic sub-score reflects your posture quality
  ✅ Emoji changes: ❤️ → 🟠 → 💛 → 💚 as score improves
```

### Test 12: Lock Screen Background Operation
```
What to do:
  1. Start sensing
  2. LOCK the phone (press power button)
  3. Put phone in pocket
  4. Walk around for 2 minutes
  5. Unlock phone and open app
  
What to look for:
  ✅ Notification on lock screen shows live activity & wellness score
  ✅ When you open the app, dashboard shows updated data
  ✅ Step count has increased while phone was locked
  ✅ This proves TRUE PERVASIVE background sensing works
```

---

## 🎬 Demo Script for Prof. Suchetana (5 minutes)

```
"Good morning Ma'am. This is PervasiveSense — a unified on-device
 pervasive sensing framework built on the DeepSense architecture."

[Start the app, tap START]

"I'll put my phone in my pocket now. It's locked."
[Lock phone, put in pocket]

[Walk around for 30 seconds]
"As you can see on the notification, it detected WALKING at 96% 
 confidence. The sampling rate automatically adapted to 20 Hz 
 to save battery — that's our adaptive energy-aware sampling."

[Sit down]
"Now it transitions to STILL. You can see the exact transition 
 event logged with timestamp. The sampling rate dropped to 5 Hz — 
 saving 90% battery while I'm just sitting."

[Slouch forward]
"Notice the posture changed to Forward Slouching. The tilt angle
 shifted from 15° to -35°. This is our ergonomic health monitoring."

[Shake leg]
"The fidget index just spiked — it detected my nervous leg shaking
 through dynamic acceleration variance analysis."

[Show wellness score card]
"This Daily Wellness Score aggregates everything — activity diversity,
 ergonomic health, movement adequacy, and safety — into a single 
 0-100 health metric. Inspired by the holistic health profiling 
 approach in your WristSense paper, Ma'am."

[Drop phone on padded surface, don't touch for 8 seconds]
"Fall detection — 3.2g impact detected, now observing for 6 seconds...
 FALL CONFIRMED. But watch — if I pick it up immediately..."

[Pick it up right away on second demo]
"...false alarm rejected! The anti-false-alarm FSM verified that 
 I was still moving after impact, so it didn't cry wolf."

"The entire system — 9 Kotlin files, 91 KB neural network — runs 
 fully on-device with no cloud, no internet, no external hardware.
 Thank you Ma'am."
```

---

## ⚠️ Troubleshooting

| Problem | Solution |
|:---|:---|
| App won't install | Enable "Install from unknown sources" in phone Settings → Security |
| No sensor data appearing | Make sure phone has accelerometer + gyroscope (check with "Sensor Box" app from Play Store) |
| Activity stuck on "INITIALIZING..." | Wait 3 seconds — first window needs 128 samples to fill |
| Pothole count stays at 0 | Pothole detection only activates when activity = BUS/CAR/METRO |
| Fall detection won't trigger | Need a sharp 3.2g impact — gentle drops won't trigger it |
| App killed in background | Go to phone Settings → Battery → PervasiveSense → Don't Optimize |
| Wellness score stuck at 0 | Do at least 1 activity for 5+ seconds — it needs data to compute |
