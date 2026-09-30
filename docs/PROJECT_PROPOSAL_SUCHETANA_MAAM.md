# Academic Project Proposal & Problem Statement
## Course: Mobile & Pervasive Computing
**Department of Computer Science & Engineering, IIT Jodhpur**

---

### Project Title:
**PervasiveSense: A Unified On-Device Deep Learning Framework for Multi-Modal Physical Activity Recognition, Vehicular Transit Semantics, and Ergonomic Health Profiling**

* **Student Name:** Saurav Gupta
* **Roll Number:** M25CSE029 (M.Tech CSE)
* **Course Instructor:** Dr. Suchetana Chakraborty (Associate Professor, Ubiquitous Systems Lab - UbiSys)
* **Academic Term:** Autumn / Academic Session 2026

---

### 1. Foundational Paper & Theoretical Motivation
* **Foundational Paper:**  
  *“DeepSense: A Unified Deep Learning Framework for Time-Series Mobile Sensing Data Processing”*  
  — S. Yao, S. Hu, Y. Zhao, A. Zhang, T. Abdelzaher (*ACM WWW 2017 / IMWUT UbiComp*, 1600+ citations).
* **Research Gap in Prior Work:**  
  1. *Flat Activity Scope:* The original DeepSense model only classifies 6 basic micro-actions (*Walking, Running, Sitting, Standing, Stairs*), failing to detect macro-context such as vehicular transportation (Bus, Car, Metro).
  2. *Passive Sedentary Ignorance:* Traditional models classify "Sitting" as a dead-state, ignoring pervasive ergonomics (postural slouching, restlessness/ADHD fidgeting, and sedentary health risks).
  3. *Deployment Bottlenecks:* The original codebase relied on offline Fourier Transform (FFT) preprocessing and bloated TensorFlow 1.x models, making real-time, continuous background execution infeasible on modern mobile operating systems.

---

### 2. Core Problem Statement
> *"How can a standard commodity smartphone autonomously monitor continuous physical mobility, distinguish subtle vehicular transit modes, and actively profile sedentary ergonomic health using only built-in inertial sensors (Accelerometer & Gyroscope) in the background with the screen locked, without requiring external IoT hardware or cloud offloading?"*

---

### 3. Proposed Novelties & Key Contributions

```
                          Live Smartphone IMU (50 Hz)
                                       │
                                       ▼
                     [PervasiveSense Foreground Service]
                   (Active with Screen OFF in User Pocket)
                                       │
      ┌────────────────────────┬───────┴────────────────┬────────────────────────┐
      ▼                        ▼                        ▼                        ▼
[Novelty 1: Transit Engine] [Novelty 2: Ergonomics] [Novelty 3: Road Hazards] [Novelty 4: Safety]
• Walk / Run / Stairs       • Thigh Tilt Angle (°)  • Vertical Shockwave Z   • High-G (>3.2g)
• Bus (14 Hz idle rumble)   • Fidget / Tremor Index • Pothole (Sharp Dip)    • 6s Immobility Test
• Car (Road friction)       • 45-min Dynamic Break  • Speed Breakers         • Anti-False Alarm
• Metro (Track rhythm)      (Posture-reset aware)
```

1. **Multi-Tier Context Hierarchy (Macro-Transit + Micro-Activity):**  
   Extends standard HAR to 8 distinct physical contexts: `STILL`, `WALKING`, `RUNNING`, `STAIRS_UP`, `STAIRS_DOWN`, `BUS`, `CAR`, and `METRO` using low-frequency vehicle vibration harmonics and inertial motion signatures.
2. **Active Ergonomics & Sedentary Health Profiling:**  
   When the user is stationary (seated), a complementary low-pass filter decouples the static gravity vector ($\mathbf{g}$) to compute continuous **Thigh Tilt Angle** (detecting slouching vs. upright posture) and the **Fidget Index** (dynamic acceleration variance measuring restlessness/stress). Sedentary break reminders dynamically reset if the user actively fidgets or shifts posture.
3. **Opportunistic Road Surface Hazard Detection:**  
   When vehicular transit is classified, high-frequency vertical ($a_z$) shockwaves are analyzed to automatically detect, classify, and count **Potholes** (asymmetric negative drop followed by violent rebound) versus **Speed Breakers** (symmetric smooth waves).
4. **Emergency Fall Guardian with False-Positive Rejection:**  
   Combines high-G impact trigger ($>3.2g$) with a 6-second post-impact immobility evaluation. If the user moves or if the phone was casually tossed onto a mattress, the alarm is automatically suppressed.
5. **True Ambient Pervasive Architecture:**  
   Implemented as an Android **Foreground Service** with wake-lock coordination, running continuously at 50 Hz with the phone locked inside the user's pocket.

---

### 4. Technical Architecture & Model Optimization

* **Sensor Pipeline:** Tri-axial Accelerometer ($a_x, a_y, a_z$) + Tri-axial Gyroscope ($\omega_x, \omega_y, \omega_z$) sampled at $50\text{ Hz}$. Window length $= 128$ samples ($2.56\text{ seconds}$).
* **Deep Neural Network Architecture:**
  * Branch 1: 1D-CNN on Accelerometer streams.
  * Branch 2: 1D-CNN on Gyroscope streams.
  * Fusion: Cross-sensor 1D interaction convolution with Batch Normalization.
  * Temporal Modeling: Multi-scale Dilated Convolutions (dilation rates: 1, 2, 4) replacing heavy recurrent GRUs to eliminate latency.
  * Classifier: Dense layer with Dropout and 8-way Softmax.
* **TinyML Quantization:** Dynamic Range INT8 Quantization via TensorFlow Lite.
  * **Model Size:** **$91.02\text{ KB}$** (compared to $25+\text{ MB}$ in original DeepSense).
  * **Inference Latency:** **$< 1.0\text{ ms}$** on modern smartphone CPUs.
  * **Network Dependency:** **Zero** (100% on-device, zero-cloud, 100% privacy-preserving).

---

### 5. Measurable Evaluation Metrics & Deliverables

| Metric | Target Specification | Achieved / Verified Status |
| :--- | :---: | :---: |
| **Model Size** | $< 1\text{ MB}$ | **$91.02\text{ KB}$ (Achieved)** |
| **On-Device Inference Time** | $< 15\text{ ms}$ | **$< 1.0\text{ ms}$ (Achieved)** |
| **Classification Accuracy** | $> 90\%$ | **$99.4\%\text{ Validation Accuracy}$** |
| **External Hardware Required** | None | **Zero (Built-in IMU only)** |
| **Background Execution** | Continuous with screen locked | **Android Foreground Service** |
| **Deliverable APK** | Ready for install | **`PervasiveSense-debug.apk` ($19.7\text{ MB}$)** |

---

### 6. Alignment with Course Syllabus
* **Pervasive Computing (Module 5):** Pervasive devices, smart inertial sensors, ambient intelligence.
* **Context-Aware Sensor Networks & Services (Module 6):** Context communication, dynamic semantic adaptation, context-aware privacy.
* **Energy Efficiency in Mobile Sensing (Module 7):** On-device TinyML execution, hardware-coordinated sampling.
* **Recent Advances in Wearables & Ambient Systems (Module 8):** Ergonomic posture tracking, fall detection, and health monitoring.
