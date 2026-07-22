package com.fcplus.forocoches

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Enriquecimiento nativo de embeds sociales (tweets y TikTok).
 *
 * El extractor degrada cada embed a una tarjeta-enlace (<a href=URL><b>etiqueta</b></a>).
 * Aquí se consulta el endpoint público del proveedor (HTTP nativo permitido: NO es HTML
 * de FC, así que Cloudflare de FC no pinta nada) y la tarjeta se sustituye por autor +
 * texto + imagen cuando la hay. Verificado 2026-07-22:
 *  - Tweets: cdn.syndication.twimg.com/tweet-result da texto, autor y fotos; el token se
 *    deriva del id con el truco π→base36 (mismo que usa el widget oficial).
 *  - TikTok: oEmbed público (www.tiktok.com/oembed) da autor, título y miniatura.
 *  - Instagram: SIN oEmbed público desde 2020 → su tarjeta-enlace se queda tal cual.
 * Mismo patrón async que PostImages: caché en memoria + pool + re-render al llegar.
 */
object EmbedEnricher {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    // url del embed → fragmento enriquecido ("" = falló: no reintentar, queda la tarjeta)
    private val cache = ConcurrentHashMap<String, String>()
    private val inflight = Collections.synchronizedSet(HashSet<String>())
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    private val TWEET =
        Regex("""https?://(?:mobile\.)?(?:twitter|x)\.com/[^/"'<>\s]+/status/(\d+)[^"'<>\s]*""")
    private val TIKTOK =
        Regex("""https?://(?:www\.)?tiktok\.com/@[^"'<>\s]*?video/\d+[^"'<>\s]*""")

    /**
     * Devuelve el HTML del post con las tarjetas de embeds ya resueltos sustituidas por
     * su versión enriquecida, y dispara en segundo plano los que falten (onUpdated llega
     * en el hilo de UI cuando alguno nuevo esté listo; la caché evita bucles).
     */
    fun apply(html: String, onUpdated: () -> Unit): String {
        if (!html.contains("/status/") && !html.contains("tiktok.com")) return html
        var out = html
        val urls = LinkedHashSet<String>()
        TWEET.findAll(html).forEach { urls.add(it.value) }
        TIKTOK.findAll(html).forEach { urls.add(it.value) }
        var n = 0
        for (url in urls) {
            if (n++ >= 4) break // tope por post: hilos-recopilatorio no deben saturar
            val frag = cache[url]
            when {
                frag == null -> fetchAsync(url, onUpdated)
                frag.isNotEmpty() -> out = replaceCard(out, url, frag)
            }
        }
        return out
    }

    /** Sustituye la tarjeta-enlace EXACTA del extractor; si el markup no casa, no toca. */
    private fun replaceCard(html: String, url: String, frag: String): String {
        val card = Regex("""<a href="${Regex.escape(url)}"><b>[^<]*</b></a>""")
        return card.replace(html) { frag }
    }

    private fun fetchAsync(url: String, onUpdated: () -> Unit) {
        if (!inflight.add(url)) return
        pool.execute {
            val frag = try {
                if (url.contains("tiktok.com")) tiktok(url) else tweet(url)
            } catch (_: Exception) { "" }
            cache[url] = frag
            inflight.remove(url)
            if (frag.isNotEmpty()) main.post { onUpdated() }
        }
    }

    private fun tweet(url: String): String {
        val id = TWEET.find(url)?.groupValues?.get(1) ?: return ""
        val o = getJson(
            "https://cdn.syndication.twimg.com/tweet-result?id=$id&token=${tweetToken(id)}&lang=es"
        ) ?: return ""
        val user = o.optJSONObject("user")
        val author = user?.optString("name").orEmpty()
        val handle = user?.optString("screen_name").orEmpty()
        val text = o.optString("text")
        if (text.isEmpty() && author.isEmpty()) return ""
        // Imagen: primera foto; si es un vídeo, su póster.
        val photo = o.optJSONArray("photos")?.optJSONObject(0)?.optString("url")
            ?.ifEmpty { null }
            ?: o.optJSONArray("mediaDetails")?.optJSONObject(0)?.optString("media_url_https")
                ?.ifEmpty { null }
            ?: o.optJSONObject("video")?.optString("poster")?.ifEmpty { null }
        val sb = StringBuilder("<blockquote><b>🐦 ").append(esc(author))
        if (handle.isNotEmpty()) sb.append(" (@").append(esc(handle)).append(")")
        sb.append("</b>")
        if (text.isNotEmpty()) sb.append("<br>").append(esc(text).replace("\n", "<br>"))
        if (photo != null) sb.append("<br><a href=\"").append(url)
            .append("\"><img src=\"").append(esc(photo)).append("\"></a>")
        sb.append("<br><a href=\"").append(url).append("\">Ver en X</a></blockquote>")
        return sb.toString()
    }

    /**
     * Token del endpoint de sindicación: ((id/1e15)·π) en base36 sin ceros ni punto —
     * el mismo cálculo que hace el widget embebido oficial. El redondeo de Double es el
     * de IEEE-754, idéntico al Number de JS, así que el token coincide.
     */
    private fun tweetToken(id: String): String {
        val x = (id.toDouble() / 1e15) * Math.PI
        val digits = "0123456789abcdefghijklmnopqrstuvwxyz"
        var n = x.toLong()
        val ip = StringBuilder()
        if (n == 0L) ip.append('0')
        while (n > 0) { ip.insert(0, digits[(n % 36).toInt()]); n /= 36 }
        var frac = x - x.toLong()
        val s = StringBuilder(ip).append('.')
        repeat(16) {
            frac *= 36
            val d = frac.toInt()
            s.append(digits[d])
            frac -= d
        }
        return s.toString().replace("0", "").replace(".", "")
    }

    private fun tiktok(url: String): String {
        val clean = url.replace("&amp;", "&").substringBefore("?")
        val o = getJson(
            "https://www.tiktok.com/oembed?url=" + URLEncoder.encode(clean, "UTF-8")
        ) ?: return ""
        val author = o.optString("author_name")
        val title = o.optString("title")
        val thumb = o.optString("thumbnail_url")
        if (author.isEmpty() && title.isEmpty() && thumb.isEmpty()) return ""
        val sb = StringBuilder("<blockquote><b>🎵 ").append(esc(author)).append(" en TikTok</b>")
        if (title.isNotEmpty()) {
            val t = if (title.length > 200) title.take(200) + "…" else title
            sb.append("<br>").append(esc(t))
        }
        if (thumb.isNotEmpty()) sb.append("<br><a href=\"").append(url)
            .append("\"><img src=\"").append(esc(thumb)).append("\"></a>")
        sb.append("<br><a href=\"").append(url).append("\">Ver vídeo en TikTok</a></blockquote>")
        return sb.toString()
    }

    private fun getJson(api: String): JSONObject? {
        val conn = URL(api).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        return try {
            if (conn.responseCode !in 200..299) null
            else JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
