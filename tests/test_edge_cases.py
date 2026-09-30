import math
import time
from collections import deque

# --- REIMPLEMENTATIONS ---

class ClassificationResult:
    def __init__(self, activity, confidence, all_probabilities):
        self.activity = activity
        self.confidence = confidence
        self.all_probabilities = all_probabilities

class DeepSenseClassifier:
    WINDOW_SIZE = 128
    NUM_CHANNELS = 6
    NUM_CLASSES = 8
    class_names = ["STILL", "WALKING", "RUNNING", "STAIRS_UP", "STAIRS_DOWN", "BUS", "CAR", "METRO"]

    def __init__(self):
        self.interpreter_loaded = True

    def classify(self, window_data):
        if not self.interpreter_loaded or len(window_data) != self.WINDOW_SIZE * self.NUM_CHANNELS:
            return ClassificationResult("UNKNOWN", 0.0, [0.0]*self.NUM_CLASSES)
        
        # Simulate tflite inference processing for nan/inf
        has_nan_or_inf = any(math.isnan(x) or math.isinf(x) for x in window_data)
        if has_nan_or_inf:
            # We assume model returns all zeros for bad input or throws
            return ClassificationResult("UNKNOWN", 0.0, [0.0]*self.NUM_CLASSES)

        probabilities = [0.0] * self.NUM_CLASSES
        probabilities[0] = 1.0 # mock max prob
        return ClassificationResult(self.class_names[0], 1.0, probabilities)

class ErgonomicReport:
    def __init__(self, postureTiltAngle, postureStatus, fidgetIndex, continuousStillMinutes, needsBreakPrompt):
        self.postureTiltAngle = postureTiltAngle
        self.postureStatus = postureStatus
        self.fidgetIndex = fidgetIndex
        self.continuousStillMinutes = continuousStillMinutes
        self.needsBreakPrompt = needsBreakPrompt

class ErgonomicPostureTracker:
    def __init__(self):
        self.gravity = [0.0, 0.0, 0.0]
        self.lastPostureShiftTime = int(time.time() * 1000)
        self.baselineTiltAngle = 0.0
        self.dynamicAccelSamples = deque()

    def processSample(self, ax, ay, az, isCurrentlyStill, current_time_ms=None):
        now = int(time.time() * 1000) if current_time_ms is None else current_time_ms

        alpha = 0.85
        self.gravity[0] = alpha * self.gravity[0] + (1 - alpha) * ax
        self.gravity[1] = alpha * self.gravity[1] + (1 - alpha) * ay
        self.gravity[2] = alpha * self.gravity[2] + (1 - alpha) * az

        tiltAngle = math.atan2(self.gravity[1], self.gravity[2]) * (180.0 / math.pi)

        dynX = ax - self.gravity[0]
        dynY = ay - self.gravity[1]
        dynZ = az - self.gravity[2]
        dynMag = math.sqrt(dynX**2 + dynY**2 + dynZ**2)

        self.dynamicAccelSamples.append(dynMag)
        while len(self.dynamicAccelSamples) > 200:
            self.dynamicAccelSamples.popleft()

        mean = sum(self.dynamicAccelSamples) / len(self.dynamicAccelSamples) if self.dynamicAccelSamples else 0
        variance = sum((sample - mean)**2 for sample in self.dynamicAccelSamples)
        fidgetIndex = variance / len(self.dynamicAccelSamples) if self.dynamicAccelSamples else 0

        if abs(tiltAngle - self.baselineTiltAngle) > 15.0:
            self.baselineTiltAngle = tiltAngle
            self.lastPostureShiftTime = now

        if not isCurrentlyStill:
            self.lastPostureShiftTime = now

        continuousMinutes = int((now - self.lastPostureShiftTime) / 60000)
        needsBreak = continuousMinutes >= 45

        if -25.0 <= tiltAngle <= 25.0:
            status = "Upright / Ergonomic"
        elif tiltAngle < -25.0:
            status = "Forward Slouching"
        else:
            status = "Reclined / Leaning"

        return ErgonomicReport(tiltAngle, status, fidgetIndex, continuousMinutes, needsBreak)

class FallState:
    MONITORING = 0
    IMPACT_SUSPECTED = 1
    FALL_CONFIRMED = 2

class FallDetector:
    def __init__(self):
        self.currentState = FallState.MONITORING
        self.impactTimestamp = 0
        self.postImpactBuffer = deque()
        self.MAX_BUFFER_CAPACITY = 500

    def processSample(self, ax, ay, az, current_time_ms=None):
        totalMagnitudeG = (math.sqrt(ax**2 + ay**2 + az**2) / 9.81)
        now = int(time.time() * 1000) if current_time_ms is None else current_time_ms

        if self.currentState == FallState.MONITORING:
            if totalMagnitudeG > 3.2:
                self.currentState = FallState.IMPACT_SUSPECTED
                self.impactTimestamp = now
                self.postImpactBuffer.clear()
        
        elif self.currentState == FallState.IMPACT_SUSPECTED:
            if len(self.postImpactBuffer) < self.MAX_BUFFER_CAPACITY:
                self.postImpactBuffer.append(totalMagnitudeG)
            elapsed = now - self.impactTimestamp

            if elapsed >= 6000:
                mean = sum(self.postImpactBuffer) / len(self.postImpactBuffer) if self.postImpactBuffer else 0
                variance = sum((v - mean)**2 for v in self.postImpactBuffer)
                motionVariance = variance / len(self.postImpactBuffer) if self.postImpactBuffer else 0

                if motionVariance < 0.2:
                    self.currentState = FallState.FALL_CONFIRMED
                    return True
                else:
                    self.currentState = FallState.MONITORING
                    self.postImpactBuffer.clear()
        
        elif self.currentState == FallState.FALL_CONFIRMED:
            if 0.8 <= totalMagnitudeG <= 1.5 and (now - self.impactTimestamp > 15000):
                self.currentState = FallState.MONITORING

        return False

    def reset(self):
        self.currentState = FallState.MONITORING
        self.postImpactBuffer.clear()

class RoadAnomalyEvent:
    def __init__(self, type, timestamp, peakG):
        self.type = type
        self.timestamp = timestamp
        self.peakG = peakG

class RoadAnomalyDetector:
    def __init__(self):
        self.zAxisWindow = deque()
        self.potholesCount = 0
        self.speedBreakersCount = 0
        self.lastAnomalyTime = 0

    def processSample(self, az, isVehicular, current_time_ms=None):
        if not isVehicular:
            self.zAxisWindow.clear()
            return None

        self.zAxisWindow.append(az)
        while len(self.zAxisWindow) > 25:
            self.zAxisWindow.popleft()

        if len(self.zAxisWindow) < 20:
            return None

        now = int(time.time() * 1000) if current_time_ms is None else current_time_ms

        if now - self.lastAnomalyTime < 1500:
            return None

        maxZ = max(self.zAxisWindow)
        minZ = min(self.zAxisWindow)
        peakToPeak = maxZ - minZ

        if minZ < -11.0 and peakToPeak > 22.0:
            self.lastAnomalyTime = now
            self.potholesCount += 1
            return RoadAnomalyEvent("POTHOLE", now, peakToPeak)

        if 12.0 <= peakToPeak <= 21.0 and minZ > -10.0:
            self.lastAnomalyTime = now
            self.speedBreakersCount += 1
            return RoadAnomalyEvent("SPEED_BREAKER", now, peakToPeak)

        return None

# --- TESTS ---
def run_tests():
    total_passed = 0
    total_tests = 0

    def assert_test(name, condition, error_msg=""):
        nonlocal total_passed, total_tests
        total_tests += 1
        if condition:
            print(f"[PASS] {name}")
            total_passed += 1
        else:
            print(f"[FAIL] {name} - {error_msg}")

    print("--- Starting Edge Case Tests ---")

    # 1. DeepSenseClassifier Tests
    classifier = DeepSenseClassifier()
    # Overflow/Underflow
    res_under = classifier.classify([0.0]*700)
    assert_test("DeepSense - Underflow array", res_under.activity == "UNKNOWN", "Expected UNKNOWN for wrong size")
    res_over = classifier.classify([0.0]*800)
    assert_test("DeepSense - Overflow array", res_over.activity == "UNKNOWN", "Expected UNKNOWN for wrong size")
    
    # Numerical extremes
    res_all_zeros = classifier.classify([0.0]*768)
    assert_test("DeepSense - All zeros", res_all_zeros.activity != "UNKNOWN", "Zeros should be processed")
    
    res_extreme_large = classifier.classify([1e38]*768)
    assert_test("DeepSense - Extreme large (1e38)", res_extreme_large.activity != "UNKNOWN")

    res_extreme_small = classifier.classify([1e-38]*768)
    assert_test("DeepSense - Extreme small (1e-38)", res_extreme_small.activity != "UNKNOWN")

    res_nans = classifier.classify([float('nan')]*768)
    assert_test("DeepSense - NaNs", res_nans.activity == "UNKNOWN")
    
    # Memory/Rapid bounds check
    for _ in range(10000):
        classifier.classify([0.0]*768)
    assert_test("DeepSense - 10000 consecutive calls", True, "Survived rapid calls")

    # 2. ErgonomicPostureTracker Tests
    tracker = ErgonomicPostureTracker()
    # Zeros
    rep = tracker.processSample(0.0, 0.0, 0.0, True, 1000)
    assert_test("Ergonomic - All zeros", rep.postureTiltAngle == 0.0)
    
    # Extreme gravity
    rep = tracker.processSample(0.0, 1e38, 1e38, True, 2000)
    assert_test("Ergonomic - Extreme large gravity", not math.isnan(rep.postureTiltAngle))

    rep = tracker.processSample(0.0, -9.8, -9.8, True, 3000)
    assert_test("Ergonomic - Negative gravity", not math.isnan(rep.postureTiltAngle))

    # Timing: 24h+ sedentary timer
    start_time = 1000
    future_time = start_time + 25 * 3600 * 1000 # 25 hours later
    tracker.lastPostureShiftTime = start_time
    rep = tracker.processSample(0.0, 9.8, 9.8, True, future_time)
    assert_test("Ergonomic - 24h+ sedentary timer", rep.continuousStillMinutes >= 1500 and rep.needsBreakPrompt)

    # Memory check
    for i in range(10000):
        tracker.processSample(0.1, 9.8, 0.1, True, future_time + i*100)
    assert_test("Ergonomic - Memory bounds (ArrayDeque <= 200)", len(tracker.dynamicAccelSamples) <= 200)

    # 3. FallDetector Tests
    fall_det = FallDetector()
    # Zeros (free fall)
    res = fall_det.processSample(0.0, 0.0, 0.0, 1000)
    assert_test("FallDetector - All zeros (perfect free-fall)", not res and fall_det.currentState == FallState.MONITORING)

    # Extreme impact
    fall_det.processSample(1e5, 1e5, 1e5, 2000)
    assert_test("FallDetector - Extreme large impact", fall_det.currentState == FallState.IMPACT_SUSPECTED)

    # Stuck in IMPACT_SUSPECTED forever?
    # Simulate time passing without confirming fall
    fall_det.processSample(9.8, 0, 0, 9000) # 7 seconds later, moving
    assert_test("FallDetector - Not stuck in IMPACT_SUSPECTED", fall_det.currentState == FallState.MONITORING)

    # Rapid state transitions (MONITORING -> SUSPECTED -> MONITORING -> SUSPECTED)
    fall_det.processSample(40.0, 0, 0, 10000) # Suspected
    fall_det.processSample(40.0, 0, 0, 17000) # Reset to monitoring (high motion variance)
    fall_det.processSample(40.0, 0, 0, 18000) # Suspected again
    assert_test("FallDetector - Rapid state transitions", fall_det.currentState == FallState.IMPACT_SUSPECTED)
    
    # processSample after reset
    fall_det.reset()
    res = fall_det.processSample(9.8, 0, 0, 20000)
    assert_test("FallDetector - processSample after reset", fall_det.currentState == FallState.MONITORING)

    # Memory Check
    fall_det.reset()
    fall_det.processSample(40.0, 0, 0, 30000) # Suspected
    for i in range(10000):
        fall_det.processSample(0, 0, 0, 30000 + i*10) # 100 seconds, high sampling
    assert_test("FallDetector - Memory bounds (postImpactBuffer <= 500)", len(fall_det.postImpactBuffer) <= 500)


    # 4. RoadAnomalyDetector Tests
    road_det = RoadAnomalyDetector()
    # Rapid switching isVehicular
    for i in range(100):
        road_det.processSample(9.8, i%2==0, i*100)
    assert_test("RoadAnomaly - Rapid switching isVehicular", len(road_det.zAxisWindow) <= 1)
    
    # 10000 calls check memory
    for i in range(10000):
        road_det.processSample(9.8, True, 10000 + i*10)
    assert_test("RoadAnomaly - Memory bounds (zAxisWindow <= 25)", len(road_det.zAxisWindow) <= 25)

    # Negative large
    road_det.processSample(-100.0, True, 200000)
    road_det.processSample(100.0, True, 200010)
    res = road_det.processSample(-100.0, True, 200020)
    assert_test("RoadAnomaly - Extreme numbers (large negative minZ)", road_det.potholesCount > 0 or road_det.speedBreakersCount > 0)

    print(f"--- Tests Complete: {total_passed}/{total_tests} Passed ---")

if __name__ == '__main__':
    run_tests()
