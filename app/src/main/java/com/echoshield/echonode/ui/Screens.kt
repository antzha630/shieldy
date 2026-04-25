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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echoshield.echonode.core.contracts.MeshStatus
import com.echoshield.echonode.core.contracts.SafetyStatus
import com.echoshield.echonode.viewmodel.MainViewModel

private val LightBackground = Color(0xFFE9EDF5)
private val CardWhite = Color.White
private val PrimaryBlue = Color(0xFF2F6BDE)
private val DarkText = Color(0xFF111111)
private val SecondaryText = Color(0xFF55637D)
private val AccentGreen = Color(0xFF0D9F46)
private val AccentYellow = Color(0xFFC79200)
private val AccentRed = Color(0xFFDC3545)
private val TrackGray = Color(0xFFD5DAE4)

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
                modifier = Modifier.padding(24.dp),
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
    locationTimestamp: String,
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
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📍", fontSize = 40.sp)
                        Text(
                            text = "Map View",
                            fontSize = 14.sp,
                            color = SecondaryText
                        )
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
    locationTimestamp: String,
    safetyStatus: SafetyStatus,
    companionsCount: Int,
    injuredCount: Int,
    incidentNotes: String,
    onCompanionsChange: (Int) -> Unit,
    onInjuredChange: (Int) -> Unit,
    onNotesChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onQuickBarricade: () -> Unit,
    onQuickEvacuate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LightBackground)
            .padding(20.dp)
    ) {
        FlowHeader(
            title = "Notes",
            step = 3,
            totalSteps = 3,
            showBackArrow = true,
            onBack = onBack,
            onCancel = onBack
        )

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardWhite),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionLabel("Location")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = locationLabel.ifBlank { "Unknown" },
                        fontSize = 15.sp,
                        color = DarkText
                    )
                    if (locationTimestamp.isNotBlank()) {
                        Text(
                            text = locationTimestamp,
                            fontSize = 13.sp,
                            color = SecondaryText
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    StatusBadge(safetyStatus)
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardWhite),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionLabel("People Details")
                    Spacer(modifier = Modifier.height(12.dp))
                    CounterRow(
                        label = "People with you",
                        value = companionsCount,
                        onChange = onCompanionsChange
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    CounterRow(
                        label = "Injured",
                        value = injuredCount,
                        onChange = onInjuredChange
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardWhite),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SectionLabel("Notes to send to authorities")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "✨ Analyzing your note...",
                        fontSize = 12.sp,
                        color = SecondaryText
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = incidentNotes,
                        onValueChange = onNotesChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        placeholder = { Text("Type or speak what happened", color = SecondaryText) },
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
                Text("Send to Authorities", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            EmergencyQuickActions(
                onQuickBarricade = onQuickBarricade,
                onQuickEvacuate = onQuickEvacuate
            )

            Spacer(modifier = Modifier.height(12.dp))
        }
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

            if (showBackArrow) {
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { onChange((value - 1).coerceAtLeast(0)) },
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                Text("−", fontSize = 18.sp)
            }
            Text(
                text = value.toString(),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(40.dp),
                textAlign = TextAlign.Center
            )
            OutlinedButton(
                onClick = { onChange(value + 1) },
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                Text("+", fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun EmergencyQuickActions(
    onQuickBarricade: () -> Unit,
    onQuickEvacuate: () -> Unit
) {
    Column {
        Text(
            text = "Emergency Actions",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = SecondaryText
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onQuickBarricade,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Barricade Now", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            OutlinedButton(
                onClick = onQuickEvacuate,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Evacuate Now", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AccentGreen)
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
