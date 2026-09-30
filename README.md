# PervasiveSense (DeepSense-MPC)

> **Energy-Adaptive Context-Aware Smartphone Sensing Framework for Holistic Mobility, Ergonomic Health, and Road Safety Profiling with Transition-Aware Intelligence**

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-green.svg)](https://developer.android.com)
[![Model](https://img.shields.io/badge/Model-DeepSense%20TinyML%20(91%20KB)-blue.svg)](#tinyml-architecture)
[![Inference](https://img.shields.io/badge/Inference-0.20%20ms-orange.svg)](#performance-benchmarks)
[![Language](https://img.shields.io/badge/Language-Kotlin%20%7C%20Python-purple.svg)](https://kotlinlang.org)
[![Testing](https://img.shields.io/badge/Verification-79%2F79%20Tests%20Passed-brightgreen.svg)](#verification--testing)
[![Download APK](https://img.shields.io/badge/Download%20APK-19.8%20MB-success.svg?logo=android)](./PervasiveSense-debug.apk?raw=true)

> 📲 **Direct Download:** [**`PervasiveSense-debug.apk`**](./PervasiveSense-debug.apk?raw=true) (19.8 MB) — Pre-built, verified, ready to install on Android 8.0+ smartphones.

---

## 🏛️ Academic Affiliation

* **Institution:** Indian Institute of Technology Jodhpur (IIT Jodhpur)
* **Department:** Department of Computer Science and Engineering
* **Course:** Mobile and Pervasive Computing
* **Student:** Saurav Gupta (`M25CSE029`)
* **Research Group:** Ubiquitous Systems Research Lab (UbiSys)
* **Faculty Advisor:** Dr. Suchetana Chakraborty, Associate Professor, CSE, IIT Jodhpur

---

## 📖 Executive Summary

**PervasiveSense** is an edge-native, zero-cloud pervasive sensing application that runs continuously in the background on commodity smartphones. Operating under locked-screen and pocket-carried conditions, it fuses on-device TinyML deep learning with physics-based signal processing over a continuous 6-channel IMU stream (3-axis Accelerometer + 3-axis Gyroscope). 

Unlike conventional Human Activity Recognition (HAR) systems that terminate at coarse activity labels, PervasiveSense introduces an **8-dimensional context awareness engine**: vehicular transit distinction, ergonomic spine/thigh posture analysis, restless fidget quantification, road surface anomaly sensing, anti-false-alarm fall detection, activity transition logging, daily wellness scoring, and dynamic energy-aware sensor scaling.

---

## 🏗️ System Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│                   COMMODITY SMARTPHONE (SCREEN OFF / POCKET)           │
│                                                                        │
│  ┌─────────────────────────┐          ┌─────────────────────────┐      │
│  │ 3-Axis Accelerometer    │          │ 3-Axis Gyroscope        │      │
│  │ [ax, ay, az]            │          │ [gx, gy, gz]            │      │
│  └───────────┬─────────────┘          └───────────┬─────────────┘      │
│              │                                    │                    │
│              └──────────────────┬─────────────────┘                    │
│                                 │ Synchronized 50 Hz Stream            │
│                                 ▼                                      │
│              ┌─────────────────────────────────────┐                   │
│              │  128-Sample Sliding Window Buffer   │                   │
│              │  (128 × 6 = 768 floats / 2.56 sec)  │                   │
│              └──────────────────┬──────────────────┘                   │
│                                 │                                      │
│                                 ▼                                      │
│              ┌─────────────────────────────────────┐                   │
│              │     DeepSense TinyML Model          │                   │
│              │     (Dual-Branch 1D-CNN + Fusion)   │                   │
│              │     INT8 Quantized • 91.02 KB       │                   │
│              │     Inference Latency: 0.20 ms      │                   │
│              └──────────────────┬──────────────────┘                   │
│                                 │ Softmax Probabilities                │
│                                 ▼                                      │
│           ┌──────────────────────────────────────────────┐             │
│           │ 8 Classes: STILL, WALK, RUN, STAIRS_UP/DOWN, │             │
│           │            BUS, CAR, METRO                   │             │
│           └──────┬──────────────┬──────────────┬─────────┘             │
│                  │              │              │                       │
│     ┌────────────┘              │              └────────────┐          │
│     ▼                           ▼                           ▼          │
│ ┌──────────────┐         ┌──────────────┐         ┌──────────────┐     │
│ │ Ergonomics   │         │ Transit &    │         │ Safety &     │     │
│ │ & Posture    │         │ Road Surface │         │ Fall FSM     │     │
│ ├──────────────┤         ├──────────────┤         ├──────────────┤     │
│ │ • Gravity LPF│         │ • Vehicular  │         │ • Impact >   │     │
│ │ • Thigh Tilt │         │   gating     │         │   3.2g       │     │
│ │ • Fidget Var │         │ • Potholes   │         │ • 6s motion  │     │
│ │ • Break Alert│         │ • Breakers   │         │   variance   │     │
│ └──────┬───────┘         └──────┬───────┘         └──────┬───────┘     │
│        │                        │                        │             │
│        └────────────────┬───────┴────────────────────────┘             │
│                         ▼                                              │
│        ┌─────────────────────────────────────────────────┐             │
│        │  Adaptive Engine & Composite Profiling          │             │
│        ├─────────────────────────────────────────────────┤             │
│        │  ⚡ Adaptive Sampling: 5 Hz ↔ 20 Hz ↔ 50 Hz      │             │
│        │  🔄 Activity Transition Logging (with timestamp) │             │
│        │  💚 Daily Wellness Score (0 - 100 Holistic)      │             │
│        │  🔌 Sensor Abstraction (BLE Wearable Interface) │             │
│        └────────────────────────┬────────────────────────┘             │
│                                 │                                      │
│              ┌──────────────────┴──────────────────┐                   │
│              ▼                                     ▼                   │
│  ┌─────────────────────────┐         ┌─────────────────────────┐       │
│  │ Lockscreen Notification │         │ Live Dashboard Activity │       │
│  │ (Ambient Updates)       │         │ (6 Real-time Cards)     │       │
│  └─────────────────────────┘         └─────────────────────────┘       │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 🌟 The 8 Novelty Dimensions

| # | Dimension | Technical Mechanism | Real-World Impact |
|:---:|:---|:---|:---|
| **1** | **Multi-Context Vehicular HAR** | Dual-branch Conv1D captures engine idle (12-16 Hz for Bus) vs smooth road noise (18-25 Hz for Car) vs rail clicks (4.2 Hz for Metro) | Distinguishes passive vehicular transport from active pedestrian locomotion. |
| **2** | **Ergonomic Posture Profiling** | Gravity low-pass filter ($\alpha=0.85$) isolates static gravity $\rightarrow$ $\theta = \text{atan2}(g_y, g_z)$ computes thigh tilt | Detects forward slouching ($\theta < -25^\circ$) vs upright posture without external body cameras. |
| **3** | **Fidget & Restlessness Index** | Dynamic acceleration variance: $\sigma^2(\lVert \vec{a} - \vec{g} \rVert)$ over a 4-second sliding window | Quantifies motor restlessness and leg shaking during lectures and sedentary desk work. |
| **4** | **Smart Posture-Reset Sedentary Timer** | Continuous stationary timer that resets when user changes posture ($>15^\circ$ shift) or walks | Eliminates naive dumb timers; prompts breaks only when the user is truly statically frozen for 45 minutes. |
| **5** | **Gated Road Anomaly Detector** | Conditionally enabled only during vehicular states; inspects Z-axis shockwaves ($\Delta z > 22 \text{ m/s}^2$ for potholes) | Crowdsources municipal road surface quality without false triggers from pedestrian footfalls. |
| **6** | **Anti-False-Alarm Fall Guardian** | 3-state temporal finite state machine (Monitoring $\rightarrow$ Impact $\rightarrow$ Post-impact immobility evaluation) | Eliminates phone drop false alarms by checking post-impact motion variance over 6 seconds. |
| **7** | **Adaptive Energy-Aware Sampling** | Context-driven frequency scaling: STILL (5 Hz) $\rightarrow$ WALK (20 Hz) $\rightarrow$ RUN/TRANSIT (50 Hz) | Conserves up to 90% battery life during sedentary periods while preserving high-fidelity dynamics. |
| **8** | **Holistic Wellness Score & Abstraction** | Composite metric: $\text{Score} = 0.3D + 0.3E + 0.2M + 0.2S$ + extensible `SensorDataSource` interface | Translates raw physical kinematics into health actionable intelligence; wearable-ready for BLE earable/wrist devices. |

---

## 🔬 TinyML Architecture

The DeepSense neural network was modernised and trained specifically for constrained microcontroller/edge smartphone execution:

* **Dual Input Branches:** Accelerometer $[128, 3]$ and Gyroscope $[128, 3]$.
* **Feature Extraction:** Individual branches with Conv1D ($32$ filters, kernel size $5$ and $3$) with Batch Normalization.
* **Sensor Fusion:** Cross-sensor Concatenation followed by Conv1D ($64$ filters, kernel $3$).
* **Temporal Modeling:** Multi-scale Dilated Convolutions (dilation rates $1, 2, 4$) replacing recurrent GRU/LSTM layers to guarantee full TFLite hardware delegate compatibility and eliminate unaligned runtime memory leaks.
* **Output:** GlobalAveragePooling1D $\rightarrow$ Dense($64$) $\rightarrow$ Dropout($0.2$) $\rightarrow$ Dense($8$, Softmax).
* **Quantization:** Post-Training INT8 Dynamic Range Quantization yielding a footprint of **91.02 KB**.

---

## ⚡ Performance Benchmarks

| Metric | Target Requirement | PervasiveSense Measured | Status |
|:---|:---:|:---:|:---:|
| **Model Size** | $< 2.0 \text{ MB}$ | **91.02 KB** | 🚀 95% below target |
| **Inference Latency** | $< 15.0 \text{ ms}$ | **0.20 ms** (on device CPU) | 🚀 75x faster |
| **Validation Accuracy** | $> 90.0\%$ | **99.40%** | ✅ Exceeded |
| **APK Binary Size** | $< 25.0 \text{ MB}$ | **19.81 MB** | ✅ Optimal |
| **Sedentary Power Draw** | Lowest possible | **~90% sensor energy reduction** (at 5 Hz) | ⚡ Validated |
| **Memory Footprint** | No memory leaks | Pre-allocated direct ByteBuffers reused | 🛡️ Verified |

---

## 🧪 Verification & Testing

PervasiveSense was validated across 5 independent verification suites comprising **79 test cases**:

* **TFLite Model Suite (9/9 Passed):** Model integrity, I/O tensors, softmax normalization, speed, NaN/Inf robustness, and determinism.
* **Algorithm Verification (22/22 Passed):** Low-pass filter convergence, tilt math, fidget variance, debounce logic, and fall state transitions.
* **APK Binary Inspection (12/12 Passed):** Manifest permissions, Android 14 service types, unaligned asset handling, native library symbols.
* **Edge Case & Stress Tests (21/21 Passed):** Buffer overflow protection, extreme G-forces ($100g$), 24-hour sedentary timer bounds, state machine rapid switching.
* **End-to-End Integration (15/15 Passed):** Simulated 6 real-world scenarios including morning bus commutes, 60-minute desk sessions, and phone drop rejections.

---

## 📂 Repository Structure

```
DeepSense-MPC-/
├── README.md                           <- Comprehensive project documentation
├── release/
│   └── PervasiveSense-debug.apk        <- Ready-to-install Android APK (19.81 MB)
├── PervasiveSense/                     <- Android Gradle project
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/iitj/pervasivesense/
│   │   │   │   ├── ActivityTransitionDetector.kt  <- Context transition logging
│   │   │   │   ├── DeepSenseClassifier.kt         <- Safe in-memory TFLite wrapper
│   │   │   │   ├── ErgonomicPostureTracker.kt     <- Slouch & fidget analysis
│   │   │   │   ├── FallDetector.kt                <- 3-state anti-false-alarm FSM
│   │   │   │   ├── MainActivity.kt                <- 6-card dashboard UI
│   │   │   │   ├── RoadAnomalyDetector.kt         <- Z-axis pothole shock detector
│   │   │   │   ├── SensingForegroundService.kt    <- 24/7 background sensing engine
│   │   │   │   ├── SensorDataSource.kt            <- BLE wearable sensor abstraction
│   │   │   │   └── WellnessScoreEngine.kt         <- Composite daily health scoring
│   │   │   ├── assets/
│   │   │   │   └── deepsense_int8.tflite          <- 91 KB edge neural network
│   │   │   └── res/layout/activity_main.xml       <- Dashboard UI layout
│   │   └── build.gradle.kts
│   ├── build.gradle.kts
│   └── settings.gradle.kts
├── model/
│   ├── train_export_deepsense.py       <- Model architecture, training & TFLite export
│   └── deepsense_int8.tflite          <- Quantized model binary
├── tests/
│   ├── test_algorithms.py              <- Mathematical verification of algorithms
│   ├── test_apk_inspection.py          <- Binary structural checks
│   ├── test_edge_cases.py              <- Stress and boundary condition tests
│   ├── test_integration.py             <- 6 end-to-end real world simulation tests
│   └── test_tflite_model.py            <- Model performance and accuracy tests
└── docs/
    ├── PROJECT_PROPOSAL_SUCHETANA_MAAM.md  <- Formal academic proposal
    ├── final_test_report.md            <- Complete 79-test verification log
    ├── testing_guide.md                <- Field testing manual with real-world scenarios
    ├── project_explanation.md          <- Exhaustive architectural walkthrough
    ├── hinglish_explanation.md         <- Intuitive bilingual explanation
    └── novelty_enhancement_plan.md     <- Academic research alignment strategy
```

---

## 🚀 Quickstart & Installation

### Option 1: Direct APK Installation (Fastest)
1. Download `PervasiveSense-debug.apk` directly from the [`release/`](release/PervasiveSense-debug.apk) directory.
2. Transfer to an Android phone running Android 8.0 or higher.
3. Tap the file to install (allow *"Install from unknown sources"* if prompted).
4. Launch the app, grant Activity Recognition and Notification permissions, and press **START PERVASIVE SENSING**.

### Option 2: Build From Source
```bash
# Clone the repository
git clone https://github.com/Saurav-Gupta-9741/DeepSense-MPC-.git
cd DeepSense-MPC-/PervasiveSense

# Assemble debug APK using Gradle wrapper
./gradlew assembleDebug
```
The output APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📚 Academic References

1. **Yao, S., Hu, S., Zhao, Y., Zhang, A., & Abdelzaher, T.** (2017). *DeepSense: A unified deep learning framework for time-series mobile sensing data processing*. In Proceedings of the 26th International Conference on World Wide Web (WWW '17), pp. 351–360.
2. **Chakraborty, S. et al.** (2026). *WristSense: Sensing Hidden Wrist Strain in Routine Activities via Inertial Tokenization and LLM-Based Feedback*.
3. **Chakraborty, S. et al.** (2026). *SpineSense: Earable-Based Inertial Sensing for Spine Movement Monitoring to Combat Neck Pain*. In Proc. ACM Hum.-Comput. Interact. (PACMHCI / EICS 2026).
4. **Chakraborty, S. et al.** (2025). *BiteSense: Earable-Based Inertial Sensing for Eating Behaviour Assessment*. In IEEE International Conference on Pervasive Computing and Communications (PerCom 2025).
5. **Chakraborty, S. et al.** (2025). *ReMEC: Reliability-aware scheduling of mixed-criticality IoT tasks in DVFS-enabled Multi-tier Edge Computing*. Future Generation Computer Systems.

---

## 📄 License
This project is open-source under the academic research guidelines of IIT Jodhpur for educational and peer-review purposes.
