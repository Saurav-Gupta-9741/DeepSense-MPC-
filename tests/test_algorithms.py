import math
import collections

# Global mockable time
_current_time_ms = 0

def current_time_millis():
    return _current_time_ms

def set_time_millis(ms):
    global _current_time_ms
    _current_time_ms = ms

def advance_time(ms):
    global _current_time_ms
    _current_time_ms += ms


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
        self.lastPostureShiftTime = current_time_millis()
        self.baselineTiltAngle = 0.0
        self.dynamicAccelSamples = collections.deque(maxlen=200)

    def processSample(self, ax, ay, az, isCurrentlyStill):
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

        if len(self.dynamicAccelSamples) > 0:
            mean = sum(self.dynamicAccelSamples) / len(self.dynamicAccelSamples)
            variance = sum((s - mean)**2 for s in self.dynamicAccelSamples) / len(self.dynamicAccelSamples)
        else:
            variance = 0.0
            
        fidgetIndex = variance

        now = current_time_millis()
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

        return ErgonomicReport(
            postureTiltAngle=tiltAngle,
            postureStatus=status,
            fidgetIndex=fidgetIndex,
            continuousStillMinutes=continuousMinutes,
            needsBreakPrompt=needsBreak
        )


class FallState:
    MONITORING = "MONITORING"
    IMPACT_SUSPECTED = "IMPACT_SUSPECTED"
    FALL_CONFIRMED = "FALL_CONFIRMED"


class FallDetector:
    def __init__(self):
        self.currentState = FallState.MONITORING
        self.impactTimestamp = 0
        self.postImpactBuffer = collections.deque(maxlen=500)

    def processSample(self, ax, ay, az):
        totalMagnitudeG = math.sqrt(ax**2 + ay**2 + az**2) / 9.81
        now = current_time_millis()

        if self.currentState == FallState.MONITORING:
            if totalMagnitudeG > 3.2:
                self.currentState = FallState.IMPACT_SUSPECTED
                self.impactTimestamp = now
                self.postImpactBuffer.clear()

        elif self.currentState == FallState.IMPACT_SUSPECTED:
            if len(self.postImpactBuffer) < 500:
                self.postImpactBuffer.append(totalMagnitudeG)
            
            elapsed = now - self.impactTimestamp
            if elapsed >= 6000:
                if len(self.postImpactBuffer) > 0:
                    mean = sum(self.postImpactBuffer) / len(self.postImpactBuffer)
                    variance = sum((x - mean)**2 for x in self.postImpactBuffer) / len(self.postImpactBuffer)
                else:
                    variance = 0.0
                
                if variance < 0.2:
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
    def __init__(self, anomaly_type, timestamp, peakG):
        self.type = anomaly_type
        self.timestamp = timestamp
        self.peakG = peakG


class RoadAnomalyDetector:
    def __init__(self):
        self.zAxisWindow = collections.deque(maxlen=25)
        self.potholesCount = 0
        self.speedBreakersCount = 0
        self.lastAnomalyTime = 0

    def processSample(self, az, isVehicular):
        if not isVehicular:
            self.zAxisWindow.clear()
            return None

        self.zAxisWindow.append(az)

        if len(self.zAxisWindow) < 20:
            return None

        now = current_time_millis()
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


class SensingForegroundService:
    def __init__(self):
        self.windowSize = 128
        self.channels = 6
        self.buffer = [0.0] * (self.windowSize * self.channels)
        self.sampleCount = 0
        
        self.latestAx = 0.0
        self.latestAy = 9.8
        self.latestAz = 0.0
        self.latestGx = 0.0
        self.latestGy = 0.0
        self.latestGz = 0.0

    def onAccel(self, ax, ay, az):
        self.latestAx = ax
        self.latestAy = ay
        self.latestAz = az
        
        if self.sampleCount < self.windowSize:
            baseIndex = self.sampleCount * self.channels
            self.buffer[baseIndex + 0] = self.latestAx
            self.buffer[baseIndex + 1] = self.latestAy
            self.buffer[baseIndex + 2] = self.latestAz
            self.buffer[baseIndex + 3] = self.latestGx
            self.buffer[baseIndex + 4] = self.latestGy
            self.buffer[baseIndex + 5] = self.latestGz
            self.sampleCount += 1

    def onGyro(self, gx, gy, gz):
        self.latestGx = gx
        self.latestGy = gy
        self.latestGz = gz


# ================= TESTING =================

def run_tests():
    total = 0
    passed = 0

    def assert_test(name, condition):
        nonlocal total, passed
        total += 1
        if condition:
            passed += 1
            print(f"PASS: {name}")
        else:
            print(f"FAIL: {name}")

    # 1. ErgonomicPostureTracker Tests
    print("\n--- ErgonomicPostureTracker Tests ---")
    tracker = ErgonomicPostureTracker()
    set_time_millis(1000)
    
    # Gravity convergence
    for _ in range(100):
        tracker.processSample(0, 9.8, 0, True)
    
    # After 100 samples, gravity should converge close to [0, 9.8, 0]
    assert_test("Gravity low-pass filter convergence", abs(tracker.gravity[1] - 9.8) < 0.1 and abs(tracker.gravity[0]) < 0.1)

    # Flat on table (Z = 9.8)
    tracker = ErgonomicPostureTracker()
    for _ in range(100):
        rep = tracker.processSample(0, 0, 9.8, True)
    assert_test("Tilt angle flat on table", abs(rep.postureTiltAngle - 0.0) < 1.0)

    # Tilted 45 degrees
    tracker = ErgonomicPostureTracker()
    for _ in range(100):
        rep = tracker.processSample(0, 9.8 * math.sin(math.pi/4), 9.8 * math.cos(math.pi/4), True)
    assert_test("Tilt angle 45 deg", abs(rep.postureTiltAngle - 45.0) < 1.0)
    
    # Upside down
    tracker = ErgonomicPostureTracker()
    for _ in range(100):
        rep = tracker.processSample(0, 0, -9.8, True)
    assert_test("Tilt angle upside down", abs(abs(rep.postureTiltAngle) - 180.0) < 1.0)
    
    # Fidget index still
    tracker = ErgonomicPostureTracker()
    rep = None
    for _ in range(500):
        rep = tracker.processSample(0, 9.8, 0, True)
    assert_test("Fidget index still data (~0)", rep.fidgetIndex < 0.01)

    # Fidget index shaking
    tracker = ErgonomicPostureTracker()
    rep = None
    for i in range(500):
        # vary dynMag
        v = (i % 3) * 5
        rep = tracker.processSample(0, 9.8 + v, 0, True)
    assert_test("Fidget index high for shaking", rep.fidgetIndex > 2.0)

    # Sedentary timer
    tracker = ErgonomicPostureTracker()
    set_time_millis(0)
    tracker.lastPostureShiftTime = 0
    tracker.processSample(0, 9.8, 0, False) # user moves, resets to 0
    assert_test("Sedentary timer reset on move", tracker.lastPostureShiftTime == 0)
    
    set_time_millis(45 * 60000)
    rep = tracker.processSample(0, 9.8, 0, True) # still for 45 min
    assert_test("Sedentary timer reaches 45 min", rep.needsBreakPrompt and rep.continuousStillMinutes == 45)

    # Posture shift
    set_time_millis(0)
    tracker = ErgonomicPostureTracker()
    for _ in range(100): tracker.processSample(0, 0, 9.8, True) # 0 tilt
    tracker.lastPostureShiftTime = 0
    set_time_millis(1000)
    for _ in range(100): tracker.processSample(0, 9.8, 0, True) # 90 tilt
    assert_test("Posture shift detection (15 deg threshold)", tracker.lastPostureShiftTime == 1000)
    
    
    # 2. FallDetector Tests
    print("\n--- FallDetector Tests ---")
    fd = FallDetector()
    set_time_millis(0)
    
    # Real fall: high-G > 3.2g followed by 6 sec immobility (1g)
    is_fall = fd.processSample(0, 40, 0) # ~4g
    assert_test("Real fall state IMPACT_SUSPECTED", fd.currentState == FallState.IMPACT_SUSPECTED)
    
    for i in range(1, 6000, 20):
        set_time_millis(i)
        is_fall = fd.processSample(0, 9.81, 0)
    set_time_millis(6000)
    is_fall = fd.processSample(0, 9.81, 0)
    assert_test("Real fall CONFIRMED", is_fall and fd.currentState == FallState.FALL_CONFIRMED)
    
    # Fall recovery
    set_time_millis(22000) # > 15s elapsed
    fd.processSample(0, 9.81, 0) # 1g
    assert_test("Real fall state recovery", fd.currentState == FallState.MONITORING)
    
    # Phone drop: high-G followed by movement
    fd.reset()
    set_time_millis(0)
    fd.processSample(0, 40, 0) # impact
    for i in range(1, 6000, 20):
        set_time_millis(i)
        fd.processSample(0, 9.81 + 10 * math.sin(i), 0) # large variance
    set_time_millis(6000)
    fd.processSample(0, 9.81, 0)
    assert_test("Phone drop rejected (false alarm)", fd.currentState == FallState.MONITORING)
    
    # Sitting down hard: <3.2g
    fd.reset()
    set_time_millis(0)
    fd.processSample(0, 25, 0) # ~2.5g
    assert_test("Sitting down hard rejected (<3.2g)", fd.currentState == FallState.MONITORING)
    
    # Running: periodic high-G
    fd.reset()
    set_time_millis(0)
    triggered = False
    for i in range(0, 10000, 50):
        set_time_millis(i)
        if fd.processSample(0, 35 if i%500==0 else 9.81, 0): triggered = True
    assert_test("Running repeated high-G rejected", not triggered)


    # 3. RoadAnomalyDetector Tests
    print("\n--- RoadAnomalyDetector Tests ---")
    rd = RoadAnomalyDetector()
    set_time_millis(2000)
    
    # Fill window with 0s first
    for _ in range(25): rd.processSample(0.0, True)

    # Pothole: minZ < -11, peakToPeak > 22
    ev = None
    for i in range(25):
        val = -12.0 if i == 10 else (12.0 if i == 11 else 0.0)
        res = rd.processSample(val, True)
        if res: ev = res
    assert_test("Pothole detection", ev is not None and ev.type == "POTHOLE")
    
    # Speed Breaker: peakToPeak 12..21, minZ > -10
    rd = RoadAnomalyDetector()
    set_time_millis(2000)
    for _ in range(25): rd.processSample(0.0, True)
    ev = None
    for i in range(25):
        val = -5.0 if i == 10 else (10.0 if i == 11 else 0.0)
        res = rd.processSample(val, True)
        if res: ev = res
    assert_test("Speed breaker detection", ev is not None and ev.type == "SPEED_BREAKER")
    
    # Normal driving: smooth vibration
    rd = RoadAnomalyDetector()
    set_time_millis(4000)
    for _ in range(25): rd.processSample(0.0, True)
    triggered = False
    for i in range(50):
        if rd.processSample(2.0 * math.sin(i), True):
            triggered = True
    assert_test("Normal driving rejected", not triggered)
    
    # Walking mode: gated off
    rd = RoadAnomalyDetector()
    set_time_millis(6000)
    for _ in range(25): rd.processSample(0.0, True)
    ev = None
    for i in range(25):
        val = -12.0 if i == 10 else (12.0 if i == 11 else 0.0)
        res = rd.processSample(val, False)
        if res: ev = res
    assert_test("Walking mode gated off", ev is None)
    
    # Debounce: two potholes within 1.5s
    rd = RoadAnomalyDetector()
    set_time_millis(8000)
    for _ in range(25): rd.processSample(0.0, True)
    for i in range(25):
        val = -12.0 if i == 10 else (12.0 if i == 11 else 0.0)
        rd.processSample(val, True)
    
    set_time_millis(9000) # +1s
    ev2 = None
    for i in range(25):
        val = -12.0 if i == 10 else (12.0 if i == 11 else 0.0)
        r = rd.processSample(val, True)
        if r: ev2 = r
    assert_test("Debounce 1.5s window", ev2 is None and rd.potholesCount == 1)

    
    # 4. Sensor Synchronization
    print("\n--- Sensor Synchronization Tests ---")
    srv = SensingForegroundService()
    
    # Interleave
    srv.onGyro(1.0, 2.0, 3.0)
    srv.onAccel(4.0, 5.0, 6.0)
    
    assert_test("Buffer baseIndex math & values", 
        srv.buffer[0:6] == [4.0, 5.0, 6.0, 1.0, 2.0, 3.0] and srv.sampleCount == 1)
        
    for _ in range(127):
        srv.onAccel(4.0, 5.0, 6.0)
        
    assert_test("Buffer fills up to windowSize", srv.sampleCount == 128)

    print(f"\nTotal: {total}, Passed: {passed}")

if __name__ == "__main__":
    run_tests()
