package com.echoshield.echonode.service

import android.content.Context
import android.util.Log
import com.echoshield.echonode.BuildConfig
import com.zeticai.mlange.core.model.ZeticMLangeModel
import com.zeticai.mlange.core.tensor.Tensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

class ZeticGunshotClassifier(
    private val context: Context
) {
    companion object {
        private const val TAG = "ZeticGunshotClassifier"
        private const val MODEL_NAME = "google/Sound Classification(YAMNET)"
        private const val LABEL_FILE = "yamnet_class_map.csv"
        const val INPUT_SAMPLES = 48000
    }

    data class Result(
        val gunshotConfidence: Float,
        val topLabel: String,
        val topScore: Float
    )

    private var model: ZeticMLangeModel? = null
    private var modelInputBuffers: Array<Tensor> = emptyArray()
    private var classLabels: List<String> = emptyList()
    private var gunshotIndices: List<Int> = emptyList()
    private var loggedOutputLayout = false

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            classLabels = loadLabels()
            gunshotIndices = classLabels.mapIndexedNotNull { index, label ->
                val lower = label.lowercase()
                if (lower.contains("gunshot") || lower.contains("gunfire") || lower.contains("machine gun")) {
                    index
                } else null
            }

            val personalKey = BuildConfig.ZETIC_KEY
            if (personalKey.isBlank()) {
                Log.e(TAG, "ZETIC_KEY is not configured in local.properties")
                return@withContext false
            }

            model = ZeticMLangeModel(context, personalKey, MODEL_NAME)
            modelInputBuffers = model?.getInputBuffers() ?: emptyArray()
            if (modelInputBuffers.isEmpty()) {
                Log.e(TAG, "Model exposes no input buffers")
                return@withContext false
            }
            Log.i(
                TAG,
                "Model input[0] byteSize=${modelInputBuffers[0].size()} count=${modelInputBuffers[0].count()}"
            )
            Log.i(TAG, "Zetic YAMNet initialized with ${classLabels.size} labels; gunshot indices=$gunshotIndices")
            runSmokeInference()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Zetic classifier", e)
            false
        }
    }

    fun classify(audio16k: FloatArray): Result? {
        val activeModel = model ?: return null
        val inputBuffer = modelInputBuffers.firstOrNull() ?: return null

        return try {
            val expectedCount = inputBuffer.size() / 4
            val normalizedInput = normalizeInput(audio16k, expectedCount)
            
            // Debug: Check audio energy
            var maxAbs = 0f
            var sumSq = 0f
            for (v in normalizedInput) {
                val abs = kotlin.math.abs(v)
                if (abs > maxAbs) maxAbs = abs
                sumSq += v * v
            }
            val rms = kotlin.math.sqrt(sumSq / normalizedInput.size)
            Log.d(TAG, "Input audio: size=${normalizedInput.size}, maxAbs=$maxAbs, rms=$rms")
            val sourceBuffer = ByteBuffer.allocateDirect(expectedCount * 4).order(ByteOrder.nativeOrder())
            sourceBuffer.asFloatBuffer().put(normalizedInput)
            sourceBuffer.rewind()
            inputBuffer.copy(data = sourceBuffer)

            val outputs = activeModel.run()
            val scores = extractClassScores(outputs) ?: return null

            var topIndex = 0
            var topScore = scores[0]
            for (i in 1 until scores.size) {
                if (scores[i] > topScore) {
                    topScore = scores[i]
                    topIndex = i
                }
            }

            var gunshotScore = 0f
            gunshotIndices.forEach { idx ->
                if (idx in scores.indices) {
                    gunshotScore = max(gunshotScore, scores[idx])
                }
            }

            Result(
                gunshotConfidence = gunshotScore,
                topLabel = classLabels.getOrElse(topIndex) { "unknown" },
                topScore = topScore
            )
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed", e)
            null
        }
    }

    fun close() {
        model = null
    }

    private fun extractClassScores(outputs: Array<Tensor>): FloatArray? {
        val labelCount = classLabels.size
        if (labelCount == 0) {
            Log.e(TAG, "No class labels loaded; cannot decode model output")
            return null
        }

        var bestCandidate: FloatArray? = null

        outputs.forEachIndexed { outputIndex, tensor ->
            val raw = try {
                val view: FloatBuffer = tensor.data()
                val copy = FloatArray(view.remaining())
                view.get(copy)
                copy
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading output[$outputIndex] as FloatBuffer", e)
                null
            } ?: return@forEachIndexed

            if (!loggedOutputLayout) {
                val frameGuess = if (raw.size % labelCount == 0) raw.size / labelCount else -1
                Log.i(
                    TAG,
                    "output[$outputIndex] float size=${raw.size}, labels=$labelCount, frameGuess=$frameGuess"
                )
            }

            if (raw.size < labelCount || raw.size % labelCount != 0) return@forEachIndexed

            val frameCount = raw.size / labelCount
            val aggregated = FloatArray(labelCount)
            for (frame in 0 until frameCount) {
                val base = frame * labelCount
                for (cls in 0 until labelCount) {
                    val value = raw[base + cls]
                    if (frame == 0 || value > aggregated[cls]) {
                        aggregated[cls] = value
                    }
                }
            }

            // Prefer single-frame class-score tensor if available; otherwise keep first valid.
            if (bestCandidate == null || frameCount == 1) {
                bestCandidate = aggregated
            }
        }

        loggedOutputLayout = true

        if (bestCandidate == null) {
            Log.e(TAG, "No compatible output tensor matched label count=$labelCount")
        }
        return bestCandidate
    }

    private fun runSmokeInference() {
        try {
            val expected = (modelInputBuffers.firstOrNull()?.size()?.div(4)) ?: INPUT_SAMPLES
            val silentInput = FloatArray(expected)
            val result = classify(silentInput)
            Log.i(
                TAG,
                "Smoke inference => topLabel=${result?.topLabel ?: "null"}, " +
                    "topScore=${result?.topScore ?: -1f}, gunshot=${result?.gunshotConfidence ?: -1f}"
            )
            
            // Test with WAV file if available (in app's private files dir)
            testWithWavFile(context.filesDir.absolutePath + "/gunshot_test.wav")
        } catch (e: Exception) {
            Log.w(TAG, "Smoke inference failed", e)
        }
    }
    
    private fun testWithWavFile(path: String) {
        try {
            val file = java.io.File(path)
            if (!file.exists()) {
                Log.i(TAG, "Test WAV file not found at $path, skipping")
                return
            }
            
            val audioData = loadWavAs16kMono(file)
            if (audioData == null) {
                Log.e(TAG, "Failed to load WAV file")
                return
            }
            
            Log.i(TAG, "Loaded WAV file: ${audioData.size} samples")
            val result = classify(audioData)
            Log.i(
                TAG,
                "WAV FILE TEST => topLabel=${result?.topLabel ?: "null"}, " +
                    "topScore=${result?.topScore ?: -1f}, gunshot=${result?.gunshotConfidence ?: -1f}"
            )
        } catch (e: Exception) {
            Log.w(TAG, "WAV file test failed", e)
        }
    }
    
    private fun loadWavAs16kMono(file: java.io.File): FloatArray? {
        return try {
            val bytes = file.readBytes()
            if (bytes.size < 44) return null
            
            // Parse WAV header
            val channels = (bytes[22].toInt() and 0xFF) or ((bytes[23].toInt() and 0xFF) shl 8)
            val sampleRate = (bytes[24].toInt() and 0xFF) or 
                ((bytes[25].toInt() and 0xFF) shl 8) or
                ((bytes[26].toInt() and 0xFF) shl 16) or
                ((bytes[27].toInt() and 0xFF) shl 24)
            val bitsPerSample = (bytes[34].toInt() and 0xFF) or ((bytes[35].toInt() and 0xFF) shl 8)
            
            Log.i(TAG, "WAV: channels=$channels, sampleRate=$sampleRate, bits=$bitsPerSample")
            
            // Find data chunk
            var dataStart = 12
            while (dataStart < bytes.size - 8) {
                val chunkId = String(bytes, dataStart, 4)
                val chunkSize = (bytes[dataStart + 4].toInt() and 0xFF) or
                    ((bytes[dataStart + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[dataStart + 6].toInt() and 0xFF) shl 16) or
                    ((bytes[dataStart + 7].toInt() and 0xFF) shl 24)
                if (chunkId == "data") {
                    dataStart += 8
                    break
                }
                dataStart += 8 + chunkSize
            }
            
            // Read samples as 16-bit PCM
            val sampleCount = (bytes.size - dataStart) / (channels * (bitsPerSample / 8))
            val monoSamples = FloatArray(sampleCount)
            
            var byteIndex = dataStart
            for (i in 0 until sampleCount) {
                var sum = 0
                for (ch in 0 until channels) {
                    val sample = (bytes[byteIndex].toInt() and 0xFF) or 
                        ((bytes[byteIndex + 1].toInt()) shl 8)
                    sum += sample
                    byteIndex += 2
                }
                monoSamples[i] = (sum / channels) / 32768f
            }
            
            // Resample to 16kHz if needed
            val targetRate = 16000
            if (sampleRate != targetRate) {
                val ratio = sampleRate.toFloat() / targetRate
                val resampledCount = (sampleCount / ratio).toInt()
                val resampled = FloatArray(resampledCount)
                for (i in 0 until resampledCount) {
                    val srcIdx = (i * ratio).toInt().coerceIn(0, sampleCount - 1)
                    resampled[i] = monoSamples[srcIdx]
                }
                Log.i(TAG, "Resampled from $sampleRate to $targetRate: ${resampled.size} samples")
                return resampled
            }
            
            monoSamples
        } catch (e: Exception) {
            Log.e(TAG, "Error loading WAV", e)
            null
        }
    }

    private fun normalizeInput(input: FloatArray, expectedCount: Int): FloatArray {
        if (input.size == expectedCount) return input
        val normalized = FloatArray(expectedCount)
        val copyCount = minOf(input.size, expectedCount)
        input.copyInto(normalized, endIndex = copyCount)
        if (input.size != expectedCount) {
            Log.w(TAG, "Resized audio input from ${input.size} to $expectedCount")
        }
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
