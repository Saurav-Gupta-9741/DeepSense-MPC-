package com.iitj.pervasivesense

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ClassificationResult(
    val activity: String,
    val confidence: Float,
    val allProbabilities: FloatArray
)

class DeepSenseClassifier(context: Context) {

    companion object {
        private const val TAG = "DeepSenseClassifier"
        const val WINDOW_SIZE = 128
        const val NUM_CHANNELS = 6
        const val NUM_CLASSES = 8
    }

    private var interpreter: Interpreter? = null

    val classNames = arrayOf(
        "STILL",
        "WALKING",
        "RUNNING",
        "STAIRS_UP",
        "STAIRS_DOWN",
        "BUS",
        "CAR",
        "METRO"
    )

    // Pre-allocate DirectByteBuffers ONCE to avoid GC churn on every inference
    private val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * WINDOW_SIZE * NUM_CHANNELS * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    private val outputBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * NUM_CLASSES * 4).apply {
        order(ByteOrder.nativeOrder())
    }

    init {
        try {
            // Read model bytes directly from assets into a DirectByteBuffer.
            // This avoids mmap page-alignment (EINVAL) failures on Android devices when the APK asset offset is not 4KB-aligned.
            val modelBytes = context.assets.open("deepsense_int8.tflite").use { it.readBytes() }
            val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size).apply {
                order(ByteOrder.nativeOrder())
                put(modelBytes)
                rewind()
            }

            val options = Interpreter.Options().apply {
                setNumThreads(2)
            }
            interpreter = Interpreter(modelBuffer, options)
            Log.d(TAG, "TFLite Interpreter initialized successfully! Model size: ${modelBytes.size} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "FATAL: Failed to initialize DeepSense TFLite Interpreter", e)
            e.printStackTrace()
        }
    }

    /**
     * Runs inference on a 128-sample window (128 x 6 = 768 floats).
     * Shape: [1, 128, 6]
     * Reuses pre-allocated ByteBuffers to avoid memory allocation per inference.
     */
    fun classify(windowData: FloatArray): ClassificationResult {
        val currentInterpreter = interpreter
        if (currentInterpreter == null || windowData.size != WINDOW_SIZE * NUM_CHANNELS) {
            Log.w(TAG, "classify() aborted: interpreter is null=${currentInterpreter == null} or size=${windowData.size}")
            return ClassificationResult("UNKNOWN", 0f, FloatArray(NUM_CLASSES))
        }

        try {
            // Reuse pre-allocated input buffer
            inputBuffer.rewind()
            for (value in windowData) {
                inputBuffer.putFloat(value)
            }

            // Reuse pre-allocated output buffer
            outputBuffer.rewind()

            currentInterpreter.run(inputBuffer, outputBuffer)

            outputBuffer.rewind()
            val probabilities = FloatArray(NUM_CLASSES)
            var maxIndex = 0
            var maxProb = -1.0f

            for (i in 0 until NUM_CLASSES) {
                probabilities[i] = outputBuffer.float
                if (probabilities[i] > maxProb) {
                    maxProb = probabilities[i]
                    maxIndex = i
                }
            }

            return ClassificationResult(
                activity = classNames[maxIndex],
                confidence = maxProb,
                allProbabilities = probabilities
            )
        } catch (e: Exception) {
            Log.e(TAG, "Inference error during classify()", e)
            return ClassificationResult("UNKNOWN", 0f, FloatArray(NUM_CLASSES))
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
