package com.fcplus.forocoches

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL

class IgnoreListFetcher {

    companion object {
        private const val IGNORE_LIST_URL = "https://forocoches.com/foro/profile.php?do=ignorelist"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    suspend fun fetch(cookieHeader: String): List<String> = withContext(Dispatchers.IO) {
        val conn = URL(IGNORE_LIST_URL).openConnection() as HttpURLConnection
        conn.setRequestProperty("Cookie", cookieHeader)
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        val html = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        parseIgnoreList(html)
    }

    fun parseIgnoreList(html: String): List<String> {
        // Cada usuario ignorado es un elemento con id="userN" (sea <li>, <div>, …).
        // jsoup lo selecciona por id sin depender del tag, y de ahí sacamos el enlace
        // a member.php con el nombre.
        return Jsoup.parse(html).select("[id~=^user\\d+$]")
            .mapNotNull { row ->
                row.selectFirst("a[href*=member.php]")?.text()?.trim()?.ifBlank { null }
            }
    }
}
