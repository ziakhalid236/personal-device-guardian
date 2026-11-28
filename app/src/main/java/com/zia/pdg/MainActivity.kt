package com.zia.pdg

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Setup screen — the only UI in the app. Used once by the OWNER to enroll
 * this phone, grant permissions, and optionally hide the launcher icon.
 *
 * Enrollment: the owner signs in ONCE with their backend email + password.
 * The app receives a per-device API key and stores ONLY that key. The owner
 * password is never stored on the phone.
 *
 * After setup the owner can hide the launcher icon. The app stays visible
 * in Settings > Apps, and the persistent guardian notification opens this
 * screen again when tapped.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var api: ApiClient
    private lateinit var status: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshStatus() }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        api = ApiClient(prefs)
        status = findViewById(R.id.status_text)

        findViewById<EditText>(R.id.base_url).setText(prefs.baseUrl)
        findViewById<EditText>(R.id.device_name).setText(
            prefs.deviceName ?: (Build.MODEL ?: "My Phone")
        )

        findViewById<Button>(R.id.btn_permissions).setOnClickListener { requestPermissionsStep1() }
        findViewById<Button>(R.id.btn_background_location).setOnClickListener { requestBackgroundLocation() }
        findViewById<Button>(R.id.btn_device_admin).setOnClickListener { requestDeviceAdmin() }
        findViewById<Button>(R.id.btn_enroll).setOnClickListener { enroll() }
        findViewById<Button>(R.id.btn_hide_icon).setOnClickListener { hideIcon() }
        findViewById<Button>(R.id.btn_start_service).setOnClickListener {
            GuardianService.start(this)
            GuardianService.scheduleRestart(this)
            toast("Guardian service started")
            refreshStatus()
        }
        refreshStatus()
    }

    private fun requestPermissionsStep1() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < 29) {
            toast("Background location is included on this Android version")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toast("First grant location permission above")
            return
        }
        backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private fun requestDeviceAdmin() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, PdgDeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            toast("Device admin is already active (lock command will work)")
            return
        }
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Lets the owner lock this phone from the private dashboard if it is lost."
            )
        }
        startActivity(intent)
    }

    private fun enroll() {
        val email = findViewById<EditText>(R.id.email).text.toString().trim()
        val password = findViewById<EditText>(R.id.password).text.toString()
        val name = findViewById<EditText>(R.id.device_name).text.toString().trim()
        val baseUrl = findViewById<EditText>(R.id.base_url).text.toString().trim()
        if (email.isEmpty() || password.isEmpty() || name.isEmpty()) {
            toast("Enter email, password and a device name")
            return
        }
        prefs.baseUrl = baseUrl.ifEmpty { ApiClient.DEFAULT_BASE_URL }
        api = ApiClient(prefs)
        status.text = "Enrolling…"
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val login = api.login(email, password)
                if (!login.ok) {
                    withContext(Dispatchers.Main) {
                        status.text = "Login failed — check email and password."
                    }
                    return@launch
                }
                val token = login.data()?.optString("token") ?: ""
                val res = api.enroll(token, name, Build.MODEL ?: "Android")
                if (!res.ok) {
                    withContext(Dispatchers.Main) { status.text = "Enrollment failed." }
                    return@launch
                }
                val device = res.data()?.optJSONObject("device")
                val key = res.data()?.optJSONObject("api_key")?.optString("key")
                prefs.deviceId = device?.optString("id")
                prefs.deviceKey = key
                prefs.deviceName = name
                prefs.enrolled = true
                // The owner session token is deliberately discarded here.
                withContext(Dispatchers.Main) {
                    status.text = "Enrolled as \"$name\". Device key saved on this phone only."
                    toast("Enrolled. Starting guardian service…")
                    GuardianService.start(this@MainActivity)
                    GuardianService.scheduleRestart(this@MainActivity)
                    refreshStatus()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status.text = "Enrollment failed: ${e.message}"
                }
            }
        }
    }

    private fun hideIcon() {
        val alias = ComponentName(this, "com.zia.pdg.LauncherAlias")
        packageManager.setComponentEnabledSetting(
            alias,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        prefs.iconHidden = true
        toast("Launcher icon hidden. Tap the guardian notification to open setup again.")
        refreshStatus()
    }

    private fun refreshStatus() {
        val sb = StringBuilder()
        sb.append("Enrolled: ${if (prefs.enrolled) "yes (${prefs.deviceName})" else "no"}\n")
        sb.append("Device ID: ${prefs.deviceId ?: "—"}\n")
        sb.append("Location: ${permState(Manifest.permission.ACCESS_FINE_LOCATION)}\n")
        sb.append("Background location: ${permState(Manifest.permission.ACCESS_BACKGROUND_LOCATION)}\n")
        sb.append("Camera: ${permState(Manifest.permission.CAMERA)}\n")
        sb.append("Microphone: ${permState(Manifest.permission.RECORD_AUDIO)}\n")
        if (Build.VERSION.SDK_INT >= 33) {
            sb.append("Notifications: ${permState(Manifest.permission.POST_NOTIFICATIONS)}\n")
        }
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        sb.append("Device admin: ${dpm.isAdminActive(ComponentName(this, PdgDeviceAdminReceiver::class.java))}\n")
        sb.append("Icon hidden: ${prefs.iconHidden}\n")
        sb.append("Backend: ${prefs.baseUrl}")
        status.text = sb.toString()
    }

    private fun permState(perm: String): String =
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) "granted" else "NOT granted"

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
