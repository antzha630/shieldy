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
    val relativeLocation: String,
    val coordinateText: String,
    val timestamp: String,
    val latitude: Double,
    val longitude: Double
)

class LocationProvider(private val context: Context) {
    companion object {
        private const val TAG = "LocationProvider"
        private val DEFAULT_LOCATION = LocationInfo(
            label = "Location unavailable",
            relativeLocation = "Unknown area",
            coordinateText = "",
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
        val coordinateText = "${String.format("%.4f", location.latitude)}, ${String.format("%.4f", location.longitude)}"
        val timestamp = formatTimestamp(System.currentTimeMillis())

        _currentLocation.value = LocationInfo(
            label = coordinateText,
            relativeLocation = "Resolving nearby place...",
            coordinateText = coordinateText,
            timestamp = timestamp,
            latitude = location.latitude,
            longitude = location.longitude
        )
        reverseGeocode(location, coordinateText, timestamp)
    }

    private fun reverseGeocode(location: Location, coordinateText: String, timestamp: String) {
        val geocoder = Geocoder(context, Locale.getDefault())
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(location.latitude, location.longitude, 1) { addresses ->
                    val resolved = addresses.firstOrNull()
                    updateResolvedAddress(resolved, coordinateText, timestamp, location.latitude, location.longitude)
                }
            } else {
                @Suppress("DEPRECATION")
                val resolved = geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
                updateResolvedAddress(resolved, coordinateText, timestamp, location.latitude, location.longitude)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Geocoding failed", e)
            _currentLocation.value = _currentLocation.value.copy(
                label = coordinateText,
                relativeLocation = "Unknown nearby place"
            )
        }
    }

    private fun updateResolvedAddress(
        addr: android.location.Address?,
        coordinateText: String,
        timestamp: String,
        latitude: Double,
        longitude: Double
    ) {
        val label = addr?.let {
            buildString {
                it.thoroughfare?.let { street -> append(street) }
                it.subThoroughfare?.let { number -> append(" $number") }
                it.locality?.let { city -> append(if (isNotBlank()) ", $city" else city) }
                it.adminArea?.let { state -> append(if (isNotBlank()) ", $state" else state) }
            }.ifBlank { coordinateText }
        } ?: coordinateText

        val relative = addr?.let {
            listOfNotNull(
                it.featureName,
                it.subLocality,
                it.locality
            ).joinToString(" • ").ifBlank { "Near your current location" }
        } ?: "Near your current location"

        _currentLocation.value = LocationInfo(
            label = label,
            relativeLocation = relative,
            coordinateText = coordinateText,
            timestamp = timestamp,
            latitude = latitude,
            longitude = longitude
        )
        Log.d(TAG, "Location updated: $label ($relative)")
    }
}
