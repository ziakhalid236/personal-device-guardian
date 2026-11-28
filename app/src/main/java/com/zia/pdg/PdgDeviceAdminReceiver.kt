package com.zia.pdg

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/** Device admin receiver — only used for the owner-triggered `lock` command. */
class PdgDeviceAdminReceiver : DeviceAdminReceiver()
