package com.fcplus.forocoches

import com.fcplus.forocoches.api.PmParser
import com.fcplus.forocoches.api.PmPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.HttpURLConnection
import java.net.URL

/** Contadores del menú de cabecera de FC. */
data class NotifCounts(val pm: Int, val quotes: Int, val mentions: Int) {
    /** "Notificaciones" = citas + menciones (todo lo que no son MP). */
    val notifTotal: Int get() = quotes + mentions
}

class NotificationFetcher {

    companion object {
        private const val BASE_URL = "https://forocoches.com/foro"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        private val DIGITS = Regex("""\d+""")
    }

    suspend fun fetchMainPage(cookie: String): String = withContext(Dispatchers.IO) {
        get("$BASE_URL/", cookie)
    }

    /**
     * Trae el MP más reciente de la bandeja con su cuerpo, listo para una notificación
     * estilo WhatsApp (remitente + preview). Devuelve null si no hay MPs o falla.
     */
    suspend fun fetchLatestPm(cookie: String): PmPreview? = withContext(Dispatchers.IO) {
        val pm = PmParser.parseInbox(get("$BASE_URL/private.php", cookie)).firstOrNull()
            ?: return@withContext null
        val showUrl = "$BASE_URL/private.php?do=showpm&pmid=${pm.pmid}"
        val body = try {
            PmParser.parseBody(get(showUrl, cookie))
        } catch (e: Exception) {
            ""
        }
        PmPreview(sender = pm.sender, subject = pm.subject, snippet = body, url = showUrl)
    }

    /**
     * Parsea los contadores del menú de cabecera.
     *
     * Cada contador vive dentro de su `<a class="menu-item">` en un
     * `<div class="user-notifications-count-wrapper">` (vacío = 0). Mapeamos cada uno a
     * su tipo por el href del ancla, NO por posición (el bug anterior asignaba el primer
     * contador a MP, de modo que una cita aparecía como "1 MP").
     */
    fun parseCounts(html: String): NotifCounts {
        val doc = Jsoup.parse(html)
        // Skin NUEVO: cada menú con su `user-notifications-count-wrapper`.
        if (doc.selectFirst("a.menu-item .user-notifications-count-wrapper") != null) {
            var pm = 0; var quotes = 0; var mentions = 0
            for (a in doc.select("a.menu-item")) {
                val href = a.attr("href")
                val n = countIn(a)
                when {
                    href.contains("private.php") -> pm = n
                    href.contains("tab=quotes") -> quotes = n
                    href.contains("tab=mentions") -> mentions = n
                }
            }
            return NotifCounts(pm, quotes, mentions)
        }
        // Skin ANTIGUO: "<a ...Privados>: <strong>N</strong> | <a ...Notificaciones>: <strong>N</strong>".
        val pm = strongAfterLink(doc, "private.php")
        val notif = strongAfterLink(doc, "profilenotif")
        return NotifCounts(pm, notif, 0)
    }

    private fun countIn(anchor: Element): Int {
        val wrapper = anchor.selectFirst(".user-notifications-count-wrapper") ?: return 0
        return DIGITS.find(wrapper.text())?.value?.toIntOrNull() ?: 0
    }

    /** Cuenta del primer `<strong>` que sigue al enlace cuyo href contiene [hrefNeedle]. */
    private fun strongAfterLink(doc: org.jsoup.nodes.Document, hrefNeedle: String): Int {
        val a = doc.selectFirst("a[href*=$hrefNeedle]") ?: return 0
        var sib = a.nextElementSibling()
        var hops = 0
        while (sib != null && hops < 4) {
            if (sib.normalName() == "strong") return DIGITS.find(sib.text())?.value?.toIntOrNull() ?: 0
            sib = sib.nextElementSibling()
            hops++
        }
        return 0
    }

    /** (MP, notificaciones=citas+menciones). */
    fun parseAllCounts(html: String): Pair<Int, Int> {
        val c = parseCounts(html)
        return Pair(c.pm, c.notifTotal)
    }

    fun parsePmCount(html: String): Int = parseCounts(html).pm

    fun parseNotifCount(html: String): Int = parseCounts(html).notifTotal

    private fun get(url: String, cookie: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("Cookie", cookie)
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        val code = conn.responseCode
        if (code !in 200..299) throw java.io.IOException("HTTP $code for $url")
        return try {
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally {
            conn.disconnect()
        }
    }
}
