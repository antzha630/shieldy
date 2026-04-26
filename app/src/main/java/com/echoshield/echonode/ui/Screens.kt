package com.echoshield.echonode.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.echoshield.echonode.core.contracts.MeshStatus
import com.echoshield.echonode.core.contracts.SafetyStatus
import com.echoshield.echonode.core.contracts.ThreatZone
import com.echoshield.echonode.core.contracts.ConversationMessage
import com.echoshield.echonode.viewmodel.MainViewModel

private val LightBackground = Color(0xFFFFFFFF)
private val CardWhite = Color.White
private val PrimaryBlue = Color(0xFF2F6BDE)
private val DarkText = Color(0xFF111111)
private val SecondaryText = Color(0xFF55637D)
private val AccentGreen = Color(0xFF0D9F46)
private val AccentYellow = Color(0xFFC79200)
private val AccentRed = Color(0xFFDC3545)
private val TrackGray = Color(0xFFD5DAE4)
private const val INCIDENT_MAP_DEFAULT_ZOOM = 14f

@Composable
fun DashboardScreen(
    uiState: MainViewModel.UiState,
    onSimulateGunshot: () -> Unit,
    onToggleEvacuate: () -> Unit,
    onThresholdChange: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    var showOptions by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LightBackground)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FlowHeader(
            title = "EchoShield",
            showBackArrow = false,
            onBack = {},
            onCancel = {}
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CardWhite)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(120.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .alpha(pulseAlpha * 0.3f)
                            .clip(CircleShape)
                            .background(AccentGreen.copy(alpha = 0.2f))
                    )
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(AccentGreen)
                            .border(3.dp, AccentGreen.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (uiState.isServiceRunning) "✓" else "○",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = CardWhite
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = if (uiState.isServiceRunning) "Monitoring Active" else "Standby",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkText
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = when (uiState.meshStatus) {
                        MeshStatus.CONNECTED -> "${uiState.connectedPeers} peers connected"
                        MeshStatus.DISCOVERING -> "Searching for peers..."
                        MeshStatus.ADVERTISING -> "Broadcasting..."
                        MeshStatus.ERROR -> "Connection error"
                        MeshStatus.IDLE -> "Mesh idle"
                    },
                    fontSize = 14.sp,
                    color = SecondaryText
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardWhite)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Audio Level",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = DarkText
                    )
                    Text(
                        text = if (uiState.gateOpen) "ACTIVE" else "IDLE",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (uiState.gateOpen) AccentGreen else SecondaryText
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                LinearProgressIndicator(
                    progress = (uiState.currentAmplitude / uiState.detectionThreshold).toFloat().coerceIn(0f, 1f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = if (uiState.currentAmplitude > uiState.detectionThreshold * 0.7) AccentRed else PrimaryBlue,
                    trackColor = TrackGray
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "ML: ${uiState.modelTopLabel}",
                        fontSize = 12.sp,
                        color = SecondaryText
                    )
                    Text(
                        text = String.format("%.0f / %.0f", uiState.currentAmplitude, uiState.detectionThreshold),
                        fontSize = 12.sp,
                        color = SecondaryText
                    )
                }

                if (uiState.cooldownRemainingMs > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Cooldown: ${uiState.cooldownRemainingMs / 1000}s",
                        fontSize = 11.sp,
                        color = AccentYellow
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onSimulateGunshot,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                text = "Test Alert",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = CardWhite
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = { showOptions = !showOptions },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                text = if (showOptions) "Hide Options" else "Options",
                fontWeight = FontWeight.SemiBold,
                color = PrimaryBlue
            )
        }

        AnimatedVisibility(
            visible = showOptions,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardWhite)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Detection Threshold: ${uiState.detectionThreshold.toInt()}",
                        fontSize = 13.sp,
                        color = SecondaryText
                    )
                    Slider(
                        value = uiState.detectionThreshold.toFloat(),
                        onValueChange = { onThresholdChange(it.toDouble()) },
                        valueRange = 100f..1500f,
                        colors = SliderDefaults.colors(
                            thumbColor = PrimaryBlue,
                            activeTrackColor = PrimaryBlue
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onToggleEvacuate,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Test Evacuate", color = AccentGreen)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun LocationConfirmationScreen(
    locationLabel: String,
    relativeLocation: String,
    coordinateText: String,
    locationTimestamp: String,
    locationLatitude: Double,
    locationLongitude: Double,
    onConfirm: (Boolean) -> Unit,
    onQuickBarricade: () -> Unit,
    onQuickEvacuate: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedYes by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LightBackground)
            .padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            FlowHeader(
                title = "Location",
                step = 1,
                totalSteps = 3,
                showBackArrow = true,
                onBack = onCancel,
                onCancel = onCancel
            )

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFD4E4F7))
            ) {
                val hasLocation = locationLatitude != 0.0 || locationLongitude != 0.0
                if (hasLocation) {
                    val currentLatLng = LatLng(locationLatitude, locationLongitude)
                    val cameraPositionState = rememberCameraPositionState {
                        position = CameraPosition.fromLatLngZoom(currentLatLng, 17f)
                    }
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = cameraPositionState,
                        properties = MapProperties(isMyLocationEnabled = false)
                    ) {
                        Marker(
                            state = MarkerState(position = currentLatLng),
                            title = "Your location",
                            snippet = relativeLocation.ifBlank { locationLabel }
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📍", fontSize = 40.sp)
                            Text(
                                text = "Waiting for GPS...",
                                fontSize = 14.sp,
                                color = SecondaryText
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardWhite)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Your Location",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PrimaryBlue
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📍", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = locationLabel.ifBlank { "Detecting location..." },
                            fontSize = 15.sp,
                            color = DarkText
                        )
                    }

                    if (locationTimestamp.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🕐", fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = locationTimestamp,
                                fontSize = 14.sp,
                                color = SecondaryText
                            )
                        }
                    }

                    if (relativeLocation.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🏢", fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = relativeLocation,
                                fontSize = 14.sp,
                                color = SecondaryText
                            )
                        }
                    }

                    if (coordinateText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🧭", fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = coordinateText,
                                fontSize = 14.sp,
                                color = SecondaryText
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SelectableChip(
                            text = "Yes",
                            selected = selectedYes,
                            selectedColor = AccentGreen,
                            onClick = { selectedYes = true },
                            modifier = Modifier.weight(1f)
                        )
                        SelectableChip(
                            text = "No",
                            selected = !selectedYes,
                            selectedColor = AccentRed,
                            onClick = { selectedYes = false },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = { onConfirm(selectedYes) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Confirm Location", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        EmergencyQuickActions(
            onQuickBarricade = onQuickBarricade,
            onQuickEvacuate = onQuickEvacuate
        )
    }
}

@Composable
fun SafetyCheckScreen(
    selectedStatus: SafetyStatus,
    onStatusSelected: (SafetyStatus) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onQuickBarricade: () -> Unit,
    onQuickEvacuate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LightBackground)
            .padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            FlowHeader(
                title = "Safety Check",
                step = 2,
                totalSteps = 3,
                showBackArrow = true,
                onBack = onBack,
                onCancel = onBack
            )

            Spacer(modifier = Modifier.height(40.dp))

            Text(
                text = "Are you and everyone\nelse safe?",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = DarkText,
                lineHeight = 36.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(32.dp))

            SafetyOptionButton(
                emoji = "✅",
                label = "Yes, we're OK",
                selected = selectedStatus == SafetyStatus.SAFE,
                selectedColor = AccentGreen,
                onClick = { onStatusSelected(SafetyStatus.SAFE) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            SafetyOptionButton(
                emoji = "⚠️",
                label = "Someone is injured",
                selected = selectedStatus == SafetyStatus.INJURED,
                selectedColor = AccentYellow,
                onClick = { onStatusSelected(SafetyStatus.INJURED) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            SafetyOptionButton(
                emoji = "🤔",
                label = "Not sure",
                selected = selectedStatus == SafetyStatus.UNKNOWN,
                selectedColor = SecondaryText,
                onClick = { onStatusSelected(SafetyStatus.UNKNOWN) }
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onNext,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedStatus != SafetyStatus.UNKNOWN) PrimaryBlue else TrackGray
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Next", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("→", fontSize = 18.sp)
                }
            }
        }

        EmergencyQuickActions(
            onQuickBarricade = onQuickBarricade,
            onQuickEvacuate = onQuickEvacuate
        )
    }
}

@Composable
fun IncidentReportScreen(
    locationLabel: String,
    relativeLocation: String,
    locationTimestamp: String,
    locationLatitude: Double,
    locationLongitude: Double,
    safetyStatus: SafetyStatus,
    connectedPeers: Int,
    meshStatus: MeshStatus,
    threatZone: String,
    evacuationRoute: String,
    threatLatitude: Double,
    threatLongitude: Double,
    threatRadiusMeters: Double,
    threatZones: List<ThreatZone>,
    serverRecommendedAction: String,
    liveUpdates: List<String>,
    conversationMessages: List<ConversationMessage>,
    companionsCount: Int,
    injuredCount: Int,
    roomNumber: String,
    incidentNotes: String,
    onCompanionsChange: (Int) -> Unit,
    onInjuredChange: (Int) -> Unit,
    onRoomNumberChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSendChat: () -> Unit,
    onBack: () -> Unit,
    onQuickBarricade: () -> Unit,
    onQuickEvacuate: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(IncidentTab.MAP) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LightBackground)
            .padding(20.dp)
    ) {
        FlowHeader(
            title = "Incident Response",
            showBackArrow = true,
            showCancel = false,
            onBack = onBack,
            onCancel = onBack
        )

        Spacer(modifier = Modifier.height(12.dp))

        TabRow(
            selectedTabIndex = selectedTab.ordinal,
            containerColor = CardWhite,
            contentColor = PrimaryBlue
        ) {
            IncidentTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = {
                        Text(
                            tab.label,
                            fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                IncidentTab.MAP -> IncidentMapTab(
                    locationLabel = locationLabel,
                    relativeLocation = relativeLocation,
                    locationTimestamp = locationTimestamp,
                    locationLatitude = locationLatitude,
                    locationLongitude = locationLongitude,
                    connectedPeers = connectedPeers,
                    meshStatus = meshStatus,
                    threatZone = threatZone,
                    evacuationRoute = evacuationRoute,
                    threatLatitude = threatLatitude,
                    threatLongitude = threatLongitude,
                    threatRadiusMeters = threatRadiusMeters,
                    threatZones = threatZones,
                    serverRecommendedAction = serverRecommendedAction,
                    liveUpdates = liveUpdates
                )
                IncidentTab.CHAT -> IncidentChatTab(
                    meshStatus = meshStatus,
                    incidentNotes = incidentNotes,
                    conversationMessages = conversationMessages,
                    onNotesChange = onNotesChange,
                    onSend = onSendChat
                )
                IncidentTab.STATUS -> IncidentStatusTab(
                    companionsCount = companionsCount,
                    injuredCount = injuredCount,
                    roomNumber = roomNumber,
                    safetyStatus = safetyStatus,
                    onCompanionsChange = onCompanionsChange,
                    onInjuredChange = onInjuredChange,
                    onRoomNumberChange = onRoomNumberChange,
                    onSubmit = onSubmit
                )
            }
        }

    }
}

private enum class IncidentTab(val label: String) {
    MAP("Map"),
    CHAT("Chat"),
    STATUS("Status")
}

@Composable
private fun IncidentMapTab(
    locationLabel: String,
    relativeLocation: String,
    locationTimestamp: String,
    locationLatitude: Double,
    locationLongitude: Double,
    connectedPeers: Int,
    meshStatus: MeshStatus,
    threatZone: String,
    evacuationRoute: String,
    threatLatitude: Double,
    threatLongitude: Double,
    threatRadiusMeters: Double,
    threatZones: List<ThreatZone>,
    serverRecommendedAction: String,
    liveUpdates: List<String>
) {
    val scrollState = rememberScrollState()
    val hasCoordinates = locationLatitude != 0.0 || locationLongitude != 0.0
    val userPoint = if (hasCoordinates) LatLng(locationLatitude, locationLongitude) else null
    val dynamicZones = threatZones.ifEmpty {
        if (threatLatitude != 0.0 || threatLongitude != 0.0) {
            listOf(ThreatZone(latitude = threatLatitude, longitude = threatLongitude,
                radiusMeters = threatRadiusMeters, confidence = 0.7f, source = "fallback"))
        } else emptyList()
    }

    val threatCenter = dynamicZones.firstOrNull()?.let { LatLng(it.latitude, it.longitude) }
        ?: if (threatLatitude != 0.0 || threatLongitude != 0.0) {
            LatLng(threatLatitude, threatLongitude)
        } else null
    val asherZones = threatCenter?.let { center ->
        buildAsherZones(
            center = center,
            baseHotRadiusMeters = dynamicZones.firstOrNull()?.radiusMeters ?: threatRadiusMeters
        )
    }
    val escapeTargetSeed = if (dynamicZones.isNotEmpty()) {
        dynamicZones
    } else {
        asherZones?.let {
            listOf(
                ThreatZone(
                    latitude = it.center.latitude,
                    longitude = it.center.longitude,
                    radiusMeters = it.warmRadiusMeters,
                    confidence = 0.7f,
                    source = "asher-hotspot"
                )
            )
        } ?: emptyList()
    }
    val escapeTarget = userPoint?.let { computeEscapeTarget(it, escapeTargetSeed) }
    val zoneClass = when {
        userPoint == null || asherZones == null -> AsherZoneClass.UNKNOWN
        else -> classifyAsherZone(userPoint, asherZones)
    }
    val isInsideThreat = zoneClass == AsherZoneClass.HOT
    val escapeLabel = if (userPoint != null && escapeTarget != null)
        mapEscapeDirectionLabel(userPoint, escapeTarget) else null

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Map ──────────────────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth().height(260.dp),
            colors = CardDefaults.cardColors(containerColor = CardWhite),
            shape = RoundedCornerShape(16.dp)
        ) {
            if (hasCoordinates && userPoint != null) {
                val cameraState = rememberCameraPositionState {
                    position = CameraPosition.fromLatLngZoom(userPoint, INCIDENT_MAP_DEFAULT_ZOOM)
                }
                val zoneKey = dynamicZones.firstOrNull()
                    ?.let { "${it.latitude},${it.longitude},${it.radiusMeters}" } ?: ""

                // Re-center on user every time their position or the primary threat changes.
                // Zoom out just enough to show the entire red threat circle.
                LaunchedEffect(locationLatitude, locationLongitude, zoneKey) {
                    val zoom = if (asherZones != null) {
                        val dist = mapDistanceM(userPoint, asherZones.center)
                        val maxM = (dist + asherZones.coldRadiusMeters).coerceAtLeast(60.0)
                        mapZoomForDist(maxM, locationLatitude)
                    } else INCIDENT_MAP_DEFAULT_ZOOM
                    kotlin.runCatching {
                        cameraState.animate(CameraUpdateFactory.newLatLngZoom(userPoint, zoom))
                    }
                }

                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraState,
                    properties = MapProperties(isMyLocationEnabled = false)
                ) {
                    // Blue dot – your current position
                    Marker(
                        state = MarkerState(position = userPoint),
                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE),
                        title = "You",
                        snippet = relativeLocation.ifBlank { locationLabel }
                    )

                    // NFPA 3000 ASHER zones:
                    // Hot (red), Warm (amber), Cold (green/blue) around threat center.
                    asherZones?.let { zones ->
                        Circle(
                            center = zones.center,
                            radius = zones.coldRadiusMeters,
                            fillColor = Color(0xFF2F6BDE).copy(alpha = 0.06f),
                            strokeColor = Color(0xFF2F6BDE).copy(alpha = 0.45f),
                            strokeWidth = 2f
                        )
                        Circle(
                            center = zones.center,
                            radius = zones.warmRadiusMeters,
                            fillColor = Color(0xFFF0A500).copy(alpha = 0.10f),
                            strokeColor = Color(0xFFF0A500).copy(alpha = 0.70f),
                            strokeWidth = 3f
                        )
                        Circle(
                            center = zones.center,
                            radius = zones.hotRadiusMeters,
                            fillColor = AccentRed.copy(alpha = 0.18f),
                            strokeColor = AccentRed.copy(alpha = 0.90f),
                            strokeWidth = 4f
                        )
                        Marker(
                            state = MarkerState(position = zones.center),
                            icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED),
                            title = "Threat Center (HOT ZONE)",
                            snippet = "Warm/Cold rings represent reduced threat exposure"
                        )
                    }

                    // Green directional arrow (shaft + V-shaped arrowhead)
                    if (escapeTarget != null) {
                        Polyline(
                            points = listOf(userPoint, escapeTarget),
                            color = AccentGreen,
                            width = 14f
                        )
                        mapArrowHeadLines(userPoint, escapeTarget).forEach { wing ->
                            Polyline(points = wing, color = AccentGreen, width = 14f)
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Map is active", color = DarkText, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = locationLabel.ifBlank { "Waiting for location update" },
                            color = SecondaryText,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // ── Proximity-based action card ───────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isInsideThreat) AccentRed.copy(alpha = 0.09f)
                                 else Color(0xFFE8F7EF)
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                when {
                    zoneClass == AsherZoneClass.HOT -> {
                        SectionLabel("⚠ BARRICADE NOW")
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "HOT ZONE: direct threat possible. Shelter in place, lock and barricade doors, stay low, and silence your phone.",
                            fontSize = 13.sp,
                            color = AccentRed
                        )
                    }
                    zoneClass == AsherZoneClass.WARM && escapeLabel != null -> {
                        SectionLabel(escapeLabel)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = evacuationRoute.ifBlank { "WARM ZONE: evacuate away from HOT center along the arrow direction." },
                            fontSize = 13.sp,
                            color = AccentGreen
                        )
                        if (serverRecommendedAction.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = serverRecommendedAction, fontSize = 12.sp, color = SecondaryText)
                        }
                    }
                    zoneClass == AsherZoneClass.COLD -> {
                        SectionLabel("COLD ZONE: REPORT / ALL CLEAR")
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "You are outside the primary danger rings. Continue moving to safety, report status/notes, and wait for official instructions.",
                            fontSize = 13.sp,
                            color = SecondaryText
                        )
                    }
                    else -> {
                        SectionLabel("Route Guidance")
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = evacuationRoute.ifBlank { "Awaiting confirmed route" },
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (evacuationRoute.isBlank()) SecondaryText else AccentGreen
                        )
                        if (threatZone.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(text = threatZone, fontSize = 13.sp, color = SecondaryText)
                        }
                    }
                }
                if (asherZones != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "ASHER zones active: HOT ${asherZones.hotRadiusMeters.toInt()}m · WARM ${asherZones.warmRadiusMeters.toInt()}m · COLD ${asherZones.coldRadiusMeters.toInt()}m",
                        fontSize = 11.sp,
                        color = AccentRed.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // ── Live updates from server authority messages ────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardWhite),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                SectionLabel("Live Updates")
                Spacer(modifier = Modifier.height(8.dp))
                if (liveUpdates.isEmpty()) {
                    LiveUpdateRow("Waiting for server updates...")
                } else {
                    liveUpdates.take(6).asReversed().forEach { update ->
                        LiveUpdateRow(update)
                    }
                }
            }
        }
    }
}

/** Haversine distance in metres between two LatLng points. */
private fun mapDistanceM(a: LatLng, b: LatLng): Double {
    val R = 6_371_000.0
    val dLat = kotlin.math.sin(kotlin.math.PI / 180.0 * (b.latitude - a.latitude) / 2.0)
    val dLon = kotlin.math.sin(kotlin.math.PI / 180.0 * (b.longitude - a.longitude) / 2.0)
    val h = dLat * dLat + kotlin.math.cos(kotlin.math.PI / 180.0 * a.latitude) *
            kotlin.math.cos(kotlin.math.PI / 180.0 * b.latitude) * dLon * dLon
    return R * 2.0 * kotlin.math.atan2(kotlin.math.sqrt(h), kotlin.math.sqrt(1.0 - h))
}

/** Zoom level that fits [maxDistMeters] within roughly 400 px of map height. */
private fun mapZoomForDist(maxDistMeters: Double, latDeg: Double): Float {
    val cosLat = kotlin.math.cos(kotlin.math.PI / 180.0 * latDeg).coerceAtLeast(0.01)
    val zoom = kotlin.math.log2(156_543.03 * cosLat * 400.0 / maxDistMeters) - 1.0
    return zoom.coerceIn(10.0, 18.0).toFloat()
}

/** Cardinal/intercardinal compass label with directional emoji for the escape bearing. */
private fun mapEscapeDirectionLabel(user: LatLng, target: LatLng): String {
    val dLon = kotlin.math.PI / 180.0 * (target.longitude - user.longitude)
    val lat1 = kotlin.math.PI / 180.0 * user.latitude
    val lat2 = kotlin.math.PI / 180.0 * target.latitude
    val y = kotlin.math.sin(dLon) * kotlin.math.cos(lat2)
    val x = kotlin.math.cos(lat1) * kotlin.math.sin(lat2) -
             kotlin.math.sin(lat1) * kotlin.math.cos(lat2) * kotlin.math.cos(dLon)
    val bearing = (kotlin.math.atan2(y, x) * 180.0 / kotlin.math.PI + 360.0) % 360.0
    return when {
        bearing < 22.5 || bearing >= 337.5 -> "↑ RUN NORTH"
        bearing < 67.5  -> "↗ RUN NORTHEAST"
        bearing < 112.5 -> "→ RUN EAST"
        bearing < 157.5 -> "↘ RUN SOUTHEAST"
        bearing < 202.5 -> "↓ RUN SOUTH"
        bearing < 247.5 -> "↙ RUN SOUTHWEST"
        bearing < 292.5 -> "← RUN WEST"
        else            -> "↖ RUN NORTHWEST"
    }
}

/**
 * Returns two polyline point-lists that form a V-shaped arrowhead at [to].
 * Works in degree-space (headLen ≈ 33 m at mid-latitudes).
 */
private fun mapArrowHeadLines(from: LatLng, to: LatLng): List<List<LatLng>> {
    val dLat = to.latitude - from.latitude
    val dLon = to.longitude - from.longitude
    val mag = kotlin.math.sqrt(dLat * dLat + dLon * dLon)
    if (mag < 1e-10) return emptyList()
    val uLat = -dLat / mag   // unit vector pointing back along the shaft
    val uLon = -dLon / mag
    val pLat = -uLon         // perpendicular unit vector
    val pLon = uLat
    val headLen = 0.00030    // ~33 m in degrees
    val sideLen = headLen * 0.45
    val left  = LatLng(to.latitude + uLat * headLen + pLat * sideLen,
                       to.longitude + uLon * headLen + pLon * sideLen)
    val right = LatLng(to.latitude + uLat * headLen - pLat * sideLen,
                       to.longitude + uLon * headLen - pLon * sideLen)
    return listOf(listOf(left, to), listOf(right, to))
}

private fun computeEscapeTarget(user: LatLng, zones: List<ThreatZone>): LatLng? {
    if (zones.isEmpty()) return null
    val threatLat = zones.map { it.latitude }.average()
    val threatLon = zones.map { it.longitude }.average()
    val vecLat = user.latitude - threatLat
    val vecLon = user.longitude - threatLon
    val mag = kotlin.math.sqrt(vecLat * vecLat + vecLon * vecLon)
    if (mag == 0.0) return null
    val normalizedLat = vecLat / mag
    val normalizedLon = vecLon / mag
    // ~180m projected guidance line away from threat center.
    val distanceDeg = 180.0 / 111_000.0
    return LatLng(
        user.latitude + normalizedLat * distanceDeg,
        user.longitude + normalizedLon * distanceDeg
    )
}

private enum class AsherZoneClass {
    HOT, WARM, COLD, UNKNOWN
}

private data class AsherZones(
    val center: LatLng,
    val hotRadiusMeters: Double,
    val warmRadiusMeters: Double,
    val coldRadiusMeters: Double
)

private fun buildAsherZones(center: LatLng, baseHotRadiusMeters: Double): AsherZones {
    val hot = baseHotRadiusMeters.coerceIn(60.0, 180.0)
    val warm = (hot * 2.3).coerceIn(180.0, 420.0)
    val cold = (warm * 1.8).coerceIn(350.0, 900.0)
    return AsherZones(
        center = center,
        hotRadiusMeters = hot,
        warmRadiusMeters = warm,
        coldRadiusMeters = cold
    )
}

private fun classifyAsherZone(user: LatLng, zones: AsherZones): AsherZoneClass {
    val distance = mapDistanceM(user, zones.center)
    return when {
        distance <= zones.hotRadiusMeters -> AsherZoneClass.HOT
        distance <= zones.warmRadiusMeters -> AsherZoneClass.WARM
        else -> AsherZoneClass.COLD
    }
}

@Composable
private fun IncidentChatTab(
    meshStatus: MeshStatus,
    incidentNotes: String,
    conversationMessages: List<ConversationMessage>,
    onNotesChange: (String) -> Unit,
    onSend: () -> Unit
) {
    val chatItems = conversationMessages.takeLast(40)
    val listState = rememberLazyListState()
    LaunchedEffect(chatItems.size) {
        if (chatItems.isNotEmpty()) {
            listState.animateScrollToItem(chatItems.lastIndex)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardWhite),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Emergency Agent", fontWeight = FontWeight.Bold, color = DarkText, fontSize = 20.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (meshStatus == MeshStatus.CONNECTED) "Active" else "Connecting",
                    color = SecondaryText,
                    fontSize = 13.sp
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardWhite),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                if (chatItems.isEmpty()) {
                    Text(
                        text = "No messages yet. Send an update to start the conversation.",
                        color = SecondaryText,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        state = listState
                    ) {
                        items(chatItems, key = { it.id }) { msg ->
                            val role = msg.role.lowercase()
                            val isUser = role == "user"
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                            ) {
                                Column(
                                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
                                ) {
                                    Text(
                                        text = if (isUser) "You" else msg.sender,
                                        fontSize = 11.sp,
                                        color = SecondaryText,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Card(
                                        modifier = Modifier.fillMaxWidth(0.82f),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isUser) PrimaryBlue else Color(0xFFF1F5FB)
                                        ),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text(
                                                text = msg.message,
                                                fontSize = 13.sp,
                                                color = if (isUser) Color.White else DarkText
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = incidentNotes,
                        onValueChange = onNotesChange,
                        modifier = Modifier
                            .fillMaxWidth(0.84f)
                            .height(56.dp),
                        placeholder = { Text("Type your message...", color = SecondaryText) },
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send)
                    )
                    IconButton(
                        onClick = onSend,
                        modifier = Modifier
                            .size(48.dp)
                            .background(PrimaryBlue, RoundedCornerShape(24.dp))
                    ) {
                        Text(
                            text = "➤",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IncidentStatusTab(
    companionsCount: Int,
    injuredCount: Int,
    roomNumber: String,
    safetyStatus: SafetyStatus,
    onCompanionsChange: (Int) -> Unit,
    onInjuredChange: (Int) -> Unit,
    onRoomNumberChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardWhite),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                SectionLabel("Status Report")
                Spacer(modifier = Modifier.height(8.dp))
                StatusBadge(safetyStatus)
                Spacer(modifier = Modifier.height(12.dp))
                CounterRow(
                    label = "Number of People",
                    value = companionsCount,
                    onChange = onCompanionsChange
                )
                Spacer(modifier = Modifier.height(10.dp))
                CounterRow(
                    label = "Number Injured",
                    value = injuredCount,
                    onChange = onInjuredChange
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = roomNumber,
                    onValueChange = onRoomNumberChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Room Number", color = SecondaryText) },
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }

        Button(
            onClick = onSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Send Status Report", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

@Composable
private fun LiveUpdateRow(text: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F7FB)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            color = DarkText,
            fontSize = 13.sp
        )
    }
}

@Composable
fun BarricadeScreen(
    threatZone: String,
    onToggle: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "alert")
    val flashAlpha by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "flashAlpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AccentRed.copy(alpha = flashAlpha)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(text = "⚠️", fontSize = 80.sp)

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "BARRICADE",
                fontSize = 56.sp,
                fontWeight = FontWeight.Black,
                color = CardWhite,
                letterSpacing = 4.sp
            )

            Text(
                text = "HIDE NOW",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = CardWhite.copy(alpha = 0.9f)
            )

            Spacer(modifier = Modifier.height(32.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "ACTIVE THREAT",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = CardWhite
                    )

                    if (threatZone.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Zone: $threatZone",
                            fontSize = 14.sp,
                            color = CardWhite.copy(alpha = 0.9f)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("• Lock all doors", color = CardWhite, fontSize = 14.sp)
                    Text("• Stay away from windows", color = CardWhite, fontSize = 14.sp)
                    Text("• Silence devices", color = CardWhite, fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = onToggle,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("EVACUATE", fontWeight = FontWeight.Bold, color = CardWhite)
                }

                OutlinedButton(
                    onClick = onReset,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CardWhite),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("ALL CLEAR", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun EvacuateScreen(
    evacuationRoute: String,
    onToggle: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "evacuate")
    val arrowOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 15f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "arrowOffset"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AccentGreen),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Row {
                Text(
                    text = "→",
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Black,
                    color = CardWhite,
                    modifier = Modifier.padding(end = arrowOffset.dp)
                )
                Text(
                    text = "→",
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Black,
                    color = CardWhite.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "EVACUATE",
                fontSize = 48.sp,
                fontWeight = FontWeight.Black,
                color = CardWhite
            )

            Text(
                text = evacuationRoute.ifBlank { "Follow exit signs" },
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = CardWhite.copy(alpha = 0.9f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("PATH IS CLEAR", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CardWhite)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Move quickly and calmly", color = CardWhite, fontSize = 14.sp)
                    Text("Help others if safe", color = CardWhite, fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = onToggle,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("BARRICADE", fontWeight = FontWeight.Bold, color = CardWhite)
                }

                OutlinedButton(
                    onClick = onReset,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CardWhite),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("ALL CLEAR", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun PermissionRequestScreen(
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LightBackground)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "🛡️", fontSize = 80.sp)

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "EchoShield",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = DarkText
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Sensor Mesh Protection",
            fontSize = 16.sp,
            color = SecondaryText
        )

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = CardWhite),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                PermissionRow("🎤", "Microphone", "Audio threat detection")
                Spacer(modifier = Modifier.height(16.dp))
                PermissionRow("📍", "Location", "Device discovery & positioning")
                Spacer(modifier = Modifier.height(16.dp))
                PermissionRow("📡", "Bluetooth", "Peer-to-peer mesh network")
                Spacer(modifier = Modifier.height(16.dp))
                PermissionRow("🔔", "Notifications", "Background alerts")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onRequestPermissions,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Grant Permissions", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "All permissions are required for protection",
            fontSize = 12.sp,
            color = SecondaryText,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun FlowHeader(
    title: String,
    step: Int = 0,
    totalSteps: Int = 0,
    showBackArrow: Boolean = false,
    showCancel: Boolean = showBackArrow,
    onBack: () -> Unit = {},
    onCancel: () -> Unit = {}
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showBackArrow) {
                    Text(
                        text = "←",
                        fontSize = 24.sp,
                        color = DarkText,
                        modifier = Modifier
                            .clickable { onBack() }
                            .padding(end = 12.dp)
                    )
                }
                Text(
                    text = title,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkText
                )
            }

            if (step > 0) {
                Text(
                    text = "$step/$totalSteps",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SecondaryText
                )
            }

            if (showBackArrow && showCancel) {
                TextButton(onClick = onCancel) {
                    Text("Cancel", color = PrimaryBlue, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (step > 0) {
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = step / totalSteps.toFloat(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = PrimaryBlue,
                trackColor = TrackGray
            )
        }
    }
}

@Composable
private fun SelectableChip(
    text: String,
    selected: Boolean,
    selectedColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .height(52.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) selectedColor.copy(alpha = 0.15f) else TrackGray.copy(alpha = 0.5f)
        ),
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, selectedColor) else null
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (text == "Yes" && selected) "✅" else if (text == "No" && selected) "❌" else "○",
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = text,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) selectedColor else SecondaryText
                )
            }
        }
    }
}

@Composable
private fun SafetyOptionButton(
    emoji: String,
    label: String,
    selected: Boolean,
    selectedColor: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) selectedColor else CardWhite
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = emoji, fontSize = 24.sp)
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = label,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) CardWhite else DarkText
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = DarkText
    )
}

@Composable
private fun StatusBadge(status: SafetyStatus) {
    val (color, label) = when (status) {
        SafetyStatus.SAFE -> AccentGreen to "Safe"
        SafetyStatus.INJURED -> AccentYellow to "Injured"
        SafetyStatus.UNKNOWN -> SecondaryText to "Unknown"
    }

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f))
    ) {
        Text(
            text = "Safety: $label",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

@Composable
private fun CounterRow(
    label: String,
    value: Int,
    onChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 15.sp, color = DarkText, fontWeight = FontWeight.Medium)
        OutlinedTextField(
            value = value.toString(),
            onValueChange = { input ->
                val digitsOnly = input.filter { it.isDigit() }
                val parsed = digitsOnly.toIntOrNull() ?: 0
                onChange(parsed)
            },
            modifier = Modifier.width(120.dp),
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
private fun EmergencyQuickActions(
    onQuickBarricade: () -> Unit,
    onQuickEvacuate: () -> Unit
) {
    Column {
        Text(
            text = "Response Logic (Placeholder)",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = SecondaryText
        )
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F7FB)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Hardcoded placeholder box",
                    fontWeight = FontWeight.SemiBold,
                    color = DarkText,
                    fontSize = 13.sp
                )
                Text(
                    text = "TODO: Replace with conditional response actions based on classifier confidence, peer confirmations, and user distance from threat.",
                    color = SecondaryText,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(emoji: String, title: String, description: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = emoji, fontSize = 24.sp)
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DarkText)
            Text(text = description, fontSize = 13.sp, color = SecondaryText)
        }
    }
}
