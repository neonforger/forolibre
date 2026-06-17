package com.fcplus.forocoches

import android.content.Context

class NotificationRepository(context: Context) {

    private val prefs = context.getSharedPreferences("fc_notifications", Context.MODE_PRIVATE)

    fun getLastPmCount(): Int = prefs.getInt("last_pm_count", -1)
    fun setLastPmCount(count: Int) { prefs.edit().putInt("last_pm_count", count).apply() }

    fun getLastNotifCount(): Int = prefs.getInt("last_notif_count", -1)
    fun setLastNotifCount(count: Int) { prefs.edit().putInt("last_notif_count", count).apply() }

    /** Modo "notificaciones instantáneas" (servicio en primer plano). */
    fun isInstantEnabled(): Boolean = prefs.getBoolean("instant_enabled", false)
    fun setInstantEnabled(enabled: Boolean) { prefs.edit().putBoolean("instant_enabled", enabled).apply() }

    /** Intervalo de sondeo del servicio, en segundos (acotado 15..600). */
    fun getIntervalSec(): Int = prefs.getInt("instant_interval", 60)
    fun setIntervalSec(sec: Int) { prefs.edit().putInt("instant_interval", sec.coerceIn(15, 600)).apply() }
}
