package com.echoshield.echonode.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.echoshield.echonode.data.MeshNetworkManager
import com.echoshield.echonode.data.SystemEventFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    enum class AppState {
        LISTENING,
        BARRICADE,
        EVACUATE
    }

    data class UiState(
        val appState: AppState = AppState.LISTENING,
        val connectedPeers: Int = 0,
        val currentAmplitude: Double = 0.0,
        val detectionThreshold: Double = 450.0,
        val modelGunshotConfidence: Float = 0f,
        val modelTopLabel: String = "unknown",
        val isServiceRunning: Boolean = false,
        val meshStatus: MeshNetworkManager.MeshStatus = MeshNetworkManager.MeshStatus.IDLE,
        val evacuationRoute: String = "EXIT 4",
        val threatZone: String = "NORTH HALL"
    )

    private val meshNetworkManager: MeshNetworkManager = MeshNetworkManager.getInstance(application)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        observeSystemEvents()
        observeMeshNetwork()
        observeAudioAmplitude()
        observeServiceStatus()
    }

    private fun observeSystemEvents() {
        viewModelScope.launch {
            SystemEventFlow.events.collect { event ->
                Log.d(TAG, "System event received: ${event.type}")
                when (event.type) {
                    SystemEventFlow.EVENT_LOCAL_TRIGGER -> {
                        handleLocalTrigger()
                    }
                    SystemEventFlow.EVENT_NETWORK_TRIGGER -> {
                        handleNetworkTrigger(event.payload)
                    }
                    SystemEventFlow.EVENT_ALL_CLEAR -> {
                        resetToListening()
                    }
                }
            }
        }
    }

    private fun observeMeshNetwork() {
        viewModelScope.launch {
            meshNetworkManager.connectedPeerCount.collect { count ->
                _uiState.value = _uiState.value.copy(connectedPeers = count)
            }
        }

        viewModelScope.launch {
            meshNetworkManager.meshStatus.collect { status ->
                _uiState.value = _uiState.value.copy(meshStatus = status)
            }
        }

        viewModelScope.launch {
            meshNetworkManager.incomingPayloads.collect { payload ->
                Log.d(TAG, "Mesh payload received: $payload")
                handleMeshPayload(payload)
            }
        }
    }

    private fun observeAudioAmplitude() {
        viewModelScope.launch {
            SystemEventFlow.audioAmplitude.collect { amplitude ->
                _uiState.value = _uiState.value.copy(currentAmplitude = amplitude)
            }
        }

        viewModelScope.launch {
            SystemEventFlow.detectionThreshold.collect { threshold ->
                _uiState.value = _uiState.value.copy(detectionThreshold = threshold)
            }
        }

        viewModelScope.launch {
            SystemEventFlow.modelGunshotConfidence.collect { confidence ->
                _uiState.value = _uiState.value.copy(modelGunshotConfidence = confidence)
            }
        }

        viewModelScope.launch {
            SystemEventFlow.modelTopLabel.collect { label ->
                _uiState.value = _uiState.value.copy(modelTopLabel = label)
            }
        }
    }

    private fun observeServiceStatus() {
        viewModelScope.launch {
            SystemEventFlow.isServiceRunning.collect { running ->
                _uiState.value = _uiState.value.copy(isServiceRunning = running)
            }
        }
    }

    private fun handleMeshPayload(payload: String) {
        when {
            payload.startsWith(MeshNetworkManager.PAYLOAD_THREAT_DETECTED) -> {
                transitionToBarricade()
            }
            payload.startsWith(MeshNetworkManager.PAYLOAD_ALL_CLEAR) -> {
                resetToListening()
            }
            payload.startsWith(MeshNetworkManager.PAYLOAD_EVACUATE) -> {
                val zone = payload.split("|").getOrNull(2) ?: "SOUTH - EXIT 4"
                transitionToEvacuate(zone)
            }
        }
    }

    private fun handleLocalTrigger() {
        Log.w(TAG, "Local trigger detected - transitioning to BARRICADE")
        transitionToBarricade()
        meshNetworkManager.broadcastAlert(_uiState.value.threatZone)
    }

    private fun handleNetworkTrigger(sourceEndpoint: String?) {
        Log.w(TAG, "Network trigger from: ${sourceEndpoint ?: "unknown"}")
        transitionToBarricade()
    }

    private fun transitionToBarricade() {
        _uiState.value = _uiState.value.copy(
            appState = AppState.BARRICADE
        )
    }

    private fun transitionToEvacuate(route: String = "EXIT 4") {
        _uiState.value = _uiState.value.copy(
            appState = AppState.EVACUATE,
            evacuationRoute = route
        )
    }

    private fun resetToListening() {
        _uiState.value = _uiState.value.copy(
            appState = AppState.LISTENING
        )
    }

    fun triggerManualDebugAlert() {
        Log.d(TAG, "Manual debug alert triggered")
        transitionToBarricade()
        meshNetworkManager.broadcastAlert(_uiState.value.threatZone)
    }

    fun toggleBarricadeEvacuate() {
        val currentState = _uiState.value.appState
        when (currentState) {
            AppState.BARRICADE -> transitionToEvacuate()
            AppState.EVACUATE -> transitionToBarricade()
            AppState.LISTENING -> transitionToBarricade()
        }
    }

    fun resetAlert() {
        resetToListening()
        meshNetworkManager.broadcastAllClear()
    }

    fun startMesh() {
        meshNetworkManager.startMesh()
    }

    fun stopMesh() {
        meshNetworkManager.stopMesh()
    }

    fun setDetectionThreshold(threshold: Double) {
        SystemEventFlow.setDetectionThreshold(threshold)
    }

    fun triggerEvacuate(route: String = "SOUTH - EXIT 4") {
        transitionToEvacuate(route)
        meshNetworkManager.broadcastEvacuate(route)
    }

    override fun onCleared() {
        super.onCleared()
    }
}
