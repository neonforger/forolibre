package com.fcplus.forocoches

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Configuración de selectores "remota": permite arreglar roturas cuando FC cambia su HTML
 * **sin publicar una versión nueva** en Play Store. Es DATOS (selectores), no código
 * ejecutable, así que cumple las políticas de Play Store.
 *
 * Estrategia: usa la copia cacheada (o el JSON bundleado en assets como fallback) al
 * instante; refresca en 2º plano para la próxima carga. Si el remoto está caído o es
 * inválido, sigue funcionando con lo que tenga.
 */
object RemoteConfig {

    // Misma fuente de verdad que el bundleado (app/src/main/assets/fc_config.json en el
    // repo). Editar ese fichero y hacer push actualiza el config remoto sin publicar versión.
    private const val REMOTE_URL =
        "https://raw.githubusercontent.com/neonforger/forolibre/main/app/src/main/assets/fc_config.json"
    private const val PREFS = "fc_remote_config"
    private const val KEY_JSON = "json"

    /** JSON a inyectar en el WebView: cacheado si existe y es válido, si no el bundleado. */
    fun cachedJson(context: Context): String {
        val cached = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null)
        if (cached != null && isValid(cached)) return cached
        return bundledJson(context)
    }

    /** Descarga el config remoto en 2º plano y lo cachea para la próxima carga. */
    fun refresh(context: Context) {
        Thread {
            try {
                val conn = URL(REMOTE_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                    if (isValid(body)) {
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putString(KEY_JSON, body).apply()
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {
                // sin red / remoto caído -> seguimos con lo cacheado/bundleado
            }
        }.start()
    }

    private fun bundledJson(context: Context): String = try {
        context.assets.open("fc_config.json").bufferedReader(Charsets.UTF_8).readText()
    } catch (e: Exception) {
        "{}"
    }

    /** Validación mínima: JSON objeto con "version". Evita cachear basura/HTML de error. */
    private fun isValid(s: String): Boolean = try {
        JSONObject(s).has("version")
    } catch (e: Exception) {
        false
    }
}
