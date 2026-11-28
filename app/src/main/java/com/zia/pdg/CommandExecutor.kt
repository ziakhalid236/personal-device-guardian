package com.zia.pdg

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes owner commands received from the dashboard. Every command is
 * something the OWNER triggered for their OWN enrolled device.
 * Camera and microphone run only here, only on an explicit owner command,
 * and Android shows its camera/mic indicator while they run.
 */
object CommandExecutor {
    suspend fun execute(context: Context, api: ApiClient, type: String): Boolean =
        withContext(Dispatchers.IO) {
            when (type) {
                "locate", "refresh_status" -> {
                    val loc = try { LocationHelper.lastLocation(context) } catch (e: Exception) { null }
                    val (level, charging) = BatteryHelper.level(context)
                    api.heartbeat(loc, level, charging, "online").ok
                }
                "ring" -> {
                    ring(context)
                    true
                }
                "lock" -> lock(context)
                "request_photo" -> {
                    val bytes = PhotoCapture.takePhoto(context) ?: return@withContext false
                    api.uploadFile("photo", "photo_${System.currentTimeMillis()}.jpg",
                        "image/jpeg", bytes).ok
                }
                "request_audio" -> {
                    val bytes = AudioCapture.record(context, seconds = 10) ?: return@withContext false
                    api.uploadFile("audio", "audio_${System.currentTimeMillis()}.m4a",
                        "audio/mp4", bytes).ok
                }
                else -> false
            }
        }

    private fun ring(context: Context) {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val player = MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
            val vib = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vib.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0))
            // Stop after 30 seconds.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { player.stop(); player.release() } catch (e: Exception) { }
                try { vib.cancel() } catch (e: Exception) { }
            }, 30_000)
            // Turn the volume up so the ring is heard.
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (e: Exception) {
            // Ring is best-effort; a failure here must not break other commands.
        }
    }

    private fun lock(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, PdgDeviceAdminReceiver::class.java)
            if (!dpm.isAdminActive(admin)) return false
            dpm.lockNow()
            true
        } catch (e: Exception) {
            false
        }
    }
}
