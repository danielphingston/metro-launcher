package io.github.danielphingston.metro.live.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** One-shot coarse location from the network provider; no Google Play services, no ongoing updates. */
object DeviceLocation {
    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * The town or city at a coordinate, from the phone's geocoder; empty when the phone has none,
     * is offline or doesn't answer in time. Only the coordinate is passed on, nothing else.
     */
    suspend fun placeName(context: Context, latitude: Double, longitude: Double): String {
        if (!Geocoder.isPresent()) return ""
        val geocoder = Geocoder(context)
        return try {
            withTimeoutOrNull(10_000) {
                val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCancellableCoroutine<Address?> { continuation ->
                        geocoder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                            }

                            override fun onError(errorMessage: String?) {
                                if (continuation.isActive) continuation.resume(null)
                            }
                        })
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        @Suppress("DEPRECATION")
                        geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
                    }
                }
                address?.let { it.locality ?: it.subAdminArea ?: it.adminArea }.orEmpty()
            }.orEmpty()
        } catch (_: java.io.IOException) {
            ""
        } catch (_: IllegalArgumentException) {
            ""
        }
    }

    /** A fresh fix, else the last known one, else null (no permission, location off or no fix in time). */
    @SuppressLint("MissingPermission") // Permission is checked first thing in the body.
    suspend fun current(context: Context): Location? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        // The permission can be revoked while a fix is awaited (SecurityException), and a phone
        // without a network provider rejects the call (IllegalArgumentException). This runs from
        // the weather tile whenever the launcher comes back, so neither may crash it.
        return try {
            val fresh = withTimeoutOrNull(15_000) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    manager.getCurrentLocation(LocationManager.NETWORK_PROVIDER, signal, context.mainExecutor) {
                        continuation.resume(it)
                    }
                }
            }
            fresh ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
