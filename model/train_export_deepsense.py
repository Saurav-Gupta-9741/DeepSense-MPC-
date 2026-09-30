"""
DeepSense TinyML - training & export on REAL smartphone IMU recordings.

    python train_export_deepsense.py --motionsense <dir> --uci <dir> [--epochs 30]

Data: MotionSense (phone in trouser pocket, 24 subjects) + UCI-HAR (phone on
waist, 30 subjects), both 50 Hz, converted to Android sensor units by
har_datasets.py. Splits are SUBJECT-DISJOINT: no person in the test set was
seen in training, so the reported accuracy reflects a new user.

Orientation robustness: every training window is rotated by a uniformly random
3-D rotation, applied identically to the accelerometer and gyroscope (a rigid
body rotation), so the model does not depend on how the phone sits in a pocket.

Export: converted with TensorFlow 2.16.1 - the same runtime version the Android
app bundles (org.tensorflow:tensorflow-lite:2.16.1) - and verified by loading
the exported flatbuffer in that runtime before it is written.
"""
from __future__ import annotations

import argparse
import json
import os
import time

import numpy as np
import tensorflow as tf
import keras
from keras import layers, ops

from har_datasets import CLASS_NAMES, STANDARD_GRAVITY, WINDOW, load_motionsense, load_uci

NUM_CLASSES = len(CLASS_NAMES)
SEED = 42

# Subject-disjoint splits. MotionSense subjects 1..24, UCI subjects 101..130.
MS_TEST, MS_VAL = {4, 8, 12, 16, 20, 24}, {2, 14, 22}
UCI_TEST = {102, 104, 109, 110, 112, 113, 118, 120, 124}  # official UCI test subjects
UCI_VAL = {101, 111, 121}


# ------------------------------------------------------------------ augmentation
def random_rotations(n: int, rng: np.random.Generator) -> np.ndarray:
    """Uniformly distributed rotation matrices (Shoemake's quaternion method)."""
    u1, u2, u3 = rng.random(n), rng.random(n), rng.random(n)
    q = np.stack([
        np.sqrt(1 - u1) * np.sin(2 * np.pi * u2), np.sqrt(1 - u1) * np.cos(2 * np.pi * u2),
        np.sqrt(u1) * np.sin(2 * np.pi * u3), np.sqrt(u1) * np.cos(2 * np.pi * u3)], axis=1)
    x, y, z, w = q.T
    return np.stack([
        np.stack([1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)], -1),
        np.stack([2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)], -1),
        np.stack([2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)], -1)], 1)


def rotate(x: np.ndarray, r: np.ndarray) -> np.ndarray:
    acc = np.einsum("nij,ntj->nti", r, x[:, :, :3])
    gyr = np.einsum("nij,ntj->nti", r, x[:, :, 3:])
    return np.concatenate([acc, gyr], -1)


def augment(x: np.ndarray, y: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    n = len(x)
    x = x.astype(np.float32).copy()
    # Scale the dynamic (non-gravity) part: models gait vigour & placement. STILL
    # windows may shrink to ~0 dynamics, which is exactly a phone resting on a desk.
    grav = x[:, :, :3].mean(axis=1, keepdims=True)
    lo = np.where(y == 0, 0.0, 0.8)[:, None, None]
    s = lo + rng.random((n, 1, 1)) * (1.2 - lo)
    x[:, :, :3] = grav + (x[:, :, :3] - grav) * s
    x[:, :, 3:] *= s
    x = rotate(x, random_rotations(n, rng))
    # Sensor noise & gyro bias typical of consumer MEMS IMUs.
    x[:, :, :3] += rng.normal(0, 0.03, (n, WINDOW, 3))
    x[:, :, 3:] += rng.normal(0, 0.004, (n, WINDOW, 3)) + rng.normal(0, 0.01, (n, 1, 3))
    return x.astype(np.float32)


class AugmentedSequence(keras.utils.PyDataset):
    def __init__(self, x, y, weights, batch, seed):
        super().__init__()
        self.x, self.y, self.w, self.batch = x, y, weights, batch
        self.rng = np.random.default_rng(seed)
        self.order = self.rng.permutation(len(x))

    def __len__(self):
        return int(np.ceil(len(self.x) / self.batch))

    def __getitem__(self, i):
        idx = self.order[i * self.batch:(i + 1) * self.batch]
        return augment(self.x[idx], self.y[idx], self.rng), self.y[idx], self.w[self.y[idx]]

    def on_epoch_end(self):
        self.order = self.rng.permutation(len(self.x))


# ------------------------------------------------------------------ model
def build_model() -> keras.Model:
    """
    Dual-branch DeepSense-style 1-D CNN. Input contract [1, 128, 6] =
    (ax, ay, az [m/s^2], gx, gy, gz [rad/s]) at 50 Hz, row-major (sample, channel).
    """
    inp = keras.Input(shape=(WINDOW, 6), name="imu_input")
    acc = layers.Lambda(lambda t: t[:, :, 0:3] / STANDARD_GRAVITY, name="accel_g")(inp)
    gyr = layers.Lambda(lambda t: t[:, :, 3:6], name="gyro")(inp)
    # Rotation-invariant magnitude channels.
    acc_mag = layers.Lambda(lambda t: ops.sqrt(ops.sum(ops.square(t), -1, keepdims=True) + 1e-6), name="accel_mag")(acc)
    gyr_mag = layers.Lambda(lambda t: ops.sqrt(ops.sum(ops.square(t), -1, keepdims=True) + 1e-6), name="gyro_mag")(gyr)
    a = layers.Concatenate()([acc, acc_mag])
    g = layers.Concatenate()([gyr, gyr_mag])

    def branch(t, name):
        t = layers.Conv1D(32, 5, padding="same", use_bias=False, name=f"{name}_c1")(t)
        t = layers.BatchNormalization(name=f"{name}_bn1")(t)
        t = layers.ReLU()(t)
        t = layers.Conv1D(32, 3, padding="same", activation="relu", name=f"{name}_c2")(t)
        return layers.MaxPooling1D(2)(t)

    f = layers.Concatenate()([branch(a, "acc"), branch(g, "gyr")])
    f = layers.Conv1D(64, 3, padding="same", use_bias=False)(f)
    f = layers.BatchNormalization()(f)
    f = layers.ReLU()(f)
    for d in (1, 2, 4):  # multi-scale temporal context (receptive field ~ full window)
        f = layers.Conv1D(64, 3, dilation_rate=d, padding="same", activation="relu")(f)
    f = layers.GlobalAveragePooling1D()(f)
    f = layers.Dense(64, activation="relu")(f)
    f = layers.Dropout(0.3)(f)
    out = layers.Dense(NUM_CLASSES, activation="softmax", name="activity_probs")(f)
    return keras.Model(inp, out, name="DeepSense_TinyML_v2")


# ------------------------------------------------------------------ evaluation
def tflite_predict(model_bytes: bytes, x: np.ndarray) -> np.ndarray:
    it = tf.lite.Interpreter(model_content=model_bytes)
    it.allocate_tensors()
    i, o = it.get_input_details()[0]["index"], it.get_output_details()[0]["index"]
    out = np.empty((len(x), NUM_CLASSES), np.float32)
    for k in range(len(x)):
        it.set_tensor(i, x[k:k + 1])
        it.invoke()
        out[k] = it.get_tensor(o)[0]
    return out


def report(y_true, y_pred) -> dict:
    cm = np.zeros((NUM_CLASSES, NUM_CLASSES), int)
    for t, p in zip(y_true, y_pred):
        cm[t, p] += 1
    recall = cm.diagonal() / np.maximum(cm.sum(1), 1)
    precision = cm.diagonal() / np.maximum(cm.sum(0), 1)
    f1 = 2 * precision * recall / np.maximum(precision + recall, 1e-9)
    present = cm.sum(1) > 0
    return {"accuracy": float(cm.trace() / cm.sum()), "macro_f1": float(f1[present].mean()),
            "per_class_f1": {c: float(v) for c, v, p in zip(CLASS_NAMES, f1, present) if p},
            "confusion": cm.tolist()}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--motionsense", required=True)
    ap.add_argument("--uci", required=True)
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--out", default=os.path.dirname(os.path.abspath(__file__)))
    args = ap.parse_args()
    assert tf.__version__.startswith("2.16."), (
        f"Export with TF 2.16.x to match the Android runtime; found {tf.__version__}")

    keras.utils.set_random_seed(SEED)
    xm, ym, sm = load_motionsense(args.motionsense, stride=32)
    xu, yu, su = load_uci(args.uci)
    x, y, s = np.concatenate([xm, xu]), np.concatenate([ym, yu]), np.concatenate([sm, su])
    test_s, val_s = MS_TEST | UCI_TEST, MS_VAL | UCI_VAL
    te, va = np.isin(s, list(test_s)), np.isin(s, list(val_s))
    tr = ~(te | va)
    print(f"windows train={tr.sum()} val={va.sum()} test={te.sum()} | "
          f"subjects train={len(set(s[tr]))} val={len(set(s[va]))} test={len(set(s[te]))}")

    counts = np.bincount(y[tr], minlength=NUM_CLASSES)
    class_w = (counts.sum() / (NUM_CLASSES * counts)).astype(np.float32)
    # Fixed random rotations for validation/test so every orientation is exercised.
    x_val = augment(x[va], y[va], np.random.default_rng(1))
    x_test_rot = rotate(x[te], random_rotations(te.sum(), np.random.default_rng(2))).astype(np.float32)

    model = build_model()
    model.summary()
    model.compile(optimizer=keras.optimizers.Adam(1e-3), loss="sparse_categorical_crossentropy",
                  metrics=["accuracy"])
    t0 = time.time()
    model.fit(AugmentedSequence(x[tr], y[tr], class_w, 64, SEED), validation_data=(x_val, y[va]),
              epochs=args.epochs, verbose=2,
              callbacks=[keras.callbacks.ReduceLROnPlateau(patience=3, factor=0.5, min_lr=1e-5),
                         keras.callbacks.EarlyStopping(patience=7, restore_best_weights=True,
                                                       monitor="val_accuracy")])
    print(f"training took {time.time() - t0:.0f}s")

    # ---- export (dynamic-range INT8) and verify in the 2.16 runtime
    # Keras 3 models are exported via a concrete function with the exact Android
    # input signature (from_keras_model is broken for Keras 3 on TF 2.16).
    serve = tf.function(lambda t: model(t, training=False),
                        input_signature=[tf.TensorSpec([1, WINDOW, 6], tf.float32, name="imu_input")])
    conv = tf.lite.TFLiteConverter.from_concrete_functions([serve.get_concrete_function()], model)
    conv.optimizations = [tf.lite.Optimize.DEFAULT]
    tfl = conv.convert()
    tflite_predict(tfl, x[te][:1].astype(np.float32))  # raises if the 2.16 runtime cannot load it

    metrics = {"tensorflow": tf.__version__, "classes": CLASS_NAMES, "model_bytes": len(tfl),
               "splits": {"train_windows": int(tr.sum()), "test_windows": int(te.sum()),
                          "test_subjects": sorted(int(v) for v in set(s[te]))}}
    p_keras = model.predict(x[te], verbose=0)
    p_tfl = tflite_predict(tfl, x[te].astype(np.float32))
    p_tfl_rot = tflite_predict(tfl, x_test_rot)
    metrics["test_keras_native_orientation"] = report(y[te], p_keras.argmax(1))
    metrics["test_tflite_native_orientation"] = report(y[te], p_tfl.argmax(1))
    metrics["test_tflite_random_orientation"] = report(y[te], p_tfl_rot.argmax(1))
    for name, mask in (("motionsense_pocket", te & (s < 100)), ("uci_waist", te & (s >= 100))):
        sub = mask[te]
        metrics[f"test_tflite_{name}"] = report(y[te][sub], p_tfl_rot[sub].argmax(1))
    metrics["int8_vs_float_agreement"] = float((p_keras.argmax(1) == p_tfl.argmax(1)).mean())

    with open(os.path.join(args.out, "deepsense_int8.tflite"), "wb") as fh:
        fh.write(tfl)
    # Labels travel with the model; the Android classifier reads them from assets.
    with open(os.path.join(args.out, "deepsense_labels.txt"), "w") as fh:
        fh.write("\n".join(CLASS_NAMES) + "\n")
    with open(os.path.join(args.out, "model_metrics.json"), "w") as fh:
        json.dump(metrics, fh, indent=2)
    for k, v in metrics.items():
        if k.startswith("test_"):
            print(f"{k:40s} acc={v['accuracy']*100:5.1f}%  macroF1={v['macro_f1']:.3f}")
    print(f"INT8 vs float argmax agreement: {metrics['int8_vs_float_agreement']*100:.2f}%  "
          f"| size {len(tfl)/1024:.1f} KB")


if __name__ == "__main__":
    main()
