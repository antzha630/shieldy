package com.echoshield.echonode.service

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max

class GunshotClassifier(
    private val context: Context
) {
    companion object {
        private const val TAG = "GunshotClassifier"
        private const val MODEL_FILE = "yamnet.tflite"
        private const val LABEL_FILE = "yamnet_class_map.csv"
        private const val INPUT_SIZE = 15600
    }

    data class Result(
        val gunshotConfidence: Float,
        val topLabel: String,
        val topScore: Float
    )

    private var interpreter: Interpreter? = null
    private var classLabels: List<String> = emptyList()
    private var gunshotIndices: List<Int> = emptyList()

    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(INPUT_SIZE * 4)
        .order(ByteOrder.nativeOrder())

    fun initialize(): Boolean {
        return try {
            classLabels = loadLabels()
            gunshotIndices = classLabels.mapIndexedNotNull { index, label ->
                val lower = label.lowercase()
                if (lower.contains("gunshot") || lower.contains("gunfire")) index else null
            }

            val model = loadModelFile(MODEL_FILE)
            val options = Interpreter.Options().apply {
                setNumThreads(2)
            }
            interpreter = Interpreter(model, options)
            Log.i(TAG, "Initialized YAMNet with ${classLabels.size} labels; gunshot indices=$gunshotIndices")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize classifier", e)
            false
        }
    }

    fun classify(audio16k: FloatArray): Result? {
        val model = interpreter ?: return null
        if (audio16k.size != INPUT_SIZE) return null

        inputBuffer.rewind()
        audio16k.forEach { sample ->
            inputBuffer.putFloat(sample.coerceIn(-1f, 1f))
        }
        inputBuffer.rewind()

        val outputScores = Array(1) { FloatArray(521) }
        model.run(inputBuffer, outputScores)
        val frameScores = outputScores[0]

        var topIndex = 0
        var topScore = frameScores[0]
        for (i in 1 until frameScores.size) {
            if (frameScores[i] > topScore) {
                topScore = frameScores[i]
                topIndex = i
            }
        }

        var gunshotScore = 0f
        gunshotIndices.forEach { idx ->
            if (idx in frameScores.indices) {
                gunshotScore = max(gunshotScore, frameScores[idx])
            }
        }

        return Result(
            gunshotConfidence = gunshotScore,
            topLabel = classLabels.getOrNull(topIndex) ?: "unknown",
            topScore = topScore
        )
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    private fun loadModelFile(assetName: String): ByteBuffer {
        val assetFileDescriptor = context.assets.openFd(assetName)
        val inputStream = assetFileDescriptor.createInputStream()
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            assetFileDescriptor.startOffset,
            assetFileDescriptor.declaredLength
        )
    }

    private fun loadLabels(): List<String> {
        val labels = mutableListOf<String>()
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
        return labels
    }
}
