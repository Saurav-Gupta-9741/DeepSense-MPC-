"""
Validation of the on-device DeepSense model exactly as the Android app uses it.

    pip install "tensorflow==2.16.1" "keras==3.3.3" pytest
    MOTIONSENSE_DIR=<dir> UCI_DIR=<dir> pytest -v tests/test_tflite_model.py

Tests needing the datasets are skipped (with a reason) if the env vars are unset.

What this suite guarantees (each item was a real failure of the previous model):
  * the model loads in the SAME TFLite runtime version the APK bundles;
  * the asset in the APK is byte-identical to the validated model and the label
    file matches the output tensor;
  * a phone lying still in ANY orientation is STILL (the old model said METRO);
  * accuracy is measured on people never seen in training, in random orientations;
  * the full streaming path (0.5 s stride + smoothing, mirrored from
    ActivityStreaming.kt) reacts to real activity changes within seconds.
"""
import hashlib
import os
import re
import sys
import time

import numpy as np
import pytest
import tensorflow as tf

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "model"))
from har_datasets import (CLASS_NAMES, load_motionsense, load_motionsense_streams,  # noqa: E402
                          load_uci)
from train_export_deepsense import (MS_TEST, UCI_TEST, random_rotations,  # noqa: E402
                                    rotate)

MODEL = os.path.join(ROOT, "model", "deepsense_int8.tflite")
ASSET = os.path.join(ROOT, "PervasiveSense", "app", "src", "main", "assets", "deepsense_int8.tflite")
LABELS = os.path.join(ROOT, "PervasiveSense", "app", "src", "main", "assets", "deepsense_labels.txt")
GRADLE = os.path.join(ROOT, "PervasiveSense", "app", "build.gradle.kts")
MS_DIR, UCI_DIR = os.environ.get("MOTIONSENSE_DIR"), os.environ.get("UCI_DIR")
needs_ms = pytest.mark.skipif(not MS_DIR, reason="set MOTIONSENSE_DIR to run real-data tests")


def app_tflite_version():
    return re.search(r'org\.tensorflow:tensorflow-lite:([0-9.]+)"', open(GRADLE).read()).group(1)


class Model:
    def __init__(self, path=MODEL):
        self.it = tf.lite.Interpreter(model_path=path)
        self.it.allocate_tensors()
        self.inp = self.it.get_input_details()[0]
        self.out = self.it.get_output_details()[0]

    def __call__(self, windows):
        windows = np.asarray(windows, np.float32).reshape(-1, 128, 6)
        res = np.empty((len(windows), self.out["shape"][-1]), np.float32)
        for k, w in enumerate(windows):
            self.it.set_tensor(self.inp["index"], w[None])
            self.it.invoke()
            res[k] = self.it.get_tensor(self.out["index"])[0]
        return res


@pytest.fixture(scope="module")
def model():
    return Model()


# ------------------------------------------------------------ deployment contract
def test_runtime_matches_the_version_bundled_in_the_apk():
    app = app_tflite_version()
    assert tf.__version__.split(".")[:2] == app.split(".")[:2], (
        f"run this suite with TF {app} (found {tf.__version__}); the model must load in the "
        "runtime the APK ships")


def test_model_loads_in_app_runtime(model):
    # The previous model failed right here: "FULLY_CONNECTED version 12" needs runtime >= 2.17.
    assert model.inp["dtype"] == np.float32


def test_min_runtime_version_metadata_is_compatible():
    from tensorflow.lite.python import schema_py_generated as s
    m = s.Model.GetRootAsModel(open(MODEL, "rb").read(), 0)
    app = tuple(int(v) for v in app_tflite_version().split("."))
    found = False
    for i in range(m.MetadataLength()):
        if m.Metadata(i).Name() == b"min_runtime_version":
            raw = m.Buffers(m.Metadata(i).Buffer()).DataAsNumpy().tobytes().rstrip(b"\x00").decode()
            need = tuple(int(v) for v in raw.split("."))
            assert need <= app, f"model needs TFLite {raw}, app bundles {app}"
            found = True
    assert found


def test_apk_asset_is_the_validated_model():
    digest = lambda p: hashlib.sha256(open(p, "rb").read()).hexdigest()
    assert digest(MODEL) == digest(ASSET)


def test_io_shapes_and_labels_file(model):
    labels = [line.strip() for line in open(LABELS) if line.strip()]
    assert list(model.inp["shape"]) == [1, 128, 6]
    assert list(model.out["shape"]) == [1, len(labels)]
    assert labels == CLASS_NAMES


def test_row_major_packing_matches_kotlin_contract(model):
    # Kotlin packs window[t * 6 + c]; numpy C-order reshape of [128, 6] is the same layout.
    w = np.random.default_rng(0).normal(0, 3, (128, 6)).astype(np.float32)
    flat = np.array([w[t, c] for t in range(128) for c in range(6)], np.float32)
    np.testing.assert_allclose(model(flat.reshape(128, 6)), model(w), atol=1e-6)


# ------------------------------------------------------------ numerical robustness
def test_softmax_valid_deterministic_and_finite_on_extreme_input(model):
    rng = np.random.default_rng(1)
    cases = [np.zeros((128, 6)), np.full((128, 6), 78.0), rng.normal(0, 40, (128, 6)),
             np.tile([0, 0, 9.81, 0, 0, 0], (128, 1))]
    for w in cases:
        p1, p2 = model(w)[0], model(w)[0]
        assert np.all(np.isfinite(p1))
        assert abs(p1.sum() - 1) < 1e-4
        np.testing.assert_array_equal(p1, p2)


def test_inference_is_fast(model):
    w = np.random.default_rng(2).normal(0, 1, (1, 128, 6)).astype(np.float32)
    model(w)
    t0 = time.perf_counter()
    for _ in range(200):
        model(w)
    ms = (time.perf_counter() - t0) / 200 * 1000
    print(f"desktop CPU inference: {ms:.3f} ms")
    assert ms < 15  # the app runs one inference per 0.5 s


# ------------------------------------------------------------ physics sanity
@pytest.mark.parametrize("gravity", [
    (0, 0, 9.81), (0, 0, -9.81), (0, 9.81, 0), (0, -9.81, 0), (9.81, 0, 0), (-9.81, 0, 0),
    (0.06, 5.04, 8.23),  # the exact reading in the bug report screenshot (old model: METRO 100 %)
])
def test_phone_resting_in_any_orientation_is_still(model, gravity):
    rng = np.random.default_rng(3)
    w = np.zeros((128, 6))
    w[:, :3] = np.array(gravity) / np.linalg.norm(gravity) * 9.81
    w[:, :3] += rng.normal(0, 0.03, (128, 3))
    w[:, 3:] += rng.normal(0, 0.005, (128, 3))
    p = model(w)[0]
    assert CLASS_NAMES[p.argmax()] == "STILL" and p.max() > 0.9, dict(zip(CLASS_NAMES, p.round(3)))


def test_random_resting_orientations_are_still(model):
    rng = np.random.default_rng(4)
    w = np.zeros((50, 128, 6))
    w[:, :, 2] = 9.81
    w = rotate(w, random_rotations(50, rng))
    w = w + np.concatenate([rng.normal(0, 0.03, (50, 128, 3)), rng.normal(0, 0.005, (50, 128, 3))], -1)
    assert np.mean(model(w).argmax(1) == 0) == 1.0


# ------------------------------------------------------------ real held-out subjects
@pytest.fixture(scope="module")
def heldout():
    xs, ys = [], []
    if MS_DIR:
        x, y, s = load_motionsense(MS_DIR, stride=64)
        keep = np.isin(s, list(MS_TEST))
        xs.append(x[keep]); ys.append(y[keep])
    if UCI_DIR:
        x, y, s = load_uci(UCI_DIR)
        keep = np.isin(s, list(UCI_TEST))
        xs.append(x[keep]); ys.append(y[keep])
    if not xs:
        pytest.skip("set MOTIONSENSE_DIR and/or UCI_DIR")
    return np.concatenate(xs), np.concatenate(ys)


def test_accuracy_on_unseen_subjects_in_random_orientations(model, heldout):
    x, y = heldout
    xr = rotate(x, random_rotations(len(x), np.random.default_rng(5))).astype(np.float32)
    pred = model(xr).argmax(1)
    acc = (pred == y).mean()
    f1 = []
    for c in range(len(CLASS_NAMES)):
        if (y == c).any():
            tp = ((pred == c) & (y == c)).sum()
            f1.append(2 * tp / ((pred == c).sum() + (y == c).sum()))
    print(f"held-out accuracy {acc*100:.2f}% | macro-F1 {np.mean(f1):.3f} | per-class F1 "
          f"{dict(zip(CLASS_NAMES, np.round(f1, 3)))} | n={len(y)}")
    assert acc >= 0.95
    assert min(f1) >= 0.85


def test_predictions_are_orientation_invariant(model, heldout):
    x, _ = heldout
    x = x[:: max(1, len(x) // 600)]
    base = model(x).argmax(1)
    agree = [np.mean(model(rotate(x, random_rotations(len(x), np.random.default_rng(k)))).argmax(1) == base)
             for k in range(5)]
    print("agreement under random re-orientation:", np.round(agree, 3))
    assert min(agree) >= 0.93


# ------------------------------------------------------------ streaming behaviour
def stream_labels(model, stream, stride=25, alpha=0.5, enter=0.5, confirm=2):
    """Mirror of SlidingWindow + ActivitySmoother (ActivityStreaming.kt) at 50 Hz."""
    smoothed, stable, pending, hits, out = None, -1, -1, 0, []
    for end in range(128, len(stream) + 1, stride):
        p = model(stream[end - 128:end])[0]
        smoothed = p if smoothed is None else alpha * p + (1 - alpha) * smoothed
        best = int(smoothed.argmax())
        if best == stable or smoothed[best] < enter:
            pending, hits = -1, 0
        elif stable < 0:
            stable = best
        else:
            hits = hits + 1 if best == pending else 1
            pending = best
            if hits >= confirm:
                stable, pending, hits = best, -1, 0
        out.append((end / 50.0, stable))
    return out


@needs_ms
@pytest.mark.parametrize("subject", sorted(MS_TEST))
def test_real_continuous_session_reacts_within_seconds(model, subject):
    """Held-out subject: sit -> walk -> sit -> jog, stitched from their own recordings."""
    parts = {}
    for sub, label, x in load_motionsense_streams(MS_DIR):
        if sub == subject and label not in parts and len(x) >= 1000:
            parts[label] = x[:1000]  # 20 s each
    order = [0, 1, 0, 2]
    stream = np.concatenate([parts[k] for k in order]).astype(np.float32)
    stream = rotate(stream[None], random_rotations(1, np.random.default_rng(subject)))[0].astype(np.float32)
    labels = stream_labels(model, stream)
    latencies = []
    for seg, cls in enumerate(order[1:], start=1):
        change = seg * 20.0
        first = next((t for t, s in labels if t >= change and s == cls), None)
        assert first is not None, f"never switched to {CLASS_NAMES[cls]}"
        latencies.append(first - change)
    print(f"subject {subject} switch latencies (s):", [round(v, 2) for v in latencies])
    assert max(latencies) <= 4.0
    # Once settled (4 s into each segment) the stable label is correct >= 95 % of the time.
    settled = [(t, s) for t, s in labels if (t % 20.0) >= 4.0]
    correct = [s == order[min(int(t // 20.0), 3)] for t, s in settled]
    assert np.mean(correct) >= 0.95, f"settled accuracy {np.mean(correct):.3f}"
