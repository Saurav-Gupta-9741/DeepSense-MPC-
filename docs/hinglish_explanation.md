# PervasiveSense — Poora Project Samjho Simple Hinglish Mein

---

## 🤔 Sabse Pehle — Yeh Project Hai Kya?

Soch ek aisa app jo tere phone mein chup-chaap background mein chalta rehta hai — **screen lock ho, phone jeb mein ho, tu bhool bhi jaaye ki app chal raha hai** — phir bhi yeh app har second yeh sab jaanta hai:

- Tu **chal raha hai ya baitha hai**
- Tu **bus mein hai ya car mein**
- Tu **seedhiyan chadh raha hai ya utar raha hai**
- Tu **slouch karke baitha hai ya seedha**
- Teri **taang hil rahi hai (nervous fidgeting)**
- Tu **kitni der se same position mein jam gaya hai**
- Gaadi mein **pothole aaya ya speed breaker**
- Tu **gir gaya hai ya phone gira hai** (aur dono mein farak bata sakta hai!)
- Tere din ka **overall health score kitna hai**
- Tu **kab kab activity change karta hai** (walking se sitting, sitting se running)
- Yeh sab karte hue **battery bhi bach rahi hai** kyunki sensor speed adjust hoti rehti hai

**Aur yeh sab karne ke liye koi internet nahi chahiye. Koi cloud nahi. Koi GPS nahi. Koi extra device nahi. Bas phone ke andar ka accelerometer aur gyroscope — 2 chhote se sensors — aur ek 91 KB ka AI model.**

---

## 🧠 Part 1: AI Brain — DeepSense Neural Network

### Accelerometer aur Gyroscope Kya Hain?

Tere phone ke andar 2 chhote chips lage hain:

**Accelerometer** = Yeh batata hai ki phone kis direction mein hil raha hai aur kitni tez. Imagine kar tere haath mein ek glass paani hai — agar tu chale toh paani hilega, agar tu daud jaye toh zyada hilega, agar tu baitha rahe toh paani shaant rahega. Accelerometer exactly yeh measure karta hai, but numbers mein.

```
Accelerometer ke 3 numbers (har 20 millisecond mein):
  ax = left-right movement    (phone ko left-right hilao)
  ay = up-down movement       (phone ko upar-neeche hilao)  
  az = front-back movement    (phone ko aage-peeche hilao)

Jab phone seedha table pe pada hai:
  ax ≈ 0, ay ≈ 9.8 (gravity!), az ≈ 0

Jab tu chal raha hai (phone pocket mein):
  ay = 9.8 ke around hilta rehta hai — har kadam pe upar-neeche
  Pattern aisa dikhta hai: 9.8, 12.1, 7.5, 12.3, 7.2, 12.0...
  Yeh ek "wave" hai — walking ki wave!
```

**Gyroscope** = Yeh batata hai ki phone kitna **ghoom** (rotate) raha hai. Jaise tere haath mein steering wheel hai — left ya right mod raha hai. Gyroscope rotation measure karta hai.

```
Gyroscope ke 3 numbers:
  gx = pitch (aage-peeche jhukna)
  gy = yaw (left-right moodna)
  gz = roll (side mein tiltna)
```

### AI Model Kaise Kaam Karta Hai?

Ab samajh — phone har second mein 50 baar sensor readings leta hai (50 Hz). Har reading mein 6 numbers aate hain (ax, ay, az, gx, gy, gz).

**Step 1: Window banana (2.56 seconds ka data)**
```
128 readings × 6 channels = 768 numbers ka ek "packet"

Yeh 768 numbers mein tere 2.56 seconds ki poori physical activity
ka fingerprint chhupa hai. Jaise tera fingerprint unique hai,
waise hi walking ka fingerprint, running ka, bus ka — sab alag hai.
```

**Step 2: Neural Network mein daalna**
```
Yeh 768 numbers AI model mein jaate hain. Model kya hai? 
Ek mathematical machine jo training se seekh chuki hai ki:

"Agar numbers mein 1.8 Hz ki wave hai → yeh WALKING hai"
"Agar numbers mein 3.2 Hz ki tez wave hai → yeh RUNNING hai"  
"Agar numbers mein 14 Hz ki vibration hai → yeh BUS hai"
"Agar numbers bilkul stable hain → yeh STILL hai"

Model ka size? Sirf 91 KB! (Ek photo se bhi chhota)
Inference time? 0.2 milliseconds! (Ek pal mein answer)
```

**Step 3: Output — 8 probabilities**
```
Model ka output ek list hoti hai:

STILL:      1%
WALKING:    96%  ← WINNER! (Sabse zyada confidence)
RUNNING:    1%
STAIRS_UP:  1%
STAIRS_DOWN: 0%
BUS:        0%
CAR:        0%
METRO:      1%

Answer: "WALKING" with 96% confidence ✅
```

### Model Ka Architecture (Neural Network Ki Design)

```
┌─────────────────────────────────────────────────────────┐
│                   INPUT: 768 numbers                     │
│              [128 time steps × 6 channels]               │
│                                                          │
│  ┌───────────────────┐    ┌───────────────────┐         │
│  │ ACCEL BRANCH       │    │ GYRO BRANCH        │         │
│  │ (ax, ay, az)       │    │ (gx, gy, gz)       │         │
│  │                    │    │                    │         │
│  │ Conv1D(32, k=5)    │    │ Conv1D(32, k=5)    │         │
│  │ BatchNorm          │    │ BatchNorm          │         │
│  │ Conv1D(32, k=3)    │    │ Conv1D(32, k=3)    │         │
│  │ MaxPool(2)         │    │ MaxPool(2)         │         │
│  └────────┬───────────┘    └────────┬───────────┘         │
│           │                         │                    │
│           └──────────┬──────────────┘                    │
│                      ▼                                   │
│           ┌──────────────────┐                           │
│           │  FUSION LAYER    │  ← Dono branches ko jodna│
│           │  Conv1D(64, k=3) │                           │
│           │  BatchNorm       │                           │
│           └────────┬─────────┘                           │
│                    ▼                                     │
│      ┌──────────────────────────┐                       │
│      │ MULTI-SCALE DILATED CNN  │                       │
│      │                          │                       │
│      │ Conv1D(64, dilation=1)   │ ← Chhota pattern      │
│      │ Conv1D(64, dilation=2)   │ ← Medium pattern      │
│      │ Conv1D(64, dilation=4)   │ ← Bada pattern        │
│      └──────────┬───────────────┘                       │
│                 ▼                                        │
│      GlobalAveragePooling                                │
│                 ▼                                        │
│      Dense(64) → Dropout(0.2) → Dense(8, softmax)       │
│                                                          │
│                 OUTPUT: 8 probabilities                   │
└─────────────────────────────────────────────────────────┘
```

**Simple explanation:**
- **Accel Branch**: Sirf accelerometer data dekhta hai — "phone kitna hila?"
- **Gyro Branch**: Sirf gyroscope data dekhta hai — "phone kitna ghooma?"
- **Fusion**: Dono ko mila ke ek combined understanding banata hai
- **Dilated CNN**: Alag-alag time scales pe patterns dhundhta hai (chhoti movements bhi, badi movements bhi)
- **Output**: 8 classes mein se sabse likely activity batata hai

---

## 🪑 Part 2: Ergonomic Health Monitoring

### Posture Detection — Tu Kaise Baitha Hai?

Jab tu baithta hai aur phone jeb mein hai, toh phone ka accelerometer **gravity** feel karta hai. Gravity hamesha neeche ki taraf hoti hai (9.8 m/s²). Lekin "neeche" ka direction phone ke relative badalti rehti hai — kyunki tere thigh ka angle badalti hai!

```
SEEDHA BAITHA:                    SLOUCHING (Aage jhuka):
  Phone pocket mein               Phone pocket mein
       │                                ╲
       │  ← thigh almost vertical        ╲  ← thigh angled forward
       │                                  ╲
  ─────┘                            ──────╲
  
  Gravity mostly ay axis pe         Gravity ay + az dono pe split
  tilt angle ≈ 10° (Ergonomic)      tilt angle ≈ -35° (Slouching!)
```

**Math:**
```
tilt_angle = atan2(gravity_y, gravity_z) × (180/π)

-25° se +25° ke beech = "Upright / Ergonomic" ✅ (Seedha baitha)
-25° se neeche         = "Forward Slouching" ⚠️ (Aage jhuka)
+25° se upar           = "Reclined / Leaning" ℹ️ (Peeche teka)
```

**Lekin gravity kaise nikaalein? Raw accelerometer mein toh movement bhi mixed hai!**

Iske liye **Low-Pass Filter** use karte hain:

```
gravity_new = 0.85 × gravity_old + 0.15 × raw_reading

Yeh kya karta hai? Jaise coffee filter slow-slow coffee girata hai
aur bade particles rok leta hai — waise hi yeh filter slow changes
(gravity) ko pass karta hai aur fast changes (hand movement, walking)
ko rok deta hai.

50 readings ke baad, gravity value almost perfect hoti hai.
```

### Fidget Index — Teri Taang Hil Rahi Hai Kya?

```
Step 1: dynamic_accel = raw_accel - gravity
        (Gravity hatao, bacha kya? Sirf movement!)

Step 2: magnitude = √(dynX² + dynY² + dynZ²)
        (Total movement kitna hai, ek number mein)

Step 3: fidget_index = variance of magnitude over last 4 seconds
        (Kitna upar-neeche ho raha hai movement — consistent hai ya erratic)

Shaant baitha: fidget = 0.003 → "Calm/Stable"
Taang hila raha: fidget = 0.42 → "Restless/Fidgeting" ⚡
```

### 45-Minute Smart Break Alert

```
Yeh ek SMART timer hai, DUMB timer nahi:

DUMB timer: 45 minute ho gaye → alert (chahe tu 10 baar khada hua ho)

SMART timer: 
  - Timer start hota hai jab tu baithta hai
  - Agar tu khada ho ke chala → TIMER RESET ✅
  - Agar tu baithte hue apna posture badla (15°+ shift) → TIMER RESET ✅
  - SIRF tab alert deta hai jab tu 45 min BINA HILE same position mein jama raha

  Yeh important kyun hai? 
  Kyunki problem CONTINUOUS SITTING hai, not just total sitting time.
```

---

## 🕳️ Part 3: Road Anomaly Detection

### Kaise Kaam Karta Hai?

```
Pehle AI check karta hai: "User abhi BUS/CAR/METRO mein hai kya?"
  
  Agar HAAN → Road Anomaly Detector ON ✅
  Agar NAHI (walking/still/running) → Detector OFF 🔴
  
  Kyun? Kyunki walking mein bhi phone hilta hai — agar 
  detector hamesha on rahe toh false alarms dega!
```

**Pothole Kaise Detect Hota Hai?**
```
Jab gaadi pothole mein jaati hai:

Normal road:   az ≈ 9.8 (gravity, smooth)
               ──────────────────────────

Pothole!:      az = 9.8 → SUDDENLY -14 → rebounds to +18 → back to 9.8
               ──────╲                  ╱──────
                      ╲   SHARP DIP   ╱
                       ╲_____-14_____╱

Check 1: minZ < -11 m/s² ? (Bahut neeche gaya?) → YES ✓
Check 2: peak-to-peak > 22 m/s² ? (Upar-neeche ka fark bahut bada?) → YES ✓
RESULT: 🕳️ POTHOLE DETECTED!
```

**Speed Breaker Kaise Detect Hota Hai?**
```
Speed breaker pe gaadi:

               ╱╲
              ╱  ╲    SMOOTH SYMMETRIC HUMP
             ╱    ╲
────────────╱      ╲────────────

Check 1: peak-to-peak 12 to 21 m/s²? (Moderate bounce?) → YES ✓
Check 2: minZ > -10? (Neeche zyada nahi gaya — pothole nahi) → YES ✓
RESULT: ⏏️ SPEED BREAKER DETECTED!
```

**Debounce System:**
```
Agar 1.5 second ke andar 2 potholes aayein → sirf 1 count karo.
Kyun? Kyunki ek hi pothole mein aage ka tyre aur peeche ka tyre
alag-alag impact dete hain — double counting se bachna hai.
```

---

## 🛡️ Part 4: Fall Detection (Sabse Interesting!)

### Problem: Phone Drop vs Real Fall

```
Phone drop:  Phone girti hai → 5g impact → owner turant uthata hai
Real fall:   Insaan girta hai → 5g impact → insaan zamin pe pada rehta hai

Dono mein IMPACT same hai! Sirf BAAD MEIN kya hota hai — woh alag hai.
```

### Solution: 3-State Machine

```
State 1: MONITORING (Normal — sab theek hai)
         │
         │ "Kya impact > 3.2g aaya?"
         │ 3.2g = bahut tez jhatka (normal walk = 1.1g, running = 2g)
         │
         │ YES ──────────────────────────────────────────────────┐
         ▼                                                       │
State 2: IMPACT_SUSPECTED (Hmm... kuch hua hai)                 │
         │                                                       │
         │ Ab 6 second wait karo. Dekhte hain kya hota hai.     │
         │ Har sample ka magnitude record karo buffer mein.      │
         │ (Kam se kam 50 samples chahiye — 1 second ka real data)│
         │                                                       │
         │ 6 seconds baad:                                       │
         │ variance = "kitna hila user impact ke baad?"          │
         │                                                       │
         ├──── variance < 0.2 ─── (User HILA NAHI! Zamin pe pada hai!)
         │                         │                             │
         │                         ▼                             │
         │          State 3: FALL_CONFIRMED 🚨                  │
         │          "INSAAN GIR GAYA! EMERGENCY!"               │
         │                                                       │
         └──── variance > 0.2 ─── (User hil raha hai! Phone gira tha)
                                   │
                                   ▼
                     Back to MONITORING ✅
                     "False alarm. Sab theek hai."
```

**Real life example:**

```
Example 1 — Saurav seedhiyon se gira:
  t=0.0s: Walking normally (1.1g)
  t=0.5s: SLIP! Body hits stairs (4.8g) → IMPACT_SUSPECTED!
  t=0.5-6.5s: Saurav zamin pe pada hai, dazed
              Buffer: [1.02, 0.98, 1.01, 0.99, 1.03...]
              (Bas gravity hai, koi movement nahi)
  t=6.5s: variance = 0.0004 (< 0.2) → FALL CONFIRMED! 🚨
  
Example 2 — Saurav ka phone haath se gira:
  t=0.0s: Phone hits desk (5.2g) → IMPACT_SUSPECTED!
  t=0.3s: Saurav phone uthata hai (1.8g movement)
  t=0.5-6.0s: Phone haath mein hai, screen check kar raha
              Buffer: [1.2, 0.9, 1.4, 1.1, 0.8, 1.3...]
              (Bahut hil raha hai — insaan active hai)
  t=6.5s: variance = 0.38 (> 0.2) → FALSE ALARM REJECTED! ✅
          Koi alarm nahi! Koi embarrassment nahi!
```

---

## ⚡ Part 5: Adaptive Energy-Aware Sampling (NEW)

### Problem Kya Tha?

```
Pehle: Sensor HAMESHA 50 Hz pe chalta tha (har second 50 readings)
       - Jab tu chal raha hai: 50 Hz chahiye ✅ (fast movement capture)
       - Jab tu baitha hai: 50 Hz waste hai! ❌ (kuch ho hi nahi raha)
       
Battery drain: Sensor + CPU processing = phone garmi se marta hai
```

### Solution:

```
Ab: Sensor speed AUTOMATICALLY adjust hoti hai:

  STILL (baitha hai)     → 5 Hz   (har second sirf 5 readings)
                           Battery saving: 90%! 🔋🔋🔋
                           
  WALKING (chal raha)    → 20 Hz  (har second 20 readings)
                           Battery saving: 60%! 🔋🔋
                           
  RUNNING / STAIRS       → 50 Hz  (full speed!)
  BUS / CAR / METRO        Battery saving: 0% (zaroorat hai)

Kaise? Har 2.56 seconds mein jab AI classify karta hai, woh dekhta hai
"abhi kya activity hai?" aur accordingly sensor rate change kar deta hai.

Jab activity CHANGE hoti hai (STILL → WALKING), toh briefly 50 Hz pe
jaata hai taaki transition miss na ho, phir settle back karta hai.
```

---

## 🔄 Part 6: Activity Transition Detection (NEW)

### Kya Karta Hai?

```
Normal HAR: "Abhi user WALKING hai" (bas itna)

Humara system: "User ne STILL se WALKING transition kiya 
               09:45:23 AM pe, 96% confidence ke saath"

Yeh TRANSITION MOMENTS detect karta hai — exact time ke saath!
```

### Kaise?

```
Har 2.56 seconds mein:
  previous_activity = "STILL"
  current_activity = "WALKING" (naya classification)
  confidence = 0.96

  Check: previous ≠ current? YES
  Check: confidence > 75%? YES (96% > 75%)
  
  → TRANSITION EVENT logged! 🔄
    "STILL → WALKING at 09:45:23 (96%)"
    
  transition_count++
  
Log mein aisa dikhta hai:
  #1: 09:30:00 — UNKNOWN → STILL (99%)     ← App start hua
  #2: 09:45:23 — STILL → WALKING (96%)     ← Chalna shuru kiya
  #3: 09:47:45 — WALKING → STAIRS_UP (88%) ← Seedhiyan chadhi
  #4: 09:49:12 — STAIRS_UP → STILL (92%)   ← Class mein baith gaya
```

---

## 💚 Part 7: Daily Wellness Score (NEW)

### Kya Hai?

```
Poore din ka ek SINGLE NUMBER — 0 se 100 — jo batata hai
"Aaj tera din health-wise kaisa gaya?"

Score = 30% × Activity Diversity   (Kitni variety ki activities?)
      + 30% × Ergonomic Health     (Kitna slouch kiya?)
      + 20% × Movement Adequacy    (Kitna chala? 6000 steps goal)
      + 20% × Safety Score         (Koi fall hua kya?)
```

### Sub-Scores Kaise Calculate Hote Hain?

```
1. Activity Diversity Score (0-100):
   Unique activities kiye / 5 × 100
   
   Sirf STILL kiya → 1/5 = 20
   STILL + WALKING + STAIRS → 3/5 = 60
   5 ya zyada unique activities → 100 (Max!)

2. Ergonomic Score (0-100):
   100 - (slouch_minutes / total_sitting_minutes × 100)
   
   60 min baitha, 0 min slouch → 100 (Perfect!)
   60 min baitha, 30 min slouch → 50 (Half time slouching)
   60 min baitha, 60 min slouch → 0 (Always slouching!)

3. Movement Score (0-100):
   min(100, total_steps / 6000 × 100)
   
   0 steps → 0 (Hila hi nahi!)
   3000 steps → 50
   6000+ steps → 100 (Daily goal achieved!)

4. Safety Score (0-100):
   100 - (fall_events × 25)
   
   0 falls → 100 (Safe day!)
   1 fall → 75
   4+ falls → 0 (Bahut dangerous day)
```

### Emoji System:
```
💚 80-100: "Excellent! Great day!"
💛 60-79:  "Good, keep it up!"
🟠 40-59:  "Fair, move more!"
❤️ 0-39:   "Needs improvement..."
```

---

## 🔌 Part 8: Sensor Abstraction Layer (NEW — Architecture)

### Yeh Kyun Banaya?

```
Abhi: Phone ke built-in sensors se data aata hai
Future mein: BLE (Bluetooth) wristband ya earbuds se bhi aa sakta hai

Prof. Suchetana ki recent papers (WristSense 2026, SpineSense 2026)
mein woh EXACTLY yeh karti hain — wrist/ear sensors se health data!

Toh humne architecture aise design kiya ki:
```

```
interface SensorDataSource {
    fun start(callback)     // Sensor shuru karo
    fun stop()              // Sensor band karo
    fun getSourceName()     // "Phone IMU" ya "BLE Wristband"
}

class PhoneIMUSource : SensorDataSource {
    // Yeh ABHI kaam karta hai — phone ke sensors use karta hai
}

class BLEWearableSource : SensorDataSource {
    // Yeh FUTURE mein kaam karega — Bluetooth wearable se data
    // Abhi stub hai (placeholder code)
}
```

**Fayda:** Kal ko agar Prof. bole "yeh earbuds ke sensors se bhi chala do" — toh sirf BLEWearableSource fill karna padega, baaki poora pipeline (AI model, posture tracking, fall detection, sab) wahi same rahega!

---

## 🏗️ Part 9: Android Architecture — Sab Kaise Juda Hai

```
PHONE (LOCKED, SCREEN OFF, POCKET MEIN)
═══════════════════════════════════════

┌─────────────────────────────────────────────────────┐
│      SensingForegroundService (Background mein)      │
│      ═══════════════════════════════════════          │
│                                                      │
│  Accelerometer ──→ latestAx, latestAy, latestAz     │
│  (har 20ms)        │                                 │
│                    ├──→ FallDetector (har tick pe)    │
│                    │                                 │
│                    ▼                                 │
│  Gyroscope ──→ latestGx, latestGy, latestGz         │
│  (har 20ms)    │                                     │
│                ▼                                     │
│  ┌──────────────────────────────────┐                │
│  │ BUFFER: 128 rows × 6 columns    │                │
│  │ [ax,ay,az,gx,gy,gz] × 128      │                │
│  │ Bhar jaata hai 2.56 sec mein    │                │
│  └───────────────┬──────────────────┘                │
│                  │ (jab 128 samples poore ho gaye)   │
│                  ▼                                   │
│  ┌──────────────────────┐                            │
│  │ DeepSense AI (91 KB) │──→ "WALKING (96%)"        │
│  └──────────┬───────────┘                            │
│             │                                        │
│    ┌────────┼────────┬──────────┬──────────┐         │
│    ▼        ▼        ▼          ▼          ▼         │
│ Posture  Fidget  Pothole   Transition  Wellness      │
│ Tracker  Index   Detector  Detector    Score         │
│    │        │        │          │          │          │
│    └────────┴────────┴──────────┴──────────┘         │
│                      │                               │
│                      ▼                               │
│    ┌─────────────────────────────────────┐           │
│    │ ADAPTIVE SAMPLING: Rate adjust!     │           │
│    │ STILL→5Hz | WALK→20Hz | RUN→50Hz   │           │
│    └─────────────────────────────────────┘           │
│                      │                               │
│           ┌──────────┴──────────┐                    │
│           ▼                     ▼                    │
│  Lock Screen Notification    Broadcast to App        │
│  "WALKING (96%) |            (jab app open ho)       │
│   Wellness: 72/100"                                  │
│                                                      │
└──────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────┐
│         MainActivity (Jab app khola hai)              │
│                                                      │
│  Card 1: 🧠 WALKING (96%) + Live ax, ay, az         │
│  Card 2: 🪑 Tilt: 12° Upright | Fidget: 0.01 Calm  │
│  Card 3: 🕳️ Potholes: 3 | Speed Breakers: 2        │
│  Card 4: 🛡️ Fall: Armed with Anti-False-Alarm       │
│  Card 5: 💚 Wellness: 72/100 (Diversity/Ergo/Move)  │
│  Card 6: ⚡ STILL→WALKING at 09:45 | 20 Hz Balanced │
│                                                      │
│  [████ STOP PERVASIVE SENSING ████]                  │
└──────────────────────────────────────────────────────┘
```

### Foreground Service Kyun?

```
Android normally background apps ko MARTA hai (battery save ke liye).

Foreground Service = Android ko bolna: "Yeh app IMPORTANT hai, 
mat maarna!" Android kehta "theek hai, lekin user ko notification 
dikhaana padega" → isliye lock screen pe notification dikhti hai.

WakeLock = CPU ko bhi jagaye rakhna jab screen off ho.
Bina WakeLock ke, phone "deep sleep" mein chala jaata aur sensors
band ho jaate.
```

---

## 📁 Part 10: Files Ka Summary

```
9 Kotlin Files — Kaunsa File Kya Karta Hai:

1. DeepSenseClassifier.kt (3.2 KB)
   → TFLite model load karta hai, 768 floats input deta hai,
     8 probabilities output mein leta hai
     
2. ErgonomicPostureTracker.kt (3.0 KB)
   → Gravity filter, tilt angle, fidget index, sedentary timer

3. RoadAnomalyDetector.kt (1.7 KB)
   → Z-axis sliding window, pothole vs speed breaker thresholds

4. FallDetector.kt (3.2 KB)
   → 3-state machine (MONITORING → IMPACT → CONFIRMED)

5. ActivityTransitionDetector.kt (1.4 KB)  ← NEW
   → Context change detection with timestamp logging

6. WellnessScoreEngine.kt (3.4 KB)  ← NEW
   → Daily health score computation (4 sub-scores)

7. SensorDataSource.kt (3.2 KB)  ← NEW
   → Interface for phone/BLE sensor abstraction

8. SensingForegroundService.kt (14.2 KB)
   → MAIN ENGINE — sensor reading, buffer filling, AI inference,
     all novelty modules calling, adaptive sampling, broadcasting

9. MainActivity.kt (9.6 KB)
   → Dashboard UI — 6 cards, BroadcastReceiver, permissions

PLUS:
- deepsense_int8.tflite (91 KB) — Trained AI model
- activity_main.xml (17 KB) — Dark theme UI layout
- AndroidManifest.xml (2.2 KB) — Permissions + service declaration
```

---

## 🎓 Prof. Suchetana Ko Kaise Impress Karna Hai

```
1. "On-device AI" → Koi cloud nahi, koi latency nahi, privacy safe
2. "Adaptive sampling" → Energy efficiency (uski ReMEC paper jaisi)
3. "Wellness Score" → Sensor data se health insight (uski WristSense jaisi)
4. "Sensor Abstraction" → BLE wearable ready (uski SpineSense jaisi)
5. "Anti-false-alarm" → Real research contribution, not toy project
6. "91 KB model, 0.2ms inference" → TRUE edge AI, not server-side
7. "Background operation" → TRUE pervasive computing, screen off mein bhi

Report mein likhna: "Inspired by the holistic inertial health 
profiling paradigm proposed in WristSense [Chakraborty et al., 2026]"

→ Uski APNI paper cite karoge → RESPECT PAKKA! 🎯
```
