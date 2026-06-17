package com.fcplus.forocoches

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Rearranca el servicio de notificaciones instantáneas tras reiniciar el móvil. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            NotificationRepository(context).isInstantEnabled()
        ) {
            NotificationService.start(context)
        }
    }
}
