package com.zia.pdg

import android.content.Context
import android.content.SharedPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Local storage for this device's identity. The per-device API key is the
 * only long-lived credential on the phone. The owner password is NEVER
 * stored — it is used once at enrollment and then discarded.
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("pdg", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString("base_url", ApiClient.DEFAULT_BASE_URL) ?: ApiClient.DEFAULT_BASE_URL
        set(v) = sp.edit().putString("base_url", v.trim().trimEnd('/')).apply()

    var deviceId: String?
        get() = sp.getString("device_id", null)
        set(v) = sp.edit().putString("device_id", v).apply()

    var deviceKey: String?
        get() = sp.getString("device_key", null)
        set(v) = sp.edit().putString("device_key", v).apply()

    var deviceName: String?
        get() = sp.getString("device_name", null)
        set(v) = sp.edit().putString("device_name", v).apply()

    var enrolled: Boolean
        get() = sp.getBoolean("enrolled", false)
        set(v) = sp.edit().putBoolean("enrolled", v).apply()

    var iconHidden: Boolean
        get() = sp.getBoolean("icon_hidden", false)
        set(v) = sp.edit().putBoolean("icon_hidden", v).apply()
}

data class ApiResult(val status: Int, val body: JSONObject?) {
    val ok: Boolean get() = status in 200..299 && body?.optBoolean("ok") == true
    fun data(): JSONObject? = body?.optJSONObject("data")
}

/**
 * HTTP client for personal-api. All calls use a browser-like User-Agent:
 * Cloudflare's edge returns 1010 for bare library user-agents on workers.dev.
 */
class ApiClient(private val prefs: Prefs) {
    companion object {
        const val DEFAULT_BASE_URL = "https://personal-api.personal-device-guardian.workers.dev"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val UA = "Mozilla/5.0 (Linux; Android 14) PDG-Android/1.0"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun call(method: String, path: String, json: JSONObject? = null, token: String? = null): ApiResult {
        val body = if (json != null) json.toString().toRequestBody(JSON) else null
        val req = Request.Builder()
            .url(prefs.baseUrl + path)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .apply {
                if (token != null) header("Authorization", "Bearer $token")
                if (body != null) header("Content-Type", "application/json")
                method(method, body)
            }
            .build()
        return http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val parsed = try {
                JSONObject(text)
            } catch (e: Exception) {
                JSONObject().put("raw", text)
            }
            ApiResult(resp.code, parsed)
        }
    }

    /** One-time owner login, used ONLY at enrollment. The session token is returned, never stored. */
    fun login(email: String, password: String): ApiResult =
        call("POST", "/api/v1/auth/login",
            JSONObject().put("email", email).put("password", password))

    /** Enroll this phone. Returns device + per-device API key (shown once by the server). */
    fun enroll(sessionToken: String, name: String, model: String): ApiResult =
        call("POST", "/api/v1/devices",
            JSONObject().put("name", name).put("platform", "android").put("model", model),
            token = sessionToken)

    fun heartbeat(location: JSONObject?, batteryLevel: Int?, charging: Boolean?, status: String): ApiResult {
        val id = prefs.deviceId ?: return ApiResult(0, null)
        val b = JSONObject().put("status", status)
        if (batteryLevel != null) b.put("battery_level", batteryLevel)
        if (charging != null) b.put("battery_charging", charging)
        if (location != null) {
            b.put("latitude", location.optDouble("latitude"))
            b.put("longitude", location.optDouble("longitude"))
            b.put("accuracy", location.optDouble("accuracy"))
        }
        return call("POST", "/api/v1/devices/$id/heartbeat", b, token = prefs.deviceKey)
    }

    /** Poll for queued owner commands. The server marks them sent when returned. */
    fun pollCommands(): List<JSONObject> {
        val id = prefs.deviceId ?: return emptyList()
        val r = call("GET", "/api/v1/devices/$id/commands", token = prefs.deviceKey)
        if (!r.ok) return emptyList()
        val arr: JSONArray = r.data()?.optJSONArray("commands") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    fun ackCommand(commandId: String, status: String): ApiResult =
        call("POST", "/api/v1/commands/$commandId/ack",
            JSONObject().put("status", status), token = prefs.deviceKey)

    /** Upload a photo/audio file as JSON base64 (server limit 5 MB). */
    fun uploadFile(kind: String, filename: String, mime: String, bytes: ByteArray): ApiResult {
        val id = prefs.deviceId ?: return ApiResult(0, null)
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return call("POST", "/api/v1/files",
            JSONObject()
                .put("device_id", id)
                .put("kind", kind)
                .put("filename", filename)
                .put("mime", mime)
                .put("data_base64", b64),
            token = prefs.deviceKey)
    }
}
