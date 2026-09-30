"""
Exports short continuous MotionSense recordings (converted to Android sensor
units by har_datasets.py) as CSV fixtures for the Kotlin JVM unit tests, plus
an independent cadence reference for each recording.

    python export_test_fixtures.py --motionsense <dir> --out <app/src/test/resources/fixtures>

MotionSense: Malekzadeh et al., "Mobile Sensor Data Anonymization", IoTDI 2019,
https://github.com/mmalekzadeh/motion-sense (phone in the front trouser pocket).

Cadence reference: 2 x the stride frequency measured from the gyroscope's
principal rotation axis (see reference_step_rate). It is independent of the
accelerometer-only step detector it is used to validate.
"""
import argparse
import glob
import json
import os

import numpy as np

from har_datasets import STANDARD_GRAVITY

FIXTURES = {
    # name: (trial dir, subject, seconds, start offset seconds)
    "walking_pocket": ("wlk_7", 1, 30, 5),
    "jogging_pocket": ("jog_9", 1, 20, 3),
    "sitting_pocket": ("sit_5", 1, 30, 5),
    "upstairs_pocket": ("ups_3", 1, 15, 2),
    "standing_pocket": ("std_6", 1, 20, 5),
}


def load(root, trial, subject):
    f = glob.glob(os.path.join(root, "A_DeviceMotion_data", trial, f"sub_{subject}.csv"))[0]
    d = np.genfromtxt(f, delimiter=",", skip_header=1)
    acc = -STANDARD_GRAVITY * (d[:, 4:7] + d[:, 10:13])
    return np.concatenate([acc, d[:, 7:10]], axis=1)


def reference_step_rate(x, fs=50.0):
    """
    Independent cadence reference from the GYROSCOPE (the step detector under
    test uses only the accelerometer). In a thigh pocket the leg swing rotates
    the phone about the hip axis exactly once per stride, so the dominant
    frequency of the principal rotation axis is the stride frequency, and the
    step rate is twice that.
    """
    g = x[:, 3:6] - x[:, 3:6].mean(0)
    if np.linalg.norm(g, axis=1).std() < 0.3:  # no gait
        return 0.0
    _, _, vt = np.linalg.svd(g, full_matrices=False)
    w = g @ vt[0] * np.hanning(len(g))
    n = 1 << 14
    spec = np.abs(np.fft.rfft(w, n))
    f = np.fft.rfftfreq(n, 1 / fs)
    band = (f >= 0.4) & (f <= 2.0)  # physiological stride range
    return 2.0 * f[band][np.argmax(spec[band])]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--motionsense", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    meta = {}
    for name, (trial, sub, secs, off) in FIXTURES.items():
        x = load(a.motionsense, trial, sub)[off * 50:(off + secs) * 50]
        t = np.arange(len(x)) * 20_000_000
        with open(os.path.join(a.out, f"{name}.csv"), "w") as fh:
            fh.write("t_ns,ax,ay,az,gx,gy,gz\n")
            for ti, row in zip(t, x):
                fh.write(f"{ti}," + ",".join(f"{v:.5f}" for v in row) + "\n")
        meta[name] = {"source": f"MotionSense {trial}/sub_{sub}.csv", "seconds": secs,
                      "reference_steps_per_second": round(reference_step_rate(x), 3)}
        print(name, meta[name])
    with open(os.path.join(a.out, "fixtures.json"), "w") as fh:
        json.dump(meta, fh, indent=2)


if __name__ == "__main__":
    main()
