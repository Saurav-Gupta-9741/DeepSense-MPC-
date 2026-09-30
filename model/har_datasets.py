"""
Real-world HAR dataset loaders for PervasiveSense.

Both datasets are converted into the exact tensor contract that the Android app
feeds the model:  float32 [N, 128, 6] = (ax, ay, az, gx, gy, gz) at 50 Hz, with
    - acceleration in m/s^2, Android TYPE_ACCELEROMETER convention
      (specific force: a phone lying face-up reads az = +9.81)
    - angular velocity in rad/s, Android TYPE_GYROSCOPE convention
      (right-handed, counter-clockwise positive)

Datasets
--------
MotionSense (Malekzadeh et al., 2019) - iPhone 6s in the front trouser pocket,
    24 subjects, 50 Hz. https://github.com/mmalekzadeh/motion-sense
    iOS CMDeviceMotion reports gravity/userAcceleration in g with the OPPOSITE
    sign to Android (face-up iPhone -> gravity.z = -1), so
        a_android = -STANDARD_GRAVITY * (gravity + userAcceleration)
    rotationRate uses the same right-handed device frame as Android.

UCI-HAR (Anguita et al., 2013) - Samsung Galaxy S II on the waist, 30 subjects,
    50 Hz, pre-windowed at 128 samples / 50 % overlap. total_acc is in g with
    Android convention already; body_gyro is rad/s.

The sign conventions are verified empirically by verify_frame_chirality().
"""
from __future__ import annotations

import glob
import os
import re

import numpy as np

STANDARD_GRAVITY = 9.80665
WINDOW = 128
CLASS_NAMES = ["STILL", "WALKING", "RUNNING", "STAIRS_UP", "STAIRS_DOWN"]

_MS_CODES = {"sit": 0, "std": 0, "wlk": 1, "jog": 2, "ups": 3, "dws": 4}
# UCI: 1 WALKING, 2 UPSTAIRS, 3 DOWNSTAIRS, 4 SITTING, 5 STANDING, 6 LAYING
_UCI_MAP = {1: 1, 2: 3, 3: 4, 4: 0, 5: 0, 6: 0}


def load_motionsense_streams(root: str):
    """Yield (subject, label, stream[T,6]) continuous recordings in Android units."""
    for trial_dir in sorted(glob.glob(os.path.join(root, "A_DeviceMotion_data", "*_*"))):
        code = os.path.basename(trial_dir).split("_")[0]
        if code not in _MS_CODES:
            continue
        for csv in sorted(glob.glob(os.path.join(trial_dir, "sub_*.csv"))):
            subject = int(re.search(r"sub_(\d+)", csv).group(1))
            d = np.genfromtxt(csv, delimiter=",", skip_header=1, dtype=np.float64)
            # columns: idx, attitude(roll,pitch,yaw), gravity xyz, rotationRate xyz, userAcceleration xyz
            grav, rot, user = d[:, 4:7], d[:, 7:10], d[:, 10:13]
            acc = -STANDARD_GRAVITY * (grav + user)
            yield subject, _MS_CODES[code], np.concatenate([acc, rot], axis=1).astype(np.float32)


def window_stream(stream: np.ndarray, stride: int):
    n = (len(stream) - WINDOW) // stride + 1
    if n <= 0:
        return np.empty((0, WINDOW, 6), np.float32)
    idx = np.arange(WINDOW)[None, :] + stride * np.arange(n)[:, None]
    return stream[idx]


def load_motionsense(root: str, stride: int = 32):
    xs, ys, ss = [], [], []
    for subject, label, stream in load_motionsense_streams(root):
        w = window_stream(stream, stride)
        xs.append(w)
        ys.append(np.full(len(w), label))
        ss.append(np.full(len(w), subject))
    return np.concatenate(xs), np.concatenate(ys), np.concatenate(ss)


def load_uci(root: str):
    xs, ys, ss = [], [], []
    for split in ("train", "test"):
        sig = os.path.join(root, split, "Inertial Signals")
        chans = []
        for name in ("total_acc_x", "total_acc_y", "total_acc_z"):
            chans.append(np.loadtxt(os.path.join(sig, f"{name}_{split}.txt")) * STANDARD_GRAVITY)
        for name in ("body_gyro_x", "body_gyro_y", "body_gyro_z"):
            chans.append(np.loadtxt(os.path.join(sig, f"{name}_{split}.txt")))
        xs.append(np.stack(chans, axis=-1).astype(np.float32))
        y = np.loadtxt(os.path.join(root, split, f"y_{split}.txt")).astype(int)
        ys.append(np.array([_UCI_MAP[v] for v in y]))
        # Offset UCI subject ids so they never collide with MotionSense ids.
        ss.append(np.loadtxt(os.path.join(root, split, f"subject_{split}.txt")).astype(int) + 100)
    return np.concatenate(xs), np.concatenate(ys), np.concatenate(ss)


def _lowpass(x: np.ndarray, alpha: float) -> np.ndarray:
    y = np.empty_like(x)
    y[0] = x[0]
    for i in range(1, len(x)):
        y[i] = alpha * y[i - 1] + (1 - alpha) * x[i]
    return y


def verify_frame_chirality(windows: np.ndarray, dt: float = 0.02) -> float:
    """
    Rigid-body kinematics: a world-fixed vector g expressed in the device frame
    evolves as dg/dt = -omega x g. Fit s in  dg/dt ~= s * (-omega x g)  using the
    low-passed accelerometer as g. Returns s: ~+1 means accelerometer and gyro
    share a consistent right-handed Android frame; ~-1 means a sign error.
    """
    num, den = 0.0, 0.0
    for w in windows:
        g = _lowpass(w[:, :3].astype(np.float64), 0.9)
        dg = np.gradient(g, dt, axis=0)
        pred = -np.cross(w[:, 3:6].astype(np.float64), g)
        num += float(np.sum(dg * pred))
        den += float(np.sum(pred * pred))
    return num / den
