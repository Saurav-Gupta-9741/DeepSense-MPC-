package com.iitj.pervasivesense

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TFLite DeepSense model. Contract (verified by tests/test_tflite_model.py):
 * input float32 [1, 128, 6] = 50 Hz (ax, ay, az m/s², gx, gy, gz rad/s),
 * row-major; output float32 [1, N] softmax over the labels in
 * assets/deepsense_labels.txt (shipped together with the model).
 *
 * If the model cannot be loaded, [initError] says exactly why and classify()
 * returns null, which the engine surfaces as MODEL_ERROR on the dashboard -
 * never a silent "UNKNOWN 0%".
 */
class DeepSenseClassifier(context: Context) : ActivityClassifier {

    companion object {
        private const val TAG = "DeepSenseClassifier"
        const val MODEL_ASSET = "deepsense_int8.tflite"
        const val LABELS_ASSET = "deepsense_labels.txt"
        const val WINDOW_SIZE = 128
        const val NUM_CHANNELS = 6
    }

    override val labels: List<String>
    var initError: String? = null
        private set

    private var interpreter: Interpreter? = null
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(WINDOW_SIZE * NUM_CHANNELS * 4).order(ByteOrder.nativeOrder())
    private val outputBuffer: ByteBuffer
    private val probabilities: FloatArray

    init {
        labels = try {
            context.assets.open(LABELS_ASSET).bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        } catch (e: Exception) {
            initError = "labels asset missing: ${e.message}"
            emptyList()
        }
        outputBuffer = ByteBuffer.allocateDirect(maxOf(1, labels.size) * 4).order(ByteOrder.nativeOrder())
        probabilities = FloatArray(labels.size)

        if (initError == null) {
            try {
                // Read into a direct buffer instead of mmap: APK asset offsets are not
                // always page aligned, which makes mmap fail on some devices.
                val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
                val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                model.put(bytes).rewind()
                val it = Interpreter(model, Interpreter.Options().setNumThreads(1))
                val inShape = it.getInputTensor(0).shape()
                val outShape = it.getOutputTensor(0).shape()
                check(inShape.contentEquals(intArrayOf(1, WINDOW_SIZE, NUM_CHANNELS))) {
                    "unexpected input shape ${inShape.contentToString()}"
                }
                check(outShape.contentEquals(intArrayOf(1, labels.size))) {
                    "output ${outShape.contentToString()} does not match ${labels.size} labels"
                }
                interpreter = it
                Log.i(TAG, "DeepSense loaded: ${bytes.size} bytes, classes=$labels")
            } catch (t: Throwable) {
                // Includes "Didn't find op for builtin opcode ..." when model and runtime versions mismatch.
                initError = t.message ?: t.javaClass.simpleName
                Log.e(TAG, "DeepSense failed to load", t)
            }
        }
    }

    override fun classify(window: FloatArray): FloatArray? {
        val it = interpreter ?: return null
        if (window.size != WINDOW_SIZE * NUM_CHANNELS) return null
        return try {
            inputBuffer.rewind()
            inputBuffer.asFloatBuffer().put(window)
            outputBuffer.rewind()
            it.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()
            outputBuffer.asFloatBuffer().get(probabilities)
            probabilities.copyOf()
        } catch (t: Throwable) {
            Log.e(TAG, "inference failed", t)
            null
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
