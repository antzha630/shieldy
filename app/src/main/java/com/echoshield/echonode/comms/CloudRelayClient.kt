package com.echoshield.echonode.comms

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

private const val CLOUD_RELAY_ALERTS_PATH = "v1/mesh/alerts"
private const val INCIDENT_REPORTS_PATH = "v1/incidents/reports"

interface CloudRelayClient {
    val isEnabled: Boolean

    suspend fun publishAlert(envelope: CloudRelayEnvelope): CloudRelayResult
    suspend fun publishIncidentReport(report: CloudIncidentReport): CloudRelayResult

    companion object {
        val Disabled: CloudRelayClient = object : CloudRelayClient {
            override val isEnabled: Boolean = false

            override suspend fun publishAlert(envelope: CloudRelayEnvelope): CloudRelayResult {
                return CloudRelayResult.Disabled
            }

            override suspend fun publishIncidentReport(report: CloudIncidentReport): CloudRelayResult {
                return CloudRelayResult.Disabled
            }
        }
    }
}

data class CloudRelayEnvelope(
    val protocolVersion: Int,
    val deviceId: String,
    val messageId: String,
    val alertType: String,
    val body: String?,
    val payload: String,
    val sourceNodeId: String,
    val observedAtMs: Long,
    val connectedPeerCount: Int,
    val leaderNodeId: String,
    val dutyEpoch: Long,
    val sessionId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val confirmedByNodes: List<String> = emptyList()
)

data class CloudIncidentReport(
    val protocolVersion: Int,
    val deviceId: String,
    val messageId: String,
    val observedAtMs: Long,
    val connectedPeerCount: Int,
    val leaderNodeId: String,
    val dutyEpoch: Long,
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
    val sessionId: String?
)

sealed class CloudRelayResult {
    object Disabled : CloudRelayResult()
    object Delivered : CloudRelayResult()
    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : CloudRelayResult()
}

data class CloudRelayConfig(
    val baseUrl: String?,
    val deviceId: String,
    val apiKey: String? = null
) {
    companion object {
        private const val META_BASE_URL = "com.echoshield.echonode.CLOUD_RELAY_URL"
        private const val META_API_KEY = "com.echoshield.echonode.CLOUD_RELAY_API_KEY"

        fun fromManifest(context: Context, deviceId: String): CloudRelayConfig {
            val metadata = runCatching {
                @Suppress("DEPRECATION")
                context.packageManager
                    .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                    .metaData
            }.getOrNull()

            return CloudRelayConfig(
                baseUrl = metadata?.getString(META_BASE_URL),
                deviceId = deviceId,
                apiKey = metadata?.getString(META_API_KEY)
            )
        }
    }
}

class RetrofitCloudRelayClient private constructor(
    private val api: CloudRelayApi
) : CloudRelayClient {
    override val isEnabled: Boolean = true

    override suspend fun publishAlert(envelope: CloudRelayEnvelope): CloudRelayResult {
        return runCatching {
            val response = api.publishAlert(
                CloudRelayAlertRequest(
                    protocolVersion = envelope.protocolVersion,
                    deviceId = envelope.deviceId,
                    messageId = envelope.messageId,
                    alertType = envelope.alertType,
                    body = envelope.body,
                    payload = envelope.payload,
                    sourceNodeId = envelope.sourceNodeId,
                    observedAtMs = envelope.observedAtMs,
                    connectedPeerCount = envelope.connectedPeerCount,
                    leaderNodeId = envelope.leaderNodeId,
                    dutyEpoch = envelope.dutyEpoch,
                    sessionId = envelope.sessionId,
                    latitude = envelope.latitude,
                    longitude = envelope.longitude,
                    confirmedByNodes = envelope.confirmedByNodes
                )
            )
            if (response.isSuccessful) {
                CloudRelayResult.Delivered
            } else {
                CloudRelayResult.Failed("HTTP ${response.code()}")
            }
        }.getOrElse { error ->
            CloudRelayResult.Failed("Cloud relay request failed", error)
        }
    }

    override suspend fun publishIncidentReport(report: CloudIncidentReport): CloudRelayResult {
        return runCatching {
            val response = api.publishIncidentReport(report)
            if (response.isSuccessful) {
                CloudRelayResult.Delivered
            } else {
                CloudRelayResult.Failed("HTTP ${response.code()}")
            }
        }.getOrElse { error ->
            CloudRelayResult.Failed("Incident report request failed", error)
        }
    }

    companion object {
        private const val TAG = "CloudRelayClient"

        fun fromManifest(context: Context, deviceId: String): CloudRelayClient {
            return createOrDisabled(CloudRelayConfig.fromManifest(context, deviceId))
        }

        fun createOrDisabled(config: CloudRelayConfig): CloudRelayClient {
            val baseUrl = config.baseUrl?.trim().takeUnless { it.isNullOrBlank() }
                ?: return CloudRelayClient.Disabled

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

                RetrofitCloudRelayClient(retrofit.create(CloudRelayApi::class.java))
            }.getOrElse { error ->
                Log.w(TAG, "Cloud relay disabled: invalid config or client setup failed", error)
                CloudRelayClient.Disabled
            }
        }
    }
}

internal interface CloudRelayApi {
    @POST(CLOUD_RELAY_ALERTS_PATH)
    suspend fun publishAlert(@Body request: CloudRelayAlertRequest): Response<Unit>

    @POST(INCIDENT_REPORTS_PATH)
    suspend fun publishIncidentReport(@Body request: CloudIncidentReport): Response<Unit>
}

internal data class CloudRelayAlertRequest(
    val protocolVersion: Int,
    val deviceId: String,
    val messageId: String,
    val alertType: String,
    val body: String?,
    val payload: String,
    val sourceNodeId: String,
    val observedAtMs: Long,
    val connectedPeerCount: Int,
    val leaderNodeId: String,
    val dutyEpoch: Long,
    val sessionId: String?,
    val latitude: Double?,
    val longitude: Double?,
    val confirmedByNodes: List<String>
)
