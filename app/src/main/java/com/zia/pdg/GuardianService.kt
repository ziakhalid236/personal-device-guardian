package com.zia.pdg

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Foreground service that keeps the guardian alive: heartbeat (location +
 * battery) and command polling every 60 seconds. A persistent notification
 * is always shown while it runs — Android requires this for foreground
 * services, and it also makes the protection visible to the owner.
 */
class GuardianService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: Prefs
    private lateinit var api: ApiClient

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        api = ApiClient(prefs)
        createChannel()
        val notification = buildNotification("Device Guardian active")
        // Declare the service types at start so the system lets this service
        // open the camera/mic while the app is in the background (needed for
        // request_photo / request_audio). The 3-arg startForeground exists
        // since API 29; the camera/mic types since API 30.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: Exception) {
                    // Never crash the loop on one failed tick; retry next cycle.
                }
                delay(60_000)
            }
        }
        return START_STICKY
    }

    private suspend fun tick() {
        if (!prefs.enrolled || prefs.deviceId == null || prefs.deviceKey == null) return
        val loc = try { LocationHelper.lastLocation(this) } catch (e: Exception) { null }
        val (level, charging) = BatteryHelper.level(this)
        try { api.heartbeat(loc, level, charging, "online") } catch (e: Exception) { /* offline: retry next tick */ }
        val commands = try { api.pollCommands() } catch (e: Exception) { emptyList() }
        for (cmd in commands) {
            val id = cmd.optString("id")
            val type = cmd.optString("type")
            val done = try {
                CommandExecutor.execute(this, api, type)
            } catch (e: Exception) {
                false
            }
            try { api.ackCommand(id, if (done) "acked" else "failed") } catch (e: Exception) { /* ack next poll */ }
        }
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "Device Guardian", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Keeps your device protection running"
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = android.content.Intent(this, MainActivity::class.java)
        val pending = android.app.PendingIntent.getActivity(
            this, 0, openIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Device Guardian")
            .setContentText(text)
            .setContentIntent(pending)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "pdg_guardian"
        const val NOTIF_ID = 1001

        fun start(context: Context) {
            val i = Intent(context, GuardianService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        /** Keep the service restarted by WorkManager even if the system kills it. */
        fun scheduleRestart(context: Context) {
            val req = PeriodicWorkRequestBuilder<GuardianWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "pdg-restart", ExistingPeriodicWorkPolicy.KEEP, req
            )
        }
    }
}

class GuardianWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (prefs.enrolled) GuardianService.start(applicationContext)
        return Result.success()
    }
}
