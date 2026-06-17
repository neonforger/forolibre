package com.fcplus.forocoches.api

import org.jsoup.Jsoup

/** Una conversación de la bandeja de MPs. */
data class PrivateMessage(
    val pmid: Long,
    val subject: String,
    val sender: String,
    val senderId: Long,
    val dateText: String
)

/** Preview lista para una notificación estilo WhatsApp. */
data class PmPreview(
    val sender: String,
    val subject: String,
    val snippet: String,
    val url: String
)

/**
 * Parsea la bandeja de MPs (`private.php`) y el detalle de un MP (`do=showpm`).
 *
 * Estructura real del skin móvil de FC:
 *  - cada fila: `<a href="...do=showpm&pmid=X"><strong>ASUNTO</strong></a>`
 *  - remitente: `<span onclick="...member.php?u=UID">NOMBRE</span>` (mismo contenedor)
 *  - fecha: el `<div>` hermano siguiente (spans con fecha y hora)
 *  - cuerpo (en showpm): `<div id="post_message_">…texto…</div>`
 */
object PmParser {

    private val PMID = Regex("""pmid=(\d+)""")
    private val UID = Regex("""u=(\d+)""")

    fun parseInbox(html: String): List<PrivateMessage> {
        val doc = Jsoup.parse(html, "https://forocoches.com/foro/")
        // Anclas de asunto y posibles remitentes, en ORDEN DE DOCUMENTO. Emparejamos cada
        // asunto con el remitente (onclick a member.php) más cercano sin cruzar a otra fila.
        // Esto es robusto al anidamiento (los MP leídos y sin leer se maquetan distinto).
        val nodes = doc.select("a[href*=do=showpm], [onclick*=member.php], a[href*=member.php]")
        val seen = HashSet<Long>()
        val result = mutableListOf<PrivateMessage>()

        for ((idx, a) in nodes.withIndex()) {
            if (!isShowpmAnchor(a)) continue
            val pmid = PMID.find(a.attr("href"))?.groupValues?.get(1)?.toLongOrNull() ?: continue
            if (!seen.add(pmid)) continue
            val subject = (a.selectFirst("strong") ?: a).text().trim()
            if (subject.isEmpty()) continue

            // Remitente: hacia delante hasta el siguiente asunto; si no, hacia atrás.
            var senderEl = scanForSender(nodes, idx + 1, +1)
            if (senderEl == null) senderEl = scanForSender(nodes, idx - 1, -1)
            val sender = senderEl?.text()?.trim().orEmpty()
            val senderId = senderEl?.let {
                UID.find(it.attr("onclick"))?.groupValues?.get(1)
                    ?: UID.find(it.attr("href"))?.groupValues?.get(1)
            }?.toLongOrNull() ?: 0L

            val dateText = a.parent()?.nextElementSibling()
                ?.select("span")?.joinToString(" ") { it.text().trim() }?.trim().orEmpty()

            result.add(PrivateMessage(pmid, subject, sender, senderId, dateText))
        }
        return result
    }

    private fun isShowpmAnchor(e: org.jsoup.nodes.Element): Boolean =
        e.normalName() == "a" && e.attr("href").contains("do=showpm")

    /** ¿Es un elemento que identifica al remitente (con texto no vacío)? */
    private fun isSender(e: org.jsoup.nodes.Element): Boolean {
        if (e.text().trim().isEmpty()) return false
        return e.attr("onclick").contains("member.php") ||
            (e.normalName() == "a" && e.attr("href").contains("member.php"))
    }

    /** Avanza desde [start] en dirección [step] buscando un remitente, parando en otra fila. */
    private fun scanForSender(
        nodes: org.jsoup.select.Elements,
        start: Int,
        step: Int
    ): org.jsoup.nodes.Element? {
        var i = start
        while (i in nodes.indices) {
            val n = nodes[i]
            if (isShowpmAnchor(n)) return null // hemos llegado a otra fila
            if (isSender(n)) return n
            i += step
        }
        return null
    }

    /** Texto plano del cuerpo de un MP (página `do=showpm`). */
    fun parseBody(html: String): String =
        Jsoup.parse(html).getElementById("post_message_")?.text()?.trim().orEmpty()
}
