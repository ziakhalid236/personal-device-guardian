package com.zia.pdg

import android.content.Context
import android.media.MediaRecorder
import kotlinx.coroutines.delay
import java.io.File

/**
 * Records a short audio clip when the OWNER triggers `request_audio` from
 * the dashboard. Android shows the mic indicator while recording.
 * Default clip length is 10 seconds.
 */
object AudioCapture {
    suspend fun record(context: Context, seconds: Int = 10): ByteArray? {
        val output = File(context.cacheDir, "audio_${System.currentTimeMillis()}.m4a")
        val recorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return try {
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(output.absolutePath)
                prepare()
                start()
            }
            delay(seconds * 1000L)
            try { recorder.stop() } catch (e: Exception) { /* short clip */ }
            recorder.release()
            if (output.exists() && output.length() > 0) output.readBytes() else null
        } catch (e: Exception) {
            try { recorder.release() } catch (e2: Exception) { }
            null
        } finally {
            output.delete()
        }
    }
}
