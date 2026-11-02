package com.aurelien.ocigrab

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        AppLog.add("Administrateur appareil activé")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        AppLog.add("Administrateur appareil désactivé")
    }
}
