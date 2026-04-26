package com.echoshield.echonode

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.echoshield.echonode.core.contracts.AppState
import com.echoshield.echonode.service.AudioSensorService
import com.echoshield.echonode.ui.BarricadeScreen
import com.echoshield.echonode.ui.DashboardScreen
import com.echoshield.echonode.ui.EvacuateScreen
import com.echoshield.echonode.ui.IncidentReportScreen
import com.echoshield.echonode.ui.PermissionRequestScreen
import com.echoshield.echonode.ui.theme.EchoNodeTheme
import com.echoshield.echonode.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            EchoNodeTheme {
                EchoShieldRoot()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity destroyed")
    }
}

@Composable
fun EchoShieldRoot() {
    val context = LocalContext.current
    val viewModel: MainViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()

    var allPermissionsGranted by remember {
        mutableStateOf(checkAllPermissions(context))
    }

    val requiredPermissions = requiredRuntimePermissions()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.d("Permissions", "Results: $permissions")
        allPermissionsGranted = permissions.values.all { it } || checkAllPermissions(context)
        
        if (allPermissionsGranted) {
            AudioSensorService.startService(context)
            viewModel.startMesh()
        }
    }

    LaunchedEffect(allPermissionsGranted) {
        if (allPermissionsGranted) {
            Log.d("EchoShield", "All permissions granted, starting services")
            AudioSensorService.startService(context)
            viewModel.startMesh()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            Log.d("EchoShield", "Disposing EchoShieldRoot")
        }
    }

    Crossfade(
        targetState = if (!allPermissionsGranted) "permissions" else uiState.appState.name,
        animationSpec = tween(300),
        label = "screenTransition"
    ) { targetScreen ->
        when (targetScreen) {
            "permissions" -> {
                PermissionRequestScreen(
                    onRequestPermissions = {
                        permissionLauncher.launch(requiredPermissions.toTypedArray())
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            AppState.LISTENING.name -> {
                DashboardScreen(
                    uiState = uiState,
                    onSimulateGunshot = { viewModel.triggerManualDebugAlert() },
                    onToggleEvacuate = { viewModel.triggerEvacuate() },
                    onThresholdChange = { viewModel.setDetectionThreshold(it) },
                    modifier = Modifier.fillMaxSize()
                )
            }
            AppState.LOCATION_CONFIRMATION.name -> {
                IncidentReportScreen(
                    locationLabel = uiState.locationLabel,
                    relativeLocation = uiState.relativeLocation,
                    locationTimestamp = uiState.locationTimestamp,
                    locationLatitude = uiState.locationLatitude,
                    locationLongitude = uiState.locationLongitude,
                    safetyStatus = uiState.safetyStatus,
                    connectedPeers = uiState.connectedPeers,
                    meshStatus = uiState.meshStatus,
                    threatZone = uiState.threatZone,
                    evacuationRoute = uiState.evacuationRoute,
                    companionsCount = uiState.companionsCount,
                    injuredCount = uiState.injuredCount,
                    roomNumber = uiState.roomNumber,
                    incidentNotes = uiState.incidentNotes,
                    onCompanionsChange = { count -> viewModel.setCompanionsCount(count) },
                    onInjuredChange = { count -> viewModel.setInjuredCount(count) },
                    onRoomNumberChange = { room -> viewModel.setRoomNumber(room) },
                    onNotesChange = { notes -> viewModel.setIncidentNotes(notes) },
                    onSubmit = { viewModel.submitIncidentReport() },
                    onBack = { viewModel.resetAlert() },
                    onQuickBarricade = { viewModel.quickBarricade() },
                    onQuickEvacuate = { viewModel.quickEvacuate() },
                    modifier = Modifier.fillMaxSize()
                )
            }
            AppState.SAFETY_CHECK.name -> {
                IncidentReportScreen(
                    locationLabel = uiState.locationLabel,
                    relativeLocation = uiState.relativeLocation,
                    locationTimestamp = uiState.locationTimestamp,
                    locationLatitude = uiState.locationLatitude,
                    locationLongitude = uiState.locationLongitude,
                    safetyStatus = uiState.safetyStatus,
                    connectedPeers = uiState.connectedPeers,
                    meshStatus = uiState.meshStatus,
                    threatZone = uiState.threatZone,
                    evacuationRoute = uiState.evacuationRoute,
                    companionsCount = uiState.companionsCount,
                    injuredCount = uiState.injuredCount,
                    roomNumber = uiState.roomNumber,
                    incidentNotes = uiState.incidentNotes,
                    onCompanionsChange = { count -> viewModel.setCompanionsCount(count) },
                    onInjuredChange = { count -> viewModel.setInjuredCount(count) },
                    onRoomNumberChange = { room -> viewModel.setRoomNumber(room) },
                    onNotesChange = { notes -> viewModel.setIncidentNotes(notes) },
                    onSubmit = { viewModel.submitIncidentReport() },
                    onBack = { viewModel.resetAlert() },
                    onQuickBarricade = { viewModel.quickBarricade() },
                    onQuickEvacuate = { viewModel.quickEvacuate() },
                    modifier = Modifier.fillMaxSize()
                )
            }
            AppState.INCIDENT_REPORT.name -> {
                IncidentReportScreen(
                    locationLabel = uiState.locationLabel,
                    relativeLocation = uiState.relativeLocation,
                    locationTimestamp = uiState.locationTimestamp,
                    locationLatitude = uiState.locationLatitude,
                    locationLongitude = uiState.locationLongitude,
                    safetyStatus = uiState.safetyStatus,
                    connectedPeers = uiState.connectedPeers,
                    meshStatus = uiState.meshStatus,
                    threatZone = uiState.threatZone,
                    evacuationRoute = uiState.evacuationRoute,
                    companionsCount = uiState.companionsCount,
                    injuredCount = uiState.injuredCount,
                    roomNumber = uiState.roomNumber,
                    incidentNotes = uiState.incidentNotes,
                    onCompanionsChange = { count -> viewModel.setCompanionsCount(count) },
                    onInjuredChange = { count -> viewModel.setInjuredCount(count) },
                    onRoomNumberChange = { room -> viewModel.setRoomNumber(room) },
                    onNotesChange = { notes -> viewModel.setIncidentNotes(notes) },
                    onSubmit = { viewModel.submitIncidentReport() },
                    onBack = { viewModel.goBackToSafetyCheck() },
                    onQuickBarricade = { viewModel.quickBarricade() },
                    onQuickEvacuate = { viewModel.quickEvacuate() },
                    modifier = Modifier.fillMaxSize()
                )
            }
            AppState.BARRICADE.name -> {
                BarricadeScreen(
                    threatZone = uiState.threatZone,
                    onToggle = { viewModel.toggleBarricadeEvacuate() },
                    onReset = { viewModel.resetAlert() },
                    modifier = Modifier.fillMaxSize()
                )
            }
            AppState.EVACUATE.name -> {
                EvacuateScreen(
                    evacuationRoute = uiState.evacuationRoute,
                    onToggle = { viewModel.toggleBarricadeEvacuate() },
                    onReset = { viewModel.resetAlert() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

private fun checkAllPermissions(context: android.content.Context): Boolean {
    return requiredRuntimePermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

private fun requiredRuntimePermissions(): List<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_SCAN)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}
