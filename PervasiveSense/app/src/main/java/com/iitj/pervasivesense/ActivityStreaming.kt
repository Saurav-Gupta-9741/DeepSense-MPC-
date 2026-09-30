package com.iitj.pervasivesense

/** Receives raw, timestamped IMU events from any source (phone sensors, a wearable, a replay). */
interface ImuSink {
    fun onAccelerometer(tNanos: Long, x: Float, y: Float, z: Float)
    fun onGyroscope(tNanos: Long, x: Float, y: Float, z: Float)
}

/** Model abstraction so the streaming engine is testable without TFLite. */
interface ActivityClassifier {
    /** Class names, index-aligned with the probability vector. */
    val labels: List<String>

    /** Returns class probabilities for a [128 x 6] row-major window, or null on failure. */
    fun classify(window: FloatArray): FloatArray?
}

/**
 * Circular buffer holding the most recent [size] multi-channel samples.
 * Enables overlapping (sliding) inference: the model re-runs every `stride`
 * samples on the latest 2.56 s of motion instead of waiting for a fresh window.
 */
class SlidingWindow(val size: Int, val channels: Int) {
    private val data = FloatArray(size * channels)
    private var head = 0
    var filled = 0
        private set

    val isFull get() = filled == size

    fun push(sample: FloatArray) {
        System.arraycopy(sample, 0, data, head * channels, channels)
        head = (head + 1) % size
        if (filled < size) filled++
    }

    /** Copies samples oldest-first into [dst] (row-major: sample, channel). */
    fun copyChronological(dst: FloatArray) {
        require(dst.size == size * channels)
        val start = if (isFull) head else 0
        val firstPart = (size - start) * channels
        System.arraycopy(data, start * channels, dst, 0, firstPart)
        System.arraycopy(data, 0, dst, firstPart, start * channels)
    }

    fun clear() { head = 0; filled = 0 }
}

/**
 * Temporal smoothing of per-window probabilities with switching hysteresis.
 *
 * Raw per-window predictions flicker at activity boundaries and on ambiguous
 * windows. An exponential moving average over overlapping windows plus a
 * confirmation count gives a stable label while keeping latency low
 * (with a 0.5 s stride, alpha 0.5 and confirm 2 the added delay is ~1 s).
 */
class ActivitySmoother(
    private val numClasses: Int,
    private val alpha: Float = 0.5f,
    private val enterThreshold: Float = 0.5f,
    private val confirmCount: Int = 2
) {
    val smoothed = FloatArray(numClasses)
    private var initialised = false
    private var pending = -1
    private var pendingHits = 0

    var stableIndex = -1
        private set
    val stableConfidence get() = if (stableIndex >= 0) smoothed[stableIndex] else 0f

    /** Feeds one probability vector; returns the (possibly unchanged) stable index or -1. */
    fun update(probs: FloatArray): Int {
        require(probs.size == numClasses)
        if (!initialised) {
            probs.copyInto(smoothed); initialised = true
        } else {
            for (i in 0 until numClasses) smoothed[i] = alpha * probs[i] + (1 - alpha) * smoothed[i]
        }
        var best = 0
        for (i in 1 until numClasses) if (smoothed[i] > smoothed[best]) best = i

        when {
            best == stableIndex -> { pending = -1; pendingHits = 0 }
            smoothed[best] < enterThreshold -> { pending = -1; pendingHits = 0 }
            stableIndex < 0 -> stableIndex = best // first confident decision: no extra delay
            else -> {
                if (best == pending) pendingHits++ else { pending = best; pendingHits = 1 }
                if (pendingHits >= confirmCount) { stableIndex = best; pending = -1; pendingHits = 0 }
            }
        }
        return stableIndex
    }

    fun reset() {
        smoothed.fill(0f); initialised = false
        pending = -1; pendingHits = 0; stableIndex = -1
    }
}
