package com.zia.pdg

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import org.json.JSONObject

object LocationHelper {
    @SuppressLint("MissingPermission")
    fun lastLocation(context: Context): JSONObject? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        var best: Location? = null
        for (p in providers) {
            if (!lm.isProviderEnabled(p)) continue
            val loc = try { lm.getLastKnownLocation(p) } catch (e: Exception) { null }
            if (loc != null && (best == null || loc.accuracy < best.accuracy)) best = loc
        }
        val l = best ?: return null
        return JSONObject()
            .put("latitude", l.latitude)
            .put("longitude", l.longitude)
            .put("accuracy", l.accuracy.toDouble())
    }
}

object BatteryHelper {
    fun level(context: Context): Pair<Int?, Boolean?> {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val v = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (v >= 0) v else null
        } else null
        val charging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bm.isCharging
        } else null
        return Pair(level, charging)
    }
}
