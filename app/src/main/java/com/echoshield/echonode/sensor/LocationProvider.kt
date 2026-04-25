package com.echoshield.echonode.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LocationInfo(
    val label: String,
    val timestamp: String,
    val latitude: Double,
    val longitude: Double
)

class LocationProvider(private val context: Context) {
    companion object {
        private const val TAG = "LocationProvider"
        private val DEFAULT_LOCATION = LocationInfo(
            label = "Location unavailable",
            timestamp = formatTimestamp(System.currentTimeMillis()),
            latitude = 0.0,
            longitude = 0.0
        )

        private fun formatTimestamp(timeMs: Long): String {
            val sdf = SimpleDateFormat("HH:mm - MMMM dd, yyyy", Locale.getDefault())
            return sdf.format(Date(timeMs))
        }
    }

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _currentLocation = MutableStateFlow(DEFAULT_LOCATION)
    val currentLocation: StateFlow<LocationInfo> = _currentLocation.asStateFlow()

    private var locationCallback: LocationCallback? = null

    fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Location permission not granted")
            return
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000L)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(5000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    updateLocation(location)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )

            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let { updateLocation(it) }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception requesting location updates", e)
        }
    }

    fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        locationCallback = null
    }

    private fun updateLocation(location: Location) {
        val address = try {
            val geocoder = Geocoder(context, Locale.getDefault())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                var result: String? = null
                geocoder.getFromLocation(location.latitude, location.longitude, 1) { addresses ->
                    result = addresses.firstOrNull()?.let { addr ->
                        buildString {
                            addr.thoroughfare?.let { append(it) }
                            addr.subThoroughfare?.let { append(" $it") }
                            addr.locality?.let { append(", $it") }
                            addr.adminArea?.let { append(", $it") }
                        }.ifBlank { null }
                    }
                }
                result ?: "${String.format("%.4f", location.latitude)}, ${String.format("%.4f", location.longitude)}"
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                addresses?.firstOrNull()?.let { addr ->
                    buildString {
                        addr.thoroughfare?.let { append(it) }
                        addr.subThoroughfare?.let { append(" $it") }
                        addr.locality?.let { append(", $it") }
                        addr.adminArea?.let { append(", $it") }
                    }.ifBlank { null }
                } ?: "${String.format("%.4f", location.latitude)}, ${String.format("%.4f", location.longitude)}"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Geocoding failed", e)
            "${String.format("%.4f", location.latitude)}, ${String.format("%.4f", location.longitude)}"
        }

        _currentLocation.value = LocationInfo(
            label = address,
            timestamp = formatTimestamp(System.currentTimeMillis()),
            latitude = location.latitude,
            longitude = location.longitude
        )
        Log.d(TAG, "Location updated: $address")
    }
}
