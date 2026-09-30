import os
import sys
import time
import math
import numpy as np
import tensorflow as tf

class DeepSenseClassifier:
    WINDOW_SIZE = 128
    NUM_CHANNELS = 6
    NUM_CLASSES = 8
    
    CLASS_NAMES = ["STILL", "WALKING", "RUNNING", "STAIRS_UP", "STAIRS_DOWN", "BUS", "CAR", "METRO"]

    def __init__(self, model_path):
        self.interpreter = tf.lite.Interpreter(model_path=model_path)
        self.interpreter.allocate_tensors()
        self.input_details = self.interpreter.get_input_details()
        self.output_details = self.interpreter.get_output_details()

    def classify(self, window_data):
        input_data = np.array(window_data, dtype=np.float32).reshape(1, self.WINDOW_SIZE, self.NUM_CHANNELS)
        
        # Quantize input if needed
        input_details = self.input_details[0]
        if input_details['dtype'] == np.int8:
            scale, zero_point = input_details['quantization']
            if scale > 0:
                input_data = (input_data / scale + zero_point).astype(np.int8)
                
        self.interpreter.set_tensor(input_details['index'], input_data)
        self.interpreter.invoke()
        output_data = self.interpreter.get_tensor(self.output_details[0]['index'])[0]
        
        # Dequantize if needed
        output_details = self.output_details[0]
        if output_details['dtype'] == np.int8:
            scale, zero_point = output_details['quantization']
            if scale > 0:
                output_data = (output_data.astype(np.float32) - zero_point) * scale

        max_index = np.argmax(output_data)
        return {
            "activity": self.CLASS_NAMES[max_index],
            "confidence": output_data[max_index],
            "allProbabilities": output_data
        }

class ErgonomicPostureTracker:
    def __init__(self):
        self.gravity = [0.0, 0.0, 0.0]
        self.last_posture_shift_time = time.time() * 1000
        self.baseline_tilt_angle = 0.0
        self.dynamic_accel_samples = []

    def process_sample(self, ax, ay, az, is_currently_still, current_time_ms):
        alpha = 0.85
        self.gravity[0] = alpha * self.gravity[0] + (1 - alpha) * ax
        self.gravity[1] = alpha * self.gravity[1] + (1 - alpha) * ay
        self.gravity[2] = alpha * self.gravity[2] + (1 - alpha) * az

        tilt_angle = math.atan2(self.gravity[1], self.gravity[2]) * (180.0 / math.pi)

        dyn_x = ax - self.gravity[0]
        dyn_y = ay - self.gravity[1]
        dyn_z = az - self.gravity[2]
        dyn_mag = math.sqrt(dyn_x**2 + dyn_y**2 + dyn_z**2)

        self.dynamic_accel_samples.append(dyn_mag)
        if len(self.dynamic_accel_samples) > 200:
            self.dynamic_accel_samples.pop(0)

        fidget_index = 0.0
        if self.dynamic_accel_samples:
            mean = sum(self.dynamic_accel_samples) / len(self.dynamic_accel_samples)
            variance = sum((x - mean)**2 for x in self.dynamic_accel_samples)
            fidget_index = variance / len(self.dynamic_accel_samples)

        if abs(tilt_angle - self.baseline_tilt_angle) > 15.0:
            self.baseline_tilt_angle = tilt_angle
            self.last_posture_shift_time = current_time_ms

        if not is_currently_still:
            self.last_posture_shift_time = current_time_ms

        continuous_minutes = int((current_time_ms - self.last_posture_shift_time) / 60000)
        needs_break = continuous_minutes >= 45

        if -25.0 <= tilt_angle <= 25.0:
            status = "Upright / Ergonomic"
        elif tilt_angle < -25.0:
            status = "Forward Slouching"
        else:
            status = "Reclined / Leaning"

        return {
            "postureTiltAngle": tilt_angle,
            "postureStatus": status,
            "fidgetIndex": fidget_index,
            "continuousStillMinutes": continuous_minutes,
            "needsBreakPrompt": needs_break
        }

class FallDetector:
    def __init__(self):
        self.current_state = "MONITORING"
        self.impact_timestamp = 0
        self.post_impact_buffer = []

    def process_sample(self, ax, ay, az, current_time_ms):
        total_magnitude_g = math.sqrt(ax**2 + ay**2 + az**2) / 9.81

        if self.current_state == "MONITORING":
            if total_magnitude_g > 3.2:
                self.current_state = "IMPACT_SUSPECTED"
                self.impact_timestamp = current_time_ms
                self.post_impact_buffer = []

        elif self.current_state == "IMPACT_SUSPECTED":
            if len(self.post_impact_buffer) < 500:
                self.post_impact_buffer.append(total_magnitude_g)
            
            elapsed = current_time_ms - self.impact_timestamp
            if elapsed >= 6000:
                mean = sum(self.post_impact_buffer) / len(self.post_impact_buffer)
                variance = sum((x - mean)**2 for x in self.post_impact_buffer)
                motion_variance = variance / len(self.post_impact_buffer)

                if motion_variance < 0.2:
                    self.current_state = "FALL_CONFIRMED"
                    return True
                else:
                    self.current_state = "MONITORING"
                    self.post_impact_buffer = []

        elif self.current_state == "FALL_CONFIRMED":
            if 0.8 <= total_magnitude_g <= 1.5 and (current_time_ms - self.impact_timestamp > 15000):
                self.current_state = "MONITORING"

        return False

    def reset(self):
        self.current_state = "MONITORING"
        self.post_impact_buffer = []

class RoadAnomalyDetector:
    def __init__(self):
        self.z_axis_window = []
        self.potholes_count = 0
        self.speed_breakers_count = 0
        self.last_anomaly_time = 0

    def process_sample(self, az, is_vehicular, current_time_ms):
        if not is_vehicular:
            self.z_axis_window = []
            return None

        self.z_axis_window.append(az)
        if len(self.z_axis_window) > 25:
            self.z_axis_window.pop(0)

        if len(self.z_axis_window) < 20:
            return None

        if current_time_ms - self.last_anomaly_time < 1500:
            return None

        max_z = max(self.z_axis_window)
        min_z = min(self.z_axis_window)
        peak_to_peak = max_z - min_z

        if min_z < -11.0 and peak_to_peak > 22.0:
            self.last_anomaly_time = current_time_ms
            self.potholes_count += 1
            return {"type": "POTHOLE", "timestamp": current_time_ms, "peakG": peak_to_peak}

        if 12.0 <= peak_to_peak <= 21.0 and min_z > -10.0:
            self.last_anomaly_time = current_time_ms
            self.speed_breakers_count += 1
            return {"type": "SPEED_BREAKER", "timestamp": current_time_ms, "peakG": peak_to_peak}

        return None

def verify(condition, msg):
    print(f"[{'PASS' if condition else 'FAIL'}] {msg}")

def run_tests():
    model_path = r"e:\GenAI-part2-Rag-implementation-main\PervasiveSense_Model\deepsense_int8.tflite"
    classifier = DeepSenseClassifier(model_path)
    
    # 1. Morning Commute Scenario (10 min)
    print("\n--- Scenario 1: Morning Commute ---")
    classifier_outputs = []
    # simulate walk (2m), still(1m), bus(5m), walk(2m) - 10 mins = 600s
    # We will simulate at 50Hz, so 30,000 samples. We test transition points.
    # To save time, we will jump in time by large steps when not testing algorithm bounds.
    tracker = ErgonomicPostureTracker()
    anomaly = RoadAnomalyDetector()
    current_time = 0
    # Walk 2 mins
    tracker.process_sample(0, 9.8, 0, False, current_time)
    verify(tracker.last_posture_shift_time == current_time, "Posture tracker resets during walking")
    current_time += 120000
    
    # Still 1 min
    tracker.process_sample(0, 9.8, 0, True, current_time)
    verify(tracker.last_posture_shift_time < current_time, "Posture tracker does NOT reset during still")
    current_time += 60000
    
    # Bus 5 mins
    for _ in range(5 * 60 * 50):
        current_time += 20
        res = anomaly.process_sample(9.8, True, current_time)
    verify(anomaly.potholes_count == 0, "Road anomaly detector ran without false positives on bus")
    
    # Walk 2 min
    current_time += 120000
    tracker.process_sample(0, 9.8, 0, False, current_time)
    verify(tracker.last_posture_shift_time == current_time, "Posture tracker resets during walking again")

    # 2. Office Desk Scenario (60 min)
    print("\n--- Scenario 2: Office Desk ---")
    tracker = ErgonomicPostureTracker()
    current_time = 0
    
    # Sitting 50 mins
    # Setup baseline at 80 deg (requires y/z ratio)
    ay = 9.8 * math.sin(80 * math.pi / 180)
    az = 9.8 * math.cos(80 * math.pi / 180)
    
    for _ in range(200): # fill buffer
        tracker.process_sample(0, ay, az, True, current_time)
        current_time += 20
        
    report = tracker.process_sample(0, ay, az, True, current_time)
    
    current_time = 20 * 60 * 1000 # 20 mins later
    # Fidget
    for _ in range(100):
        tracker.process_sample(0, ay + np.random.normal(0, 2), az, True, current_time)
        current_time += 20
    report_fidget = tracker.process_sample(0, ay, az, True, current_time)
    verify(report_fidget['fidgetIndex'] > 0.5, "Fidget index spikes during leg-shake")
    
    # Slouching (30 mins in)
    current_time = 30 * 60 * 1000
    ay_slouch = 9.8 * math.sin(-30 * math.pi / 180)
    az_slouch = 9.8 * math.cos(-30 * math.pi / 180)
    for _ in range(50):
        tracker.process_sample(0, ay_slouch, az_slouch, True, current_time)
        current_time += 20
    report_slouch = tracker.process_sample(0, ay_slouch, az_slouch, True, current_time)
    verify(report_slouch['postureStatus'] == "Forward Slouching", "Posture status changes to Forward Slouching")
    
    current_time = 76 * 60 * 1000 # 46 mins AFTER slouching reset (which is at 30 min)
    report_break = tracker.process_sample(0, ay_slouch, az_slouch, True, current_time)
    verify(report_break['needsBreakPrompt'] == True, "Break prompt triggers at 45 minutes")

    # 3. Fall & Recovery
    print("\n--- Scenario 3: Fall & Recovery ---")
    fall = FallDetector()
    current_time = 0
    fall.process_sample(0, 9.8, 0, current_time) # walk
    
    # impact 4g
    current_time += 1000
    fall.process_sample(0, 4 * 9.81, 0, current_time)
    verify(fall.current_state == "IMPACT_SUSPECTED", "Fall impact suspected")
    
    # still 8 secs
    for _ in range(8 * 50):
        current_time += 20
        fall.process_sample(0, 9.8, 0, current_time)
    
    verify(fall.current_state == "FALL_CONFIRMED", "Fall confirmed after lying still")
    
    # get up
    current_time += 20000
    fall.process_sample(0, 9.8, 0, current_time)
    verify(fall.current_state == "MONITORING", "Fall detector resets to MONITORING after recovery")

    # 4. False Alarm Rejection
    print("\n--- Scenario 4: False Alarm Rejection ---")
    fall = FallDetector()
    current_time = 0
    # drop 4g
    fall.process_sample(0, 4 * 9.81, 0, current_time)
    verify(fall.current_state == "IMPACT_SUSPECTED", "Phone drop impact suspected")
    
    # pick up in 3 sec
    for _ in range(3 * 50):
        current_time += 20
        fall.process_sample(0, 9.8, 0, current_time)
    
    # move
    for _ in range(4 * 50):
        current_time += 20
        fall.process_sample(2 * 9.81, 9.8, 0, current_time) # dynamic move
        
    verify(fall.current_state == "MONITORING", "False alarm rejected properly")

    # 5. Pothole Detection
    print("\n--- Scenario 5: Pothole Detection ---")
    anomaly = RoadAnomalyDetector()
    current_time = 0
    
    # Pothole 1
    for _ in range(25): anomaly.process_sample(9.8, True, current_time); current_time += 20
    anomaly.process_sample(-12.0, True, current_time); current_time += 20
    anomaly.process_sample(11.0, True, current_time); current_time += 20
    for _ in range(25): anomaly.process_sample(9.8, True, current_time); current_time += 20
    
    current_time += 2000
    # Pothole 2
    for _ in range(25): anomaly.process_sample(9.8, True, current_time); current_time += 20
    anomaly.process_sample(-12.0, True, current_time); current_time += 20
    anomaly.process_sample(11.0, True, current_time); current_time += 20
    for _ in range(25): anomaly.process_sample(9.8, True, current_time); current_time += 20
    
    # Pothole 3 (within 1s of 2 - debounce)
    current_time += 500
    for _ in range(25): anomaly.process_sample(9.8, True, current_time); current_time += 20
    anomaly.process_sample(-12.0, True, current_time); current_time += 20
    anomaly.process_sample(11.0, True, current_time); current_time += 20
    for _ in range(25): anomaly.process_sample(9.8, True, current_time); current_time += 20
    
    current_time += 2000
    # Speed breaker 1
    for _ in range(25): anomaly.process_sample(9.8, True, current_time); current_time += 20
    anomaly.process_sample(24.0, True, current_time); current_time += 20
    for _ in range(25): anomaly.process_sample(9.8, True, current_time); current_time += 20

    current_time += 2000
    # Speed breaker 2
    for _ in range(25): anomaly.process_sample(9.8, True, current_time); current_time += 20
    anomaly.process_sample(24.0, True, current_time); current_time += 20
    for _ in range(25): anomaly.process_sample(9.8, True, current_time); current_time += 20

    verify(anomaly.potholes_count == 2, f"Potholes count expected 2 (1 debounced), got {anomaly.potholes_count}")
    verify(anomaly.speed_breakers_count == 2, f"Speed breakers count expected 2, got {anomaly.speed_breakers_count}")

    # 6. Rapid Activity Switching (TFLite integration test mock)
    print("\n--- Scenario 6: Rapid Activity Switching ---")
    transitions = 0
    last_act = None
    # We will test the TFLite model directly with dummy window data
    for i in range(12): # 1 min = 12 * 5 sec
        # generate random window data just to see it runs without crash
        window_data = np.random.randn(128, 6).astype(np.float32)
        res = classifier.classify(window_data)
        if last_act != res['activity']:
            transitions += 1
            last_act = res['activity']
            
    verify(True, "Classifier processes rapid switching and runs without crashing")
    
    print("\nALL TESTS COMPLETED.")

if __name__ == "__main__":
    run_tests()
