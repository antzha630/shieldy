package com.echoshield.echonode.sensor

import com.echoshield.echonode.core.contracts.DetectionTelemetry
import com.echoshield.echonode.core.contracts.SensorGateway
import com.echoshield.echonode.data.SystemEventFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SensorGatewayImpl : SensorGateway {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _localTriggerEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 32)
    override val localTriggerEvents: SharedFlow<Unit> = _localTriggerEvents.asSharedFlow()

    private val _telemetry = MutableStateFlow(
        DetectionTelemetry(
            amplitude = 0.0,
            smoothedAmplitude = 0.0,
            threshold = 450.0,
            gateOpen = false,
            modelGunshotConfidence = 0f,
            modelTopLabel = "unknown",
            cooldownRemainingMs = 0L,
            serviceRunning = false
        )
    )
    override val telemetry: StateFlow<DetectionTelemetry> = _telemetry.asStateFlow()

    init {
        scope.launch {
            SystemEventFlow.events.collect { event ->
                if (event.type == SystemEventFlow.EVENT_LOCAL_TRIGGER) {
                    _localTriggerEvents.emit(Unit)
                }
            }
        }
        scope.launch {
            SystemEventFlow.audioAmplitude.collect { amplitude ->
                _telemetry.value = _telemetry.value.copy(amplitude = amplitude)
            }
        }
        scope.launch {
            SystemEventFlow.smoothedAmplitude.collect { smoothed ->
                _telemetry.value = _telemetry.value.copy(smoothedAmplitude = smoothed)
            }
        }
        scope.launch {
            SystemEventFlow.detectionThreshold.collect { threshold ->
                _telemetry.value = _telemetry.value.copy(threshold = threshold)
            }
        }
        scope.launch {
            SystemEventFlow.gateOpen.collect { open ->
                _telemetry.value = _telemetry.value.copy(gateOpen = open)
            }
        }
        scope.launch {
            SystemEventFlow.modelGunshotConfidence.collect { confidence ->
                _telemetry.value = _telemetry.value.copy(modelGunshotConfidence = confidence)
            }
        }
        scope.launch {
            SystemEventFlow.modelTopLabel.collect { label ->
                _telemetry.value = _telemetry.value.copy(modelTopLabel = label)
            }
        }
        scope.launch {
            SystemEventFlow.cooldownRemainingMs.collect { remaining ->
                _telemetry.value = _telemetry.value.copy(cooldownRemainingMs = remaining)
            }
        }
        scope.launch {
            SystemEventFlow.isServiceRunning.collect { running ->
                _telemetry.value = _telemetry.value.copy(serviceRunning = running)
            }
        }
    }

    override fun setDetectionThreshold(threshold: Double) {
        SystemEventFlow.setDetectionThreshold(threshold)
    }
}
