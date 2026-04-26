package com.echoshield.echonode.comms

import android.content.Context
import android.util.Log
import com.echoshield.echonode.core.contracts.CloudGateway
import com.echoshield.echonode.core.contracts.ConversationMessage
import com.echoshield.echonode.core.contracts.IncidentReportDraft
import com.echoshield.echonode.core.contracts.ServerIncidentUpdate
import com.echoshield.echonode.core.contracts.ThreatZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val REPORTS_PATH = "v1/incidents/reports"
private const val LATEST_INCIDENT_PATH = "v1/incidents/latest"

class RetrofitCloudGateway private constructor(
    private val localDeviceId: String,
    private val api: CloudGatewayApi
) : CloudGateway {
    companion object {
        private const val TAG = "CloudGateway"
        private const val POLL_INTERVAL_MS = 1_500L
        private const val PREFS_NAME = "echoshield_cloud"
        private const val PREF_DEVICE_ID = "cloud_device_id"

        fun fromManifest(context: Context): CloudGateway {
            val deviceId = loadOrCreateDeviceId(context)
            val config = CloudRelayConfig.fromManifest(context, deviceId = deviceId)
            val baseUrl = config.baseUrl?.trim().takeUnless { it.isNullOrBlank() }
                ?: return CloudGateway.Disabled

            return runCatching {
                val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
                val logging = HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
                val okHttpClient = OkHttpClient.Builder()
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .writeTimeout(5, TimeUnit.SECONDS)
                    .addInterceptor { chain ->
                        val request = if (config.apiKey.isNullOrBlank()) {
                            chain.request()
                        } else {
                            chain.request()
                                .newBuilder()
                                .addHeader("Authorization", "Bearer ${config.apiKey}")
                                .build()
                        }
                        chain.proceed(request)
                    }
                    .addInterceptor(logging)
                    .build()

                val retrofit = Retrofit.Builder()
                    .baseUrl(normalizedBaseUrl)
                    .client(okHttpClient)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()

                RetrofitCloudGateway(
                    localDeviceId = deviceId,
                    api = retrofit.create(CloudGatewayApi::class.java)
                )
            }.getOrElse { error ->
                Log.w(TAG, "Cloud gateway disabled due to setup failure", error)
                CloudGateway.Disabled
            }
        }

        private fun loadOrCreateDeviceId(context: Context): String {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(PREF_DEVICE_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val created = "EchoNode-${UUID.randomUUID().toString().take(8)}"
            prefs.edit().putString(PREF_DEVICE_ID, created).apply()
            return created
        }
    }

    override val isEnabled: Boolean = true

    private val _latestIncident = MutableStateFlow<ServerIncidentUpdate?>(null)
    override val latestIncident: StateFlow<ServerIncidentUpdate?> = _latestIncident.asStateFlow()

    private var pollingJob: Job? = null

    override fun startPolling(scope: CoroutineScope) {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch(Dispatchers.IO) {
            while (true) {
                fetchLatestIncident()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    override suspend fun submitIncidentReport(report: IncidentReportDraft): Boolean {
        val request = IncidentReportRequest(
            messageId = "${System.currentTimeMillis()}-${(1000..9999).random()}",
            deviceId = localDeviceId,
            appState = report.appState.name,
            safetyStatus = report.safetyStatus.name,
            injuredCount = report.injuredCount,
            companionsCount = report.companionsCount,
            roomNumber = report.roomNumber,
            note = report.note,
            latitude = report.latitude,
            longitude = report.longitude,
            locationLabel = report.locationLabel,
            relativeLocation = report.relativeLocation,
            threatLatitude = report.threatLatitude,
            threatLongitude = report.threatLongitude,
            sessionId = report.sessionId,
            connectedPeerCount = report.connectedPeerCount,
            observedAtMs = System.currentTimeMillis()
        )

        return runCatching {
            val response = api.submitIncidentReport(request)
            val ok = response.isSuccessful
            if (ok) {
                fetchLatestIncident()
            }
            ok
        }.getOrElse {
            Log.w(TAG, "submitIncidentReport failed", it)
            false
        }
    }

    override suspend fun sendAuthorityMessage(incidentId: String, message: String): Boolean {
        if (incidentId.isBlank() || message.isBlank()) return false
        val request = AuthorityMessageRequest(
            sender = localDeviceId,
            role = "user",
            message = message
        )
        return runCatching {
            val response = api.sendAuthorityMessage(incidentId, request)
            val ok = response.isSuccessful
            if (ok) {
                fetchLatestIncident()
            }
            ok
        }.getOrElse {
            Log.w(TAG, "sendAuthorityMessage failed", it)
            false
        }
    }

    private suspend fun fetchLatestIncident() {
        runCatching {
            val response = api.getLatestIncident()
            if (!response.isSuccessful) {
                if (response.code() == 404) {
                    _latestIncident.value = null
                }
                return
            }

            val payload = response.body() ?: return
            val incident = payload.incident ?: return
            val mappedMessages = incident.authorityMessages.orEmpty().mapNotNull { msg ->
                val text = msg.message?.trim().orEmpty()
                if (text.isBlank()) return@mapNotNull null
                ConversationMessage(
                    id = msg.id ?: "${incident.id}-${msg.at ?: System.currentTimeMillis()}-${msg.sender ?: "unknown"}",
                    sender = msg.sender ?: "Dispatcher",
                    role = msg.role ?: "authority",
                    message = text,
                    at = msg.at ?: incident.updatedAt.orEmpty()
                )
            }
            _latestIncident.value = ServerIncidentUpdate(
                incidentId = incident.id,
                status = incident.status,
                recommendedAction = incident.recommendedAction.orEmpty(),
                policeBrief = incident.policeBrief.orEmpty(),
                medicalBrief = incident.medicalBrief.orEmpty(),
                threatLatitude = incident.location?.latitude,
                threatLongitude = incident.location?.longitude,
                threatZones = buildThreatZones(incident),
                authorityMessages = mappedMessages,
                liveUpdates = incident.liveUpdates.orEmpty().map { it.trim() }.filter { it.isNotBlank() }
                    .ifEmpty {
                        mappedMessages.takeLast(6).map { "${it.sender}: ${it.message}" }
                    },
                confirmedByCount = incident.confirmedByNodes?.size ?: 0,
                updatedAt = incident.updatedAt.orEmpty()
            )
        }.onFailure {
            Log.d(TAG, "fetchLatestIncident failed", it)
        }
    }

    private fun buildThreatZones(incident: IncidentDto): List<ThreatZone> {
        val zones = mutableListOf<ThreatZone>()

        incident.observations.orEmpty()
            .asReversed() // newest first
            .forEachIndexed { idx, observation ->
                val parsed = parseResponseTriggerPayload(observation.payload ?: return@forEachIndexed)
                if (parsed != null) {
                    val recencyScale = (1.0f - idx * 0.15f).coerceIn(0.35f, 1.0f)
                    val confidence = (0.4f + parsed.confirmedByCount * 0.15f).coerceAtMost(1.0f) * recencyScale
                    val radius = (60.0 + parsed.confirmedByCount * 20.0 + idx * 12.0).coerceAtMost(260.0)
                    zones += ThreatZone(
                        latitude = parsed.latitude,
                        longitude = parsed.longitude,
                        radiusMeters = radius,
                        confidence = confidence,
                        source = "response-trigger"
                    )
                }
            }

        // Fallback: if server has only a single incident location, still show one circle.
        if (zones.isEmpty()) {
            val lat = incident.location?.latitude
            val lon = incident.location?.longitude
            if (lat != null && lon != null) {
                zones += ThreatZone(
                    latitude = lat,
                    longitude = lon,
                    radiusMeters = 90.0,
                    confidence = 0.55f,
                    source = "incident-location"
                )
            }
        }

        return zones.take(5)
    }

    private fun parseResponseTriggerPayload(payload: String): ParsedTrigger? {
        val parts = payload.split("|")
        if (parts.size < 5 || parts[0] != "RESPONSE:TRIGGER") return null
        val lat = parts[2].toDoubleOrNull() ?: return null
        val lon = parts[3].toDoubleOrNull() ?: return null
        val confirmedByCount = parts[4].split(",").count { it.isNotBlank() }
        return ParsedTrigger(lat, lon, confirmedByCount)
    }

    private data class ParsedTrigger(
        val latitude: Double,
        val longitude: Double,
        val confirmedByCount: Int
    )
}

private interface CloudGatewayApi {
    @GET(LATEST_INCIDENT_PATH)
    suspend fun getLatestIncident(): retrofit2.Response<LatestIncidentResponse>

    @POST(REPORTS_PATH)
    suspend fun submitIncidentReport(@Body request: IncidentReportRequest): retrofit2.Response<Unit>

    @POST("v1/incidents/{incidentId}/authority-messages")
    suspend fun sendAuthorityMessage(
        @retrofit2.http.Path("incidentId") incidentId: String,
        @Body request: AuthorityMessageRequest
    ): retrofit2.Response<Unit>
}

private data class IncidentReportRequest(
    val messageId: String,
    val deviceId: String,
    val appState: String,
    val safetyStatus: String,
    val injuredCount: Int,
    val companionsCount: Int,
    val roomNumber: String,
    val note: String,
    val latitude: Double?,
    val longitude: Double?,
    val locationLabel: String,
    val relativeLocation: String,
    val threatLatitude: Double?,
    val threatLongitude: Double?,
    val sessionId: String?,
    val connectedPeerCount: Int,
    val observedAtMs: Long
)

private data class AuthorityMessageRequest(
    val sender: String,
    val role: String,
    val message: String
)

private data class LatestIncidentResponse(
    val ok: Boolean,
    val incident: IncidentDto?
)

private data class IncidentDto(
    val id: String,
    val status: String,
    val updatedAt: String?,
    val recommendedAction: String?,
    val policeBrief: String?,
    val medicalBrief: String?,
    val location: LocationDto?,
    val confirmedByNodes: List<String>?,
    val observations: List<ObservationDto>?,
    val authorityMessages: List<AuthorityMessageDto>?,
    val liveUpdates: List<String>?
)

private data class LocationDto(
    val latitude: Double?,
    val longitude: Double?
)

private data class ObservationDto(
    val payload: String?
)

private data class AuthorityMessageDto(
    val id: String?,
    val sender: String?,
    val role: String?,
    val message: String?,
    val at: String?
)
