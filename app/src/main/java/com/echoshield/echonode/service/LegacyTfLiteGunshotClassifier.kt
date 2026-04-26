package com.echoshield.echonode.service

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LegacyTfLiteGunshotClassifier(
    private val context: Context
) {
    companion object {
        private const val TAG = "LegacyTfLiteClassifier"
        private const val MODEL_FILE = "yamnet.tflite"
        private const val LABEL_FILE = "yamnet_class_map.csv"
        const val INPUT_SAMPLES = 15600
    }

    data class Result(
        val gunshotConfidence: Float,
        val topLabel: String,
        val topScore: Float
    )

    private var interpreter: Interpreter? = null
    private var classLabels: List<String> = emptyList()
    private var gunshotIndices: List<Int> = emptyList()

    fun initialize(): Boolean {
        return try {
            classLabels = loadLabels()
            if (classLabels.isEmpty()) {
                Log.e(TAG, "Failed loading labels from $LABEL_FILE")
                return false
            }
            gunshotIndices = classLabels.mapIndexedNotNull { index, label ->
                val lower = label.lowercase()
                val isGunRelated = lower.contains("gunshot") ||
                    lower.contains("gunfire") ||
                    lower.contains("machine gun") ||
                    lower.contains("explosion") ||
                    lower.contains("artillery") ||
                    lower.contains("fusillade") ||
                    lower.contains("cap gun") ||
                    lower.contains("burst") ||
                    lower.contains("bang") ||
                    (lower.contains("pop") && !lower.contains("music"))
                if (isGunRelated) index else null
            }

            val modelBytes = context.assets.open(MODEL_FILE).use { it.readBytes() }
            val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size)
                .order(ByteOrder.nativeOrder())
            modelBuffer.put(modelBytes)
            modelBuffer.rewind()

            val options = Interpreter.Options().apply {
                setNumThreads(2)
            }
            interpreter = Interpreter(modelBuffer, options)

            Log.i(TAG, "Legacy TFLite initialized labels=${classLabels.size} gunIndices=${gunshotIndices.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize legacy TensorFlow Lite classifier", e)
            false
        }
    }

    fun classify(audio16k: FloatArray): Result? {
        val activeInterpreter = interpreter ?: return null
        return try {
            val normalizedInput = normalizeInput(audio16k, INPUT_SAMPLES)
            val input = arrayOf(normalizedInput)

            val classCount = classLabels.size
            val outputScores = Array(1) { FloatArray(classCount) }
            val fallbackOutput = Array(1) { FloatArray(1024) }

            val outputMap = mutableMapOf<Int, Any>(
                0 to outputScores,
                1 to fallbackOutput,
                2 to fallbackOutput
            )
            activeInterpreter.runForMultipleInputsOutputs(arrayOf(input), outputMap)

            val scores = when {
                outputScores[0].isNotEmpty() -> outputScores[0]
                else -> return null
            }

            var topIndex = 0
            var topScore = scores[0]
            for (i in 1 until scores.size) {
                if (scores[i] > topScore) {
                    topScore = scores[i]
                    topIndex = i
                }
            }

            var gunshotScore = 0f
            var bestGunshotIdx = -1
            gunshotIndices.forEach { idx ->
                if (idx in scores.indices && scores[idx] > gunshotScore) {
                    gunshotScore = scores[idx]
                    bestGunshotIdx = idx
                }
            }
            if (gunshotScore > 0.05f) {
                Log.d(
                    TAG,
                    "Legacy TF gunshot=${"%.3f".format(gunshotScore)} label=${
                        classLabels.getOrElse(bestGunshotIdx) { "?" }
                    }"
                )
            }

            Result(
                gunshotConfidence = gunshotScore,
                topLabel = classLabels.getOrElse(topIndex) { "unknown" },
                topScore = topScore
            )
        } catch (e: Exception) {
            Log.e(TAG, "Legacy TF inference failed", e)
            null
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    private fun normalizeInput(input: FloatArray, expectedCount: Int): FloatArray {
        if (input.size == expectedCount) return input
        val normalized = FloatArray(expectedCount)
        input.copyInto(normalized, endIndex = minOf(input.size, expectedCount))
        return normalized
    }

    private fun loadLabels(): List<String> {
        val labels = mutableListOf<String>()
        try {
            context.assets.open(LABEL_FILE).use { stream ->
                BufferedReader(InputStreamReader(stream)).useLines { lines ->
                    lines.drop(1).forEach { line ->
                        val parts = line.split(",")
                        if (parts.size >= 3) {
                            labels += parts[2].trim().trim('"')
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load labels", e)
        }
        return labels
    }
}
