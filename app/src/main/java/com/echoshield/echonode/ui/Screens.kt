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
import com.echoshield.echonode.core.contracts.SafetyStatus
import com.echoshield.echonode.viewmodel.MainViewModel

private val DarkBackground = Color(0xFF0A0A0A)
private val SensorGreen = Color(0xFF00E676)
private val AlertRed = Color(0xFFFF0000)
private val SafeGreen = Color(0xFF00FF00)
private val AccentGray = Color(0xFF2A2A2A)
private val TextGray = Color(0xFF888888)

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
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    var showAdvanced by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(40.dp))
            
            Text(
                text = "ECHOSHIELD",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 4.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "SENSOR MESH ACTIVE",
                fontSize = 12.sp,
                color = TextGray,
                letterSpacing = 2.sp
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(200.dp)
                    .scale(pulseScale)
            ) {
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .alpha(pulseAlpha * 0.3f)
                        .clip(CircleShape)
                        .background(SensorGreen.copy(alpha = 0.2f))
                )
                
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .alpha(pulseAlpha * 0.5f)
                        .clip(CircleShape)
                        .background(SensorGreen.copy(alpha = 0.3f))
                )

                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(SensorGreen)
                        .border(4.dp, SensorGreen.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "●",
                        fontSize = 40.sp,
                        color = DarkBackground
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AccentGray),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatusItem(
                            label = "STATUS",
                            value = if (uiState.isServiceRunning) "ACTIVE" else "STANDBY",
                            valueColor = if (uiState.isServiceRunning) SensorGreen else TextGray
                        )
                        StatusItem(
                            label = "COVERAGE",
                            value = "100%",
                            valueColor = SensorGreen
                        )
                        StatusItem(
                            label = "PEERS",
                            value = "${uiState.connectedPeers + 4}",
                            valueColor = SensorGreen
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "AUDIO LEVEL",
                        fontSize = 10.sp,
                        color = TextGray,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    LinearProgressIndicator(
                        progress = (uiState.currentAmplitude / uiState.detectionThreshold).toFloat().coerceIn(0f, 1f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = if (uiState.currentAmplitude > uiState.detectionThreshold * 0.8) AlertRed else SensorGreen,
                        trackColor = DarkBackground,
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "${uiState.currentAmplitude.toInt()} / ${uiState.detectionThreshold.toInt()}",
                        fontSize = 10.sp,
                        color = TextGray
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "ML: ${uiState.modelTopLabel} (${String.format("%.2f", uiState.modelGunshotConfidence)})",
                            fontSize = 10.sp,
                            color = if (uiState.modelGunshotConfidence >= 0.2f) AlertRed else TextGray
                        )
                        Text(
                            text = if (uiState.gateOpen) "GATE: OPEN" else "GATE: CLOSED",
                            fontSize = 10.sp,
                            color = if (uiState.gateOpen) SensorGreen else TextGray
                        )
                    }

                    if (uiState.cooldownRemainingMs > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Cooldown: ${uiState.cooldownRemainingMs / 1000}s",
                            fontSize = 10.sp,
                            color = AlertRed.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = onSimulateGunshot,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AlertRed.copy(alpha = 0.8f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "⚠ SIMULATE THREAT",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onToggleEvacuate,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SafeGreen
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "TEST EVACUATE",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedButton(
                    onClick = { showAdvanced = !showAdvanced },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = TextGray
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (showAdvanced) "HIDE OPTIONS" else "OPTIONS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            AnimatedVisibility(
                visible = showAdvanced,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    Text(
                        text = "Detection Threshold: ${uiState.detectionThreshold.toInt()}",
                        fontSize = 12.sp,
                        color = TextGray
                    )
                    Slider(
                        value = uiState.detectionThreshold.toFloat(),
                        onValueChange = { onThresholdChange(it.toDouble()) },
                        valueRange = 200f..2000f,
                        colors = SliderDefaults.colors(
                            thumbColor = SensorGreen,
                            activeTrackColor = SensorGreen
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Mesh: ${uiState.meshStatus.name}",
                fontSize = 10.sp,
                color = TextGray
            )
        }
    }
}

@Composable
private fun StatusItem(
    label: String,
    value: String,
    valueColor: Color = Color.White
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = TextGray,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor
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
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "flashAlpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AlertRed.copy(alpha = flashAlpha)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "⚠",
                fontSize = 80.sp,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "BARRICADE",
                fontSize = 64.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
                letterSpacing = 4.sp
            )

            Text(
                text = "HIDE",
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.9f),
                letterSpacing = 8.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "ACTIVE THREAT DETECTED",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = 2.sp
                    )

                    if (threatZone.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "ZONE: $threatZone",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.9f),
                            letterSpacing = 1.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Lock doors immediately.",
                        fontSize = 16.sp,
                        color = Color.White.copy(alpha = 0.9f),
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "Stay away from windows.",
                        fontSize = 16.sp,
                        color = Color.White.copy(alpha = 0.9f),
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "Silence all devices.",
                        fontSize = 16.sp,
                        color = Color.White.copy(alpha = 0.9f),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Police have been notified.",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = onToggle,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SafeGreen
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "EVACUATE MODE",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }

                OutlinedButton(
                    onClick = onReset,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "ALL CLEAR",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
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
        targetValue = 20f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "arrowOffset"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SafeGreen),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "→",
                    fontSize = 80.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.Black,
                    modifier = Modifier.padding(end = arrowOffset.dp)
                )
                Text(
                    text = "→",
                    fontSize = 80.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.Black.copy(alpha = 0.6f),
                    modifier = Modifier.padding(end = arrowOffset.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "RUN",
                fontSize = 72.sp,
                fontWeight = FontWeight.Black,
                color = Color.Black,
                letterSpacing = 8.sp
            )

            Text(
                text = evacuationRoute,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black.copy(alpha = 0.9f),
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.2f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "PATH IS CLEAR",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        letterSpacing = 2.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Evacuate immediately.",
                        fontSize = 18.sp,
                        color = Color.Black.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "Move quickly but do not run.",
                        fontSize = 16.sp,
                        color = Color.Black.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "Help others if safe to do so.",
                        fontSize = 16.sp,
                        color = Color.Black.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = onToggle,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AlertRed
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "BARRICADE MODE",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                OutlinedButton(
                    onClick = onReset,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "ALL CLEAR",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun LocationConfirmationScreen(
    locationLabel: String,
    locationTimestamp: String,
    onConfirm: (Boolean) -> Unit,
    onQuickBarricade: () -> Unit,
    onQuickEvacuate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFE9EDF5))
            .padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            FlowHeader(
                title = "Location",
                step = 1,
                totalSteps = 3
            )

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Your Location",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1A1A1A)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = locationLabel,
                        fontSize = 16.sp,
                        color = Color(0xFF1A1A1A)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = locationTimestamp,
                        fontSize = 14.sp,
                        color = Color(0xFF4E596B)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { onConfirm(false) },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Location Not Exact", fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = { onConfirm(true) },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2F6BDE)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Confirm", fontWeight = FontWeight.Bold)
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
fun SafetyCheckScreen(
    selectedStatus: SafetyStatus,
    onStatusSelected: (SafetyStatus) -> Unit,
    onNext: () -> Unit,
    onQuickBarricade: () -> Unit,
    onQuickEvacuate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFE9EDF5))
            .padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            FlowHeader(
                title = "Safety Check",
                step = 2,
                totalSteps = 3
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Are you and everyone else safe?",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF111111),
                lineHeight = 36.sp
            )

            Spacer(modifier = Modifier.height(20.dp))

            SafetyStatusButton(
                label = "Yes, we're OK",
                selected = selectedStatus == SafetyStatus.SAFE,
                color = Color(0xFF0D9F46),
                onClick = { onStatusSelected(SafetyStatus.SAFE) }
            )
            Spacer(modifier = Modifier.height(12.dp))
            SafetyStatusButton(
                label = "Someone is injured",
                selected = selectedStatus == SafetyStatus.INJURED,
                color = Color(0xFFC79200),
                onClick = { onStatusSelected(SafetyStatus.INJURED) }
            )
            Spacer(modifier = Modifier.height(12.dp))
            SafetyStatusButton(
                label = "Not sure",
                selected = selectedStatus == SafetyStatus.UNKNOWN,
                color = Color(0xFF55637D),
                onClick = { onStatusSelected(SafetyStatus.UNKNOWN) }
            )

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = onNext,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2F6BDE)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Next", fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
    onQuickBarricade: () -> Unit,
    onQuickEvacuate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFE9EDF5))
            .padding(20.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        FlowHeader(
            title = "Incident Notes",
            step = 3,
            totalSteps = 3
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Location", fontWeight = FontWeight.Bold, color = Color(0xFF111111))
                Spacer(modifier = Modifier.height(6.dp))
                Text(locationLabel, color = Color(0xFF2E3642))
                Text(locationTimestamp, color = Color(0xFF5F697A), fontSize = 13.sp)
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Safety: ${safetyStatus.name}",
                    fontWeight = FontWeight.SemiBold,
                    color = when (safetyStatus) {
                        SafetyStatus.SAFE -> Color(0xFF0D9F46)
                        SafetyStatus.INJURED -> Color(0xFFC79200)
                        SafetyStatus.UNKNOWN -> Color(0xFF55637D)
                    }
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "People Details",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111111),
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                CounterRow(
                    label = "People with you",
                    value = companionsCount,
                    onChange = onCompanionsChange
                )
                Spacer(modifier = Modifier.height(10.dp))
                CounterRow(
                    label = "Injured",
                    value = injuredCount,
                    onChange = onInjuredChange
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Notes / Voice Note Transcript",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111111)
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = incidentNotes,
                    onValueChange = onNotesChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    placeholder = { Text("Type or speak what happened") }
                )
            }
        }

        Button(
            onClick = onSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B4FB5)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Notify Incident & Continue", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        EmergencyQuickActions(
            onQuickBarricade = onQuickBarricade,
            onQuickEvacuate = onQuickEvacuate
        )

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun FlowHeader(
    title: String,
    step: Int,
    totalSteps: Int
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111111))
            Text("$step/$totalSteps", color = Color(0xFF55637D), fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = step / totalSteps.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(8.dp)),
            color = Color(0xFF2F6BDE),
            trackColor = Color(0xFFD5DAE4)
        )
    }
}

@Composable
private fun SafetyStatusButton(
    label: String,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) color else Color.White,
            contentColor = if (selected) Color.White else Color(0xFF1A1A1A)
        )
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
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
            color = Color(0xFF2E3642),
            fontWeight = FontWeight.SemiBold
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
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AlertRed),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Immediate Barricade", fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = onQuickEvacuate,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Immediate Evacuate", fontWeight = FontWeight.Bold)
            }
        }
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
        Text(label, fontSize = 16.sp, color = Color(0xFF1A1A1A), fontWeight = FontWeight.SemiBold)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { onChange((value - 1).coerceAtLeast(0)) }) {
                Text("-")
            }
            OutlinedTextField(
                value = value.toString(),
                onValueChange = { raw ->
                    val parsed = raw.toIntOrNull() ?: 0
                    onChange(parsed.coerceAtLeast(0))
                },
                singleLine = true,
                modifier = Modifier.width(88.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedButton(onClick = { onChange(value + 1) }) {
                Text("+")
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
            .background(DarkBackground)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "🛡️",
            fontSize = 80.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "ECHOSHIELD",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            letterSpacing = 4.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Permissions Required",
            fontSize = 20.sp,
            color = SensorGreen,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = AccentGray),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                PermissionItem("🎤", "Microphone", "Threat audio detection")
                Spacer(modifier = Modifier.height(12.dp))
                PermissionItem("📍", "Location", "Nearby device discovery")
                Spacer(modifier = Modifier.height(12.dp))
                PermissionItem("📡", "Bluetooth", "Peer-to-peer mesh network")
                Spacer(modifier = Modifier.height(12.dp))
                PermissionItem("🔔", "Notifications", "Background monitoring alerts")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onRequestPermissions,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SensorGreen
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "GRANT PERMISSIONS",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = DarkBackground,
                letterSpacing = 1.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "All permissions are required for the\nsensor mesh to function properly.",
            fontSize = 12.sp,
            color = TextGray,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PermissionItem(
    emoji: String,
    title: String,
    description: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = emoji,
            fontSize = 24.sp
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = TextGray
            )
        }
    }
}
