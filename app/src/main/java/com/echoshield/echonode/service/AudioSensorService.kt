package com.echoshield.echonode.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.echoshield.echonode.MainActivity
import com.echoshield.echonode.R
import com.echoshield.echonode.data.MeshNetworkManager
import com.echoshield.echonode.data.SystemEventFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

class AudioSensorService : Service() {

    companion object {
        private const val TAG = "AudioSensorService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "echoshield_sensor_channel"

        // ─────────────────────────────────────────────────────────────────────
        // AUDIO CAPTURE SETTINGS
        // ─────────────────────────────────────────────────────────────────────
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // ─────────────────────────────────────────────────────────────────────
        // TWO-TIER DETECTOR TUNING CONSTANTS
        // ─────────────────────────────────────────────────────────────────────
        
        // TIER 1: Low-power activity gate
        // EMA alpha: higher = more responsive to spikes, lower = more stable.
        // 0.4 is responsive enough for impulsive sounds like gunshots while
        // still filtering out brief noise. Original was 0.15 which over-dampened spikes.
        private const val EMA_ALPHA = 0.4
        
        // Gate threshold is read from SystemEventFlow.detectionThreshold (default 450).
        // Tune higher to reduce false gate opens from speech/ambient noise.
        // Typical quiet room ~50-100 RMS, speech ~200-500, clap/loud ~800+.

        // TIER 2: ML confirmation
        // Gunshot confidence threshold. YAMNet typically outputs 0.0-1.0 for each class.
        // 0.15 is sensitive for real gunshot detection; was 0.2 originally, worked well.
        // Increase to 0.25-0.35 if getting false positives from other loud sounds.
        private const val GUNSHOT_CONFIDENCE_THRESHOLD = 0.15f
        
        // Minimum consecutive high-confidence frames before trigger.
        // Prevents single-frame noise spikes from triggering.
        private const val MIN_CONSECUTIVE_DETECTIONS = 1
        
        // ML inference rate limit. Running inference too often drains battery.
        // 400ms provides ~2.5 inferences/sec when gate is open.
        private const val MODEL_INFERENCE_INTERVAL_MS = 400L

        // COOLDOWN: Prevent rapid re-triggers after a detection.
        // 5 seconds allows situation assessment before next alert.
        private const val TRIGGER_COOLDOWN_MS = 5000L
        
        // Telemetry update rate for UI. 100ms = 10 updates/sec.
        private const val AMPLITUDE_UPDATE_INTERVAL_MS = 100L

        // ─────────────────────────────────────────────────────────────────────
        // MODEL SETTINGS (YAMNet expects 16kHz input, ~0.975s window)
        // ─────────────────────────────────────────────────────────────────────
        private const val MODEL_SAMPLE_RATE = 16000
        private const val MODEL_INPUT_SAMPLES = 15600

        fun startService(context: Context) {
            val intent = Intent(context, AudioSensorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, AudioSensorService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var audioRecordingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var meshNetworkManager: MeshNetworkManager
    private lateinit var gunshotClassifier: GunshotClassifier
    
    // Two-tier detector state
    private var lastTriggerTime = 0L
    private var smoothedAmplitude = 0.0
    private var consecutiveHighConfidence = 0
    private var currentGateOpen = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        acquireWakeLock()
        meshNetworkManager = MeshNetworkManager.getInstance(applicationContext)
        gunshotClassifier = GunshotClassifier(applicationContext)
        val modelInitialized = gunshotClassifier.initialize()
        Log.i(TAG, "Gunshot classifier initialized: $modelInitialized")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        
        startForegroundWithNotification()
        meshNetworkManager.startMesh()
        startAudioRecording()
        
        SystemEventFlow.setServiceRunning(true)
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        stopAudioRecording()
        meshNetworkManager.stopMesh()
        gunshotClassifier.close()
        releaseWakeLock()
        serviceScope.cancel()
        SystemEventFlow.setServiceRunning(false)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "EchoShield::AudioSensorWakeLock"
        ).apply {
            acquire(60 * 60 * 1000L) // 1 hour max
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun startAudioRecording() {
        if (audioRecordingJob?.isActive == true) {
            Log.d(TAG, "Audio recording already active")
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            Log.d(TAG, "Audio recording started with buffer size: $bufferSize")

            audioRecordingJob = serviceScope.launch {
                processAudioStream(bufferSize)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting audio recording", e)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting audio recording", e)
        }
    }

    private suspend fun processAudioStream(bufferSize: Int) {
        val buffer = ShortArray(bufferSize)
        val rollingModelSamples = FloatArray(MODEL_INPUT_SAMPLES)
        var rollingWriteIndex = 0
        var rollingBufferFilled = false
        var lastAmplitudeUpdate = 0L
        var lastModelInference = 0L

        while (audioRecordingJob?.isActive == true) {
            val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: -1

            if (readResult > 0) {
                val instantRms = calculateRMS(buffer, readResult)
                
                // TIER 1: Apply EMA smoothing for stable gate decisions
                smoothedAmplitude = EMA_ALPHA * instantRms + (1 - EMA_ALPHA) * smoothedAmplitude
                
                appendToRollingBuffer(
                    source = buffer,
                    size = readResult,
                    destination = rollingModelSamples,
                    writeIndex = rollingWriteIndex
                ).also { (newIndex, didWrap) ->
                    rollingWriteIndex = newIndex
                    if (didWrap) {
                        rollingBufferFilled = true
                    }
                }

                val currentTime = System.currentTimeMillis()
                val cooldownRemaining = maxOf(0L, TRIGGER_COOLDOWN_MS - (currentTime - lastTriggerTime))
                
                // Update telemetry at fixed interval
                if (currentTime - lastAmplitudeUpdate >= AMPLITUDE_UPDATE_INTERVAL_MS) {
                    SystemEventFlow.updateAmplitude(instantRms)
                    SystemEventFlow.updateSmoothedAmplitude(smoothedAmplitude)
                    SystemEventFlow.updateCooldownRemaining(cooldownRemaining)
                    lastAmplitudeUpdate = currentTime
                }

                // TIER 1: Activity gate check
                // Use RAW amplitude for gate (not smoothed) - gunshots are impulsive spikes
                // that get dampened by EMA. Gate opens on ANY loud sound, ML filters false positives.
                val gateThreshold = SystemEventFlow.detectionThreshold.value
                val gateOpen = instantRms >= gateThreshold && rollingBufferFilled
                
                // Update gate state telemetry if changed
                if (gateOpen != currentGateOpen) {
                    currentGateOpen = gateOpen
                    SystemEventFlow.updateGateOpen(gateOpen)
                    if (gateOpen) {
                        Log.d(TAG, "Activity gate OPENED (rms=${String.format("%.1f", instantRms)} >= $gateThreshold)")
                    } else {
                        Log.d(TAG, "Activity gate CLOSED")
                        consecutiveHighConfidence = 0
                    }
                }

                // TIER 2: ML inference only when gate is open and rate limit allows
                val shouldRunModel = gateOpen &&
                    (currentTime - lastModelInference >= MODEL_INFERENCE_INTERVAL_MS)

                if (shouldRunModel) {
                    lastModelInference = currentTime
                    val modelInput = snapshotModelInput(rollingModelSamples, rollingWriteIndex)
                    val result = gunshotClassifier.classify(modelInput)
                    
                    if (result != null) {
                        SystemEventFlow.updateModelInference(
                            gunshotConfidence = result.gunshotConfidence,
                            topLabel = result.topLabel
                        )

                        // Track consecutive high-confidence frames
                        if (result.gunshotConfidence >= GUNSHOT_CONFIDENCE_THRESHOLD) {
                            consecutiveHighConfidence++
                        } else {
                            consecutiveHighConfidence = 0
                        }

                        // Trigger only if consecutive threshold met AND cooldown expired
                        if (consecutiveHighConfidence >= MIN_CONSECUTIVE_DETECTIONS &&
                            cooldownRemaining == 0L) {
                            Log.w(
                                TAG,
                                "ML THREAT DETECTED! gunshot=${result.gunshotConfidence} " +
                                "consecutive=$consecutiveHighConfidence top=${result.topLabel}"
                            )
                            lastTriggerTime = currentTime
                            consecutiveHighConfidence = 0
                            SystemEventFlow.emitLocalTrigger()
                        }
                    }
                }
            } else if (readResult == AudioRecord.ERROR_INVALID_OPERATION) {
                Log.e(TAG, "AudioRecord read error: invalid operation")
                delay(100)
            } else if (readResult == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "AudioRecord read error: bad value")
                delay(100)
            }
        }
    }

    private fun calculateRMS(buffer: ShortArray, readSize: Int): Double {
        var sum = 0.0
        for (i in 0 until readSize) {
            sum += buffer[i].toDouble() * buffer[i].toDouble()
        }
        return sqrt(sum / readSize)
    }

    private fun calculateMaxAmplitude(buffer: ShortArray, readSize: Int): Int {
        var maxAmplitude = 0
        for (i in 0 until readSize) {
            val amplitude = abs(buffer[i].toInt())
            if (amplitude > maxAmplitude) {
                maxAmplitude = amplitude
            }
        }
        return maxAmplitude
    }

    private fun appendToRollingBuffer(
        source: ShortArray,
        size: Int,
        destination: FloatArray,
        writeIndex: Int
    ): Pair<Int, Boolean> {
        var index = writeIndex
        var wrapped = false
        val ratio = SAMPLE_RATE.toFloat() / MODEL_SAMPLE_RATE.toFloat()
        var sourcePos = 0f
        while (sourcePos < size) {
            val sourceIndex = sourcePos.toInt().coerceIn(0, size - 1)
            destination[index] = source[sourceIndex] / 32768f
            index++
            if (index >= destination.size) {
                index = 0
                wrapped = true
            }
            sourcePos += ratio
        }
        return index to wrapped
    }

    private fun snapshotModelInput(ringBuffer: FloatArray, writeIndex: Int): FloatArray {
        val output = FloatArray(MODEL_INPUT_SAMPLES)
        val tailSize = ringBuffer.size - writeIndex
        System.arraycopy(ringBuffer, writeIndex, output, 0, tailSize)
        System.arraycopy(ringBuffer, 0, output, tailSize, writeIndex)
        return output
    }

    private fun stopAudioRecording() {
        audioRecordingJob?.cancel()
        audioRecordingJob = null

        audioRecord?.let {
            try {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping audio recording", e)
            }
        }
        audioRecord = null
        
        // Reset all two-tier detector state and telemetry
        smoothedAmplitude = 0.0
        consecutiveHighConfidence = 0
        currentGateOpen = false
        
        SystemEventFlow.updateAmplitude(0.0)
        SystemEventFlow.updateSmoothedAmplitude(0.0)
        SystemEventFlow.updateGateOpen(false)
        SystemEventFlow.updateCooldownRemaining(0L)
        SystemEventFlow.updateModelInference(0f, "idle")
        Log.d(TAG, "Audio recording stopped")
    }
}
