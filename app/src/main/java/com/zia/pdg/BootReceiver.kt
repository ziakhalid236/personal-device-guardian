package com.zia.pdg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restart the guardian after the phone boots. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = Prefs(context)
            if (prefs.enrolled) {
                GuardianService.start(context)
                GuardianService.scheduleRestart(context)
            }
        }
    }
}
