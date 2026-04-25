package com.echoshield.echonode.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

object SystemEventFlow {
    
    private const val DEFAULT_THRESHOLD = 450.0

    const val EVENT_LOCAL_TRIGGER = "LOCAL_TRIGGER"
    const val EVENT_NETWORK_TRIGGER = "NETWORK_TRIGGER"
    const val EVENT_ALL_CLEAR = "ALL_CLEAR"
    const val EVENT_SERVICE_STARTED = "SERVICE_STARTED"
    const val EVENT_SERVICE_STOPPED = "SERVICE_STOPPED"

    private val _events = MutableSharedFlow<SystemEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SystemEvent> = _events.asSharedFlow()

    private val _audioAmplitude = MutableStateFlow(0.0)
    val audioAmplitude: StateFlow<Double> = _audioAmplitude.asStateFlow()

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _detectionThreshold = MutableStateFlow(DEFAULT_THRESHOLD)
    val detectionThreshold: StateFlow<Double> = _detectionThreshold.asStateFlow()

    private val _modelGunshotConfidence = MutableStateFlow(0f)
    val modelGunshotConfidence: StateFlow<Float> = _modelGunshotConfidence.asStateFlow()

    private val _modelTopLabel = MutableStateFlow("unknown")
    val modelTopLabel: StateFlow<String> = _modelTopLabel.asStateFlow()

    suspend fun emit(eventType: String, payload: String? = null) {
        _events.emit(SystemEvent(eventType, payload, System.currentTimeMillis()))
    }

    suspend fun emitLocalTrigger() {
        emit(EVENT_LOCAL_TRIGGER)
    }

    suspend fun emitNetworkTrigger(sourceEndpoint: String? = null) {
        emit(EVENT_NETWORK_TRIGGER, sourceEndpoint)
    }

    suspend fun emitAllClear() {
        emit(EVENT_ALL_CLEAR)
    }

    fun updateAmplitude(amplitude: Double) {
        _audioAmplitude.value = amplitude
    }

    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
    }

    fun setDetectionThreshold(threshold: Double) {
        _detectionThreshold.value = threshold
    }

    fun resetThreshold() {
        _detectionThreshold.value = DEFAULT_THRESHOLD
    }

    fun updateModelInference(gunshotConfidence: Float, topLabel: String) {
        _modelGunshotConfidence.value = gunshotConfidence
        _modelTopLabel.value = topLabel
    }

    data class SystemEvent(
        val type: String,
        val payload: String?,
        val timestamp: Long
    )
}
