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
import com.echoshield.echonode.core.Triangulation
import com.echoshield.echonode.data.MeshNetworkManager
import com.echoshield.echonode.data.SystemEventFlow
import com.echoshield.echonode.sensor.LocationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
        
        // Gate threshold is read from SystemEventFlow.detectionThreshold (default 200).
        // Tune higher to reduce false gate opens from speech/ambient noise.
        // Typical quiet room ~50-100 RMS, speech ~200-500, clap/loud ~800+.
        
        // PEAK detection: Gunshots are impulsive sounds with high peak-to-RMS ratio.
        // Use peak amplitude (max sample) as alternative gate trigger.
        // Normalized peaks > 0.3 (~9800 raw) often indicate impulsive transients.
        private const val PEAK_THRESHOLD_NORMALIZED = 0.25f
        
        // If peak/RMS ratio is high, the sound is likely impulsive (like a gunshot)
        private const val IMPULSIVE_PEAK_RMS_RATIO = 3.0f

        // CLIPPING: Cheap phone mics saturate on nearby gunshots (samples pin to
        // ±32767). A sample is "clipped" when its magnitude is within CLIP_MARGIN of
        // full scale. Two thresholds:
        //  - CLIP_FRACTION_GATE: enough saturation to treat the frame as an impulsive
        //    transient and open the activity gate (a strong gunshot cue).
        //  - CLIP_FRACTION_SEVERE: so much saturation the waveform is destroyed and
        //    YAMNet confidence is unreliable; we relax the ML threshold so a real,
        //    close shot that clips the mic is not missed.
        private const val CLIP_MARGIN = 64                 // within ~0.2% of full scale
        private const val CLIP_FRACTION_GATE = 0.005f      // 0.5% of samples clipped
        private const val CLIP_FRACTION_SEVERE = 0.02f     // 2% of samples clipped
        // ML threshold applied when audio is severely clipped (lower = more sensitive).
        private const val CLIPPED_CONFIDENCE_THRESHOLD = 0.06f

        // TIER 2: ML confirmation
        // Gunshot confidence threshold. YAMNet typically outputs 0.0-1.0 for each class.
        // 0.10 is more sensitive - lowered from 0.15 since we need more positives.
        // Increase to 0.20-0.35 if getting too many false positives.
        private const val GUNSHOT_CONFIDENCE_THRESHOLD = 0.10f
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
        // MODEL SETTINGS (TFLite YAMNet expects 16kHz input, 3s window)
        // ─────────────────────────────────────────────────────────────────────
        private const val MODEL_SAMPLE_RATE = 16000
        private const val MODEL_INPUT_SAMPLES = LegacyTfLiteGunshotClassifier.INPUT_SAMPLES

        // ─────────────────────────────────────────────────────────────────────
        // RETROSPECTIVE AUDIO HISTORY
        //
        // A gunshot is over in milliseconds, but a peer's WAKE_CLASSIFY takes time
        // to cross the mesh. Classifying "the last second of audio" at the moment
        // the request arrives can therefore miss the shot entirely and vote NO on a
        // real event — which silently breaks consensus. Every node instead keeps
        // several seconds of audio so it can look *back* to the moment it heard the
        // impulse and classify that window.
        // ─────────────────────────────────────────────────────────────────────
        private const val HISTORY_SECONDS = 4
        private const val HISTORY_SAMPLES = MODEL_SAMPLE_RATE * HISTORY_SECONDS

        // How recently this node must have heard an impulse of its own for that to
        // count as its arrival of the event a peer is asking about.
        private const val IMPULSE_MEMORY_MS = 3000L

        // Peak level that marks a frame as containing an impulsive onset worth
        // timestamping for triangulation.
        private const val IMPULSE_ONSET_PEAK_NORMALIZED = 0.15f

        // Audio kept *after* the impulse onset in the classified window. Indoors the
        // report arrives smeared into a reverberant tail, and YAMNet keys on that
        // whole envelope, so the window is centred late rather than on the onset.
        private const val WINDOW_TAIL_MS = 500L

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
    private lateinit var legacyTfClassifier: LegacyTfLiteGunshotClassifier
    private var locationProvider: LocationProvider? = null
    @Volatile
    private var legacyTfReady = false
    
    // Two-tier detector state
    private var lastTriggerTime = 0L
    private var smoothedAmplitude = 0.0
    private var consecutiveHighConfidence = 0
    private var currentGateOpen = false

    // Sentinel duty state - controls whether this device captures audio at all
    @Volatile
    private var isSentinelActive = true
    @Volatile
    private var isProcessingWakeRequest = false
    @Volatile
    private var pendingWakeSessionId: String? = null
    private var dutyMonitorJob: Job? = null
    private var wakeRequestJob: Job? = null
    
    // Retrospective audio history, shared by the detection loop and by wake
    // classification. Written by the capture loop, read by classification on another
    // coroutine, so all access is under historyLock.
    private val historyLock = Any()
    private val historyBuffer = FloatArray(HISTORY_SAMPLES)
    private var historyWriteIndex = 0
    private var historySamplesWritten = 0L
    private var historyEndTimeMs = 0L

    /** Wall-clock time this node's microphone last heard an impulsive onset. */
    @Volatile
    private var lastImpulseAtMs = 0L

    /** Set once per wake request so a filling buffer cannot fire a burst of votes. */
    @Volatile
    private var wakeVoteDispatched = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        acquireWakeLock()
        meshNetworkManager = MeshNetworkManager.getInstance(applicationContext)
        legacyTfClassifier = LegacyTfLiteGunshotClassifier(applicationContext)
        locationProvider = LocationProvider(applicationContext)

        serviceScope.launch {
            legacyTfReady = legacyTfClassifier.initialize()
            Log.i(TAG, "Legacy TF classifier initialized: $legacyTfReady")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        
        startForegroundWithNotification()
        meshNetworkManager.startMesh()
        startDutyMonitoring()
        startWakeRequestHandling()
        
        // Only start audio recording if we're the sentinel
        // Duty monitoring will start/stop recording based on assignment
        val assignment = meshNetworkManager.dutyAssignment.value
        isSentinelActive = assignment.audioSentinelDuty
        if (isSentinelActive) {
            Log.i(TAG, "Starting as SENTINEL - audio capture active")
            startAudioRecording()
        } else {
            Log.i(TAG, "Starting as NON-SENTINEL - audio capture paused (battery saving)")
        }
        
        SystemEventFlow.setServiceRunning(true)
        
        return START_STICKY
    }

    private fun startDutyMonitoring() {
        if (dutyMonitorJob?.isActive == true) return

        dutyMonitorJob = serviceScope.launch(Dispatchers.Main) {
            meshNetworkManager.dutyAssignment.collect { assignment ->
                val wasActive = isSentinelActive
                isSentinelActive = assignment.audioSentinelDuty

                if (wasActive != isSentinelActive) {
                    Log.i(TAG, "Sentinel duty changed: active=$isSentinelActive (leader=${assignment.sentinelNodeId})")
                    
                    if (isSentinelActive) {
                        // Became sentinel - start audio capture
                        startAudioRecording()
                    } else {
                        // No longer sentinel - stop audio capture to save battery
                        stopAudioRecording()
                        currentGateOpen = false
                        consecutiveHighConfidence = 0
                        SystemEventFlow.updateGateOpen(false)
                    }
                }
            }
        }
    }

    private fun startWakeRequestHandling() {
        if (wakeRequestJob?.isActive == true) return

        wakeRequestJob = serviceScope.launch(Dispatchers.Main) {
            meshNetworkManager.wakeClassifyRequests.collect { request ->
                Log.i(TAG, "Received WAKE_CLASSIFY request: session=${request.sessionId}")
                handleWakeClassifyRequest(request.sessionId)
            }
        }
    }

    private fun handleWakeClassifyRequest(sessionId: String) {
        if (isProcessingWakeRequest) {
            Log.d(TAG, "Already processing wake request, skipping")
            return
        }

        // Already listening: the shot is in our history buffer, so answer from it.
        if (audioRecordingJob?.isActive == true && hasEnoughHistory()) {
            classifyAndVote(sessionId)
            return
        }

        // Not listening - start capture and vote as soon as we have a full window.
        // This node cannot contribute an arrival time (it was not listening when the
        // shot happened), so its vote counts toward consensus but not triangulation.
        isProcessingWakeRequest = true
        wakeVoteDispatched = false
        pendingWakeSessionId = sessionId
        resetHistory()

        Log.i(TAG, "Starting wake audio capture for session=$sessionId")
        // Ensure we have a position fix so this node's vote can contribute to
        // triangulation, even though non-sentinels normally keep location idle.
        locationProvider?.startLocationUpdates()
        startAudioRecording()
    }

    /**
     * Classify this node's *own* audio for a peer's detection session and vote.
     *
     * The window classified is the one ending at the moment this microphone last
     * heard an impulsive onset (if that was recent enough to be the same event),
     * not simply the latest audio — otherwise mesh latency would have us classifying
     * the silence *after* the shot. That same onset time is reported as this node's
     * arrival time, which is what makes TDoA triangulation possible.
     */
    private fun classifyAndVote(sessionId: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                isProcessingWakeRequest = true

                val voterLocation = locationProvider?.currentLocation?.value
                val voterLat = voterLocation?.latitude ?: Double.NaN
                val voterLon = voterLocation?.longitude ?: Double.NaN
                val voterAlt = voterLocation?.altitude ?: Double.NaN

                val now = System.currentTimeMillis()
                val impulseAt = lastImpulseAtMs
                val heardImpulse = impulseAt > 0L && now - impulseAt <= IMPULSE_MEMORY_MS
                // Give the model the tail of the impulse, not just its leading edge.
                val windowEnd = if (heardImpulse) impulseAt + WINDOW_TAIL_MS else now
                val arrivalAtMs = if (heardImpulse) impulseAt else Triangulation.NO_TIMING

                if (!legacyTfReady) {
                    Log.w(TAG, "No classifier ready for wake vote, voting NO for $sessionId")
                    meshNetworkManager.submitClassifyVote(
                        sessionId, false, 0f, voterLat, voterLon, voterAlt, Triangulation.NO_TIMING
                    )
                    return@launch
                }

                val modelInput = snapshotHistoryAt(windowEnd)
                if (modelInput == null) {
                    Log.w(TAG, "No audio history yet for $sessionId, voting NO")
                    meshNetworkManager.submitClassifyVote(
                        sessionId, false, 0f, voterLat, voterLon, voterAlt, Triangulation.NO_TIMING
                    )
                    return@launch
                }

                val result = classifyTfLite(modelInput)

                if (result != null) {
                    val isGunshot = result.gunshotConfidence >= GUNSHOT_CONFIDENCE_THRESHOLD
                    Log.i(
                        TAG,
                        "Wake classify result: gunshot=$isGunshot score=${result.gunshotConfidence} " +
                            "top=${result.topLabel} windowEnd=${now - windowEnd}ms ago heardImpulse=$heardImpulse"
                    )
                    meshNetworkManager.submitClassifyVote(
                        sessionId = sessionId,
                        isGunshot = isGunshot,
                        confidence = result.gunshotConfidence,
                        latitude = voterLat,
                        longitude = voterLon,
                        altitude = voterAlt,
                        // Only claim an arrival time when we actually heard something;
                        // a guessed timestamp would corrupt the triangulation.
                        detectedAtMs = if (isGunshot) arrivalAtMs else Triangulation.NO_TIMING
                    )
                    SystemEventFlow.updateModelInference(result.gunshotConfidence, result.topLabel)
                } else {
                    Log.w(TAG, "Classifier returned null for $sessionId")
                    meshNetworkManager.submitClassifyVote(
                        sessionId, false, 0f, voterLat, voterLon, voterAlt, Triangulation.NO_TIMING
                    )
                }
            } finally {
                isProcessingWakeRequest = false
                pendingWakeSessionId = null

                // If not sentinel, stop audio capture after classification
                if (!isSentinelActive) {
                    serviceScope.launch(Dispatchers.Main) {
                        stopAudioRecording()
                        Log.d(TAG, "Stopped wake audio capture (not sentinel)")
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        dutyMonitorJob?.cancel()
        dutyMonitorJob = null
        wakeRequestJob?.cancel()
        wakeRequestJob = null
        stopAudioRecording()
        meshNetworkManager.stopMesh()
        legacyTfClassifier.close()
        locationProvider?.stopLocationUpdates()
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
            .setSmallIcon(R.drawable.ic_notification)
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
        var lastAmplitudeUpdate = 0L
        var lastModelInference = 0L

        if (isSentinelActive) {
            locationProvider?.startLocationUpdates()
        }

        while (audioRecordingJob?.isActive == true) {
            val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: -1

            if (readResult > 0) {
                val currentTime = System.currentTimeMillis()

                val instantRms = calculateRMS(buffer, readResult)
                val peakIndex = indexOfMaxAmplitude(buffer, readResult)
                val peakAmplitude = abs(buffer[peakIndex].toInt())
                val peakNormalized = peakAmplitude / 32768f
                val clipFraction = calculateClipFraction(buffer, readResult)
                val clippingGate = clipFraction >= CLIP_FRACTION_GATE
                val clippingSevere = clipFraction >= CLIP_FRACTION_SEVERE

                // Every frame goes into the shared history, whether or not this node
                // is the sentinel, so a peer's wake request can be answered from the
                // audio at the moment of the shot.
                appendHistory(buffer, readResult, currentTime)

                // Timestamp the impulse itself, to sample accuracy within the frame:
                // sound covers 0.343 m per millisecond, so this is what triangulation
                // resolution ultimately rests on.
                if (peakNormalized >= IMPULSE_ONSET_PEAK_NORMALIZED || clippingGate) {
                    val frameStartMs = currentTime - (readResult * 1000L / SAMPLE_RATE)
                    lastImpulseAtMs = frameStartMs + (peakIndex * 1000L / SAMPLE_RATE)
                }

                // A woken non-sentinel votes as soon as it has a full window, once.
                if (isProcessingWakeRequest && !isSentinelActive) {
                    val sessionId = pendingWakeSessionId
                    if (!wakeVoteDispatched && sessionId != null && hasEnoughHistory()) {
                        wakeVoteDispatched = true
                        Log.i(TAG, "Wake capture window filled, classifying for session=$sessionId")
                        classifyAndVote(sessionId)
                    }
                    continue
                }

                if (!isSentinelActive) {
                    // Not sentinel and not processing a wake request: history only.
                    continue
                }

                // TIER 1: Apply EMA smoothing for stable gate decisions
                smoothedAmplitude = EMA_ALPHA * instantRms + (1 - EMA_ALPHA) * smoothedAmplitude

                val cooldownRemaining = maxOf(0L, TRIGGER_COOLDOWN_MS - (currentTime - lastTriggerTime))
                
                // Update telemetry at fixed interval
                if (currentTime - lastAmplitudeUpdate >= AMPLITUDE_UPDATE_INTERVAL_MS) {
                    SystemEventFlow.updateAmplitude(instantRms)
                    SystemEventFlow.updateSmoothedAmplitude(smoothedAmplitude)
                    SystemEventFlow.updateCooldownRemaining(cooldownRemaining)
                    SystemEventFlow.updateClipping(clippingGate, clipFraction)
                    lastAmplitudeUpdate = currentTime
                }

                // TIER 1: Activity gate check - multiple criteria for sensitivity
                // 1. RMS threshold: catches sustained loud sounds
                // 2. Peak threshold: catches impulsive transients (gunshots have high peaks)
                // 3. Impulsive ratio: high peak-to-RMS ratio indicates transient sounds
                val gateThreshold = SystemEventFlow.detectionThreshold.value
                val rmsGateOpen = instantRms >= gateThreshold
                val peakGateOpen = peakNormalized >= PEAK_THRESHOLD_NORMALIZED
                val impulsiveSound = instantRms > 50 && (peakAmplitude / instantRms) > IMPULSIVE_PEAK_RMS_RATIO

                // Open gate if ANY of the criteria are met (more sensitive).
                // Mic clipping is itself a strong impulsive cue for a nearby gunshot.
                val gateOpen = (rmsGateOpen || peakGateOpen || impulsiveSound || clippingGate) && hasEnoughHistory()
                
                // Update gate state telemetry if changed
                if (gateOpen != currentGateOpen) {
                    currentGateOpen = gateOpen
                    SystemEventFlow.updateGateOpen(gateOpen)
                    if (gateOpen) {
                        val reason = when {
                            rmsGateOpen -> "RMS"
                            peakGateOpen -> "PEAK"
                            impulsiveSound -> "IMPULSIVE"
                            clippingGate -> "CLIP"
                            else -> "?"
                        }
                        Log.d(TAG, "Activity gate OPENED [$reason] rms=${String.format("%.1f", instantRms)} peak=${"%.3f".format(peakNormalized)} clip=${"%.3f".format(clipFraction)} thresh=$gateThreshold")
                    } else {
                        Log.d(TAG, "Activity gate CLOSED")
                        consecutiveHighConfidence = 0
                    }
                }

                // TIER 2: ML inference only when gate is open, classifier ready, and rate limit allows
                val shouldRunModel = gateOpen && legacyTfReady &&
                    (currentTime - lastModelInference >= MODEL_INFERENCE_INTERVAL_MS)

                if (shouldRunModel) {
                    lastModelInference = currentTime
                    val modelInput = snapshotHistoryAt(currentTime)
                    val result = if (modelInput == null) null else classifyTfLite(modelInput)

                    if (result != null) {
                        // Log every inference for debugging detection issues.
                        // When the mic is severely clipped the ML input waveform is
                        // distorted, so relax the threshold to avoid missing a real
                        // close-range shot that saturated the microphone.
                        val effectiveThreshold =
                            if (clippingSevere) CLIPPED_CONFIDENCE_THRESHOLD else GUNSHOT_CONFIDENCE_THRESHOLD
                        val isAboveThreshold = result.gunshotConfidence >= effectiveThreshold
                        Log.d(TAG, "ML: score=${"%.3f".format(result.gunshotConfidence)} " +
                            "(thresh=$effectiveThreshold clip=${"%.3f".format(clipFraction)}, above=$isAboveThreshold) " +
                            "top=${result.topLabel}@${"%.3f".format(result.topScore)}")
                        
                        SystemEventFlow.updateModelInference(
                            gunshotConfidence = result.gunshotConfidence,
                            topLabel = result.topLabel
                        )

                        // Track consecutive high-confidence frames
                        if (isAboveThreshold) {
                            consecutiveHighConfidence++
                            Log.i(TAG, "HIGH CONFIDENCE FRAME #$consecutiveHighConfidence")
                        } else {
                            consecutiveHighConfidence = 0
                        }

                        // Trigger only if consecutive threshold met AND cooldown expired
                        if (consecutiveHighConfidence >= MIN_CONSECUTIVE_DETECTIONS &&
                            cooldownRemaining == 0L) {
                            Log.w(
                                TAG,
                                "SENTINEL DETECTED THREAT! score=${result.gunshotConfidence} " +
                                "consecutive=$consecutiveHighConfidence top=${result.topLabel}"
                            )
                            lastTriggerTime = currentTime
                            consecutiveHighConfidence = 0
                            
                            // Get current location
                            val location = locationProvider?.currentLocation?.value
                            val lat = location?.latitude ?: 0.0
                            val lon = location?.longitude ?: 0.0
                            val alt = location?.altitude ?: Double.NaN

                            val connectedPeers = meshNetworkManager.getConnectedPeerIds().size
                            // Only handoff sentinel duty when peers are available. On a solo
                            // device, disarming creates a long idle period during testing.
                            if (connectedPeers > 0) {
                                meshNetworkManager.disarmSentinel()
                            } else {
                                Log.i(TAG, "Threat detected with no peers; keeping local sentinel active")
                            }

                            // Report when *this* microphone heard the impulse, and our
                            // real classifier score rather than a blanket 1.0 — the node
                            // that fires first is the one most likely to be wrong, so it
                            // must not dominate the triangulated fix.
                            val impulseAt = lastImpulseAtMs
                            val arrivalAtMs = if (impulseAt > 0L && currentTime - impulseAt <= IMPULSE_MEMORY_MS) {
                                impulseAt
                            } else {
                                currentTime
                            }
                            meshNetworkManager.broadcastWakeClassify(
                                latitude = lat,
                                longitude = lon,
                                confidence = result.gunshotConfidence,
                                altitude = alt,
                                detectedAtMs = arrivalAtMs
                            )

                            Log.i(
                                TAG,
                                "WAKE_CLASSIFY broadcast at ($lat, $lon), peers=$connectedPeers " +
                                    "conf=${result.gunshotConfidence} arrival=${currentTime - arrivalAtMs}ms ago"
                            )
                            
                            // Note: Audio recording will stop when duty assignment updates
                            // and isSentinelActive becomes false
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

    /**
     * Fraction of samples in this frame that are saturated (pinned within CLIP_MARGIN
     * of the 16-bit full-scale rails). A high value means the microphone clipped.
     */
    private fun calculateClipFraction(buffer: ShortArray, readSize: Int): Float {
        if (readSize <= 0) return 0f
        val clipHigh = Short.MAX_VALUE - CLIP_MARGIN
        val clipLow = Short.MIN_VALUE + CLIP_MARGIN
        var clipped = 0
        for (i in 0 until readSize) {
            val sample = buffer[i].toInt()
            if (sample >= clipHigh || sample <= clipLow) {
                clipped++
            }
        }
        return clipped.toFloat() / readSize.toFloat()
    }

    /** Index of the loudest sample in the frame — the impulse onset, to sample accuracy. */
    private fun indexOfMaxAmplitude(buffer: ShortArray, readSize: Int): Int {
        var maxAmplitude = -1
        var maxIndex = 0
        for (i in 0 until readSize) {
            val amplitude = abs(buffer[i].toInt())
            if (amplitude > maxAmplitude) {
                maxAmplitude = amplitude
                maxIndex = i
            }
        }
        return maxIndex
    }

    /**
     * Resample a 44.1 kHz frame down to the model's 16 kHz and append it to the
     * history ring, stamping the frame's end time so windows can later be addressed
     * by wall-clock instead of by position.
     *
     * Each output sample is the mean of the input samples it spans rather than a
     * single picked sample: without that box filter, everything above 8 kHz aliases
     * back down into the band, and a gunshot is exactly the broadband impulse that
     * suffers most from it.
     */
    private fun appendHistory(source: ShortArray, size: Int, frameEndTimeMs: Long) {
        val ratio = SAMPLE_RATE.toDouble() / MODEL_SAMPLE_RATE.toDouble()
        synchronized(historyLock) {
            var index = historyWriteIndex
            var written = 0L
            var sourcePos = 0.0
            while (sourcePos < size) {
                val start = sourcePos.toInt()
                val end = ((sourcePos + ratio).toInt()).coerceAtMost(size)
                var sum = 0f
                var count = 0
                for (i in start until end) {
                    sum += source[i] / 32768f
                    count++
                }
                historyBuffer[index] = if (count > 0) sum / count else 0f
                index++
                if (index >= historyBuffer.size) index = 0
                written++
                sourcePos += ratio
            }
            historyWriteIndex = index
            historySamplesWritten += written
            historyEndTimeMs = frameEndTimeMs
        }
    }

    private fun hasEnoughHistory(): Boolean = synchronized(historyLock) {
        historySamplesWritten >= MODEL_INPUT_SAMPLES
    }

    private fun resetHistory() {
        synchronized(historyLock) {
            historyWriteIndex = 0
            historySamplesWritten = 0L
            historyEndTimeMs = 0L
        }
        lastImpulseAtMs = 0L
    }

    /**
     * A model-sized window of history ending at [endTimeMs] (wall clock). Older
     * requests are clamped to the oldest audio still retained, future ones to the
     * newest. Returns null until a full window has been captured.
     */
    private fun snapshotHistoryAt(endTimeMs: Long): FloatArray? {
        synchronized(historyLock) {
            if (historySamplesWritten < MODEL_INPUT_SAMPLES) return null

            val msBehind = (historyEndTimeMs - endTimeMs).coerceAtLeast(0L)
            val oldestOffset = minOf(
                historySamplesWritten - MODEL_INPUT_SAMPLES,
                (HISTORY_SAMPLES - MODEL_INPUT_SAMPLES).toLong()
            )
            val samplesBehind = (msBehind * MODEL_SAMPLE_RATE / 1000L).coerceIn(0L, oldestOffset)

            val end = Math.floorMod(historyWriteIndex - samplesBehind.toInt(), HISTORY_SAMPLES)
            val start = Math.floorMod(end - MODEL_INPUT_SAMPLES, HISTORY_SAMPLES)

            val output = FloatArray(MODEL_INPUT_SAMPLES)
            val firstChunk = minOf(MODEL_INPUT_SAMPLES, HISTORY_SAMPLES - start)
            System.arraycopy(historyBuffer, start, output, 0, firstChunk)
            if (firstChunk < MODEL_INPUT_SAMPLES) {
                System.arraycopy(historyBuffer, 0, output, firstChunk, MODEL_INPUT_SAMPLES - firstChunk)
            }
            return output
        }
    }

    private data class ClassificationResult(
        val gunshotConfidence: Float,
        val topLabel: String,
        val topScore: Float
    )

    private fun classifyTfLite(modelInput: FloatArray): ClassificationResult? {
        val legacy = if (legacyTfReady) legacyTfClassifier.classify(modelInput) else null
        if (legacy == null) return null
        return ClassificationResult(
            gunshotConfidence = legacy.gunshotConfidence.coerceIn(0f, 1f),
            topLabel = legacy.topLabel,
            topScore = legacy.topScore
        )
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
        
        resetHistory()
        wakeVoteDispatched = false
        
        SystemEventFlow.updateAmplitude(0.0)
        SystemEventFlow.updateSmoothedAmplitude(0.0)
        SystemEventFlow.updateGateOpen(false)
        SystemEventFlow.updateCooldownRemaining(0L)
        SystemEventFlow.updateModelInference(0f, "idle")
        SystemEventFlow.updateClipping(false, 0f)
        Log.d(TAG, "Audio recording stopped")
    }
}
