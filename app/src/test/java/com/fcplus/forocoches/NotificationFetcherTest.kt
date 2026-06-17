package com.fcplus.forocoches

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationFetcherTest {

    private val fetcher = NotificationFetcher()

    /** Menú de cabecera de FC con contadores configurables (estructura real del skin). */
    private fun menu(pm: String = "", quotes: String = "", mentions: String = ""): String {
        fun item(href: String, badge: String) =
            """<a class="menu-item" href="$href">
                 <span class="menu-icon"></span>
                 <div class='user-notifications-count-wrapper'>${
                if (badge.isEmpty()) "" else
                    """<div class="user-notifications-container"><span class="user-notifications">$badge</span></div>"""
            }</div>
               </a>"""
        return """<html><body><nav>
            ${item("/foro/private.php", pm)}
            ${item("/foro/member.php?u=0&tab=quotes#citas", quotes)}
            ${item("/foro/member.php?u=0&tab=mentions#menciones", mentions)}
        </nav></body></html>"""
    }

    @Test
    fun `sin contadores todo es 0`() {
        val c = fetcher.parseCounts(menu())
        assertEquals(0, c.pm)
        assertEquals(0, c.quotes)
        assertEquals(0, c.mentions)
    }

    @Test
    fun `mapea cada contador a su tipo por el href`() {
        val c = fetcher.parseCounts(menu(pm = "2", quotes = "3", mentions = "1"))
        assertEquals(2, c.pm)
        assertEquals(3, c.quotes)
        assertEquals(1, c.mentions)
        assertEquals(4, c.notifTotal)
    }

    @Test
    fun `una cita NO se reporta como mensaje privado (regresion del bug)`() {
        // Solo hay 1 cita, ningún MP -> pm debe ser 0 (antes salía "1 MP").
        val c = fetcher.parseCounts(menu(quotes = "1"))
        assertEquals(0, c.pm)
        assertEquals(1, c.quotes)
        assertEquals(0, fetcher.parsePmCount(menu(quotes = "1")))
        assertEquals(1, fetcher.parseNotifCount(menu(quotes = "1")))
    }

    @Test
    fun `parseAllCounts devuelve MP y notificaciones combinadas`() {
        val (pm, notif) = fetcher.parseAllCounts(menu(pm = "5", quotes = "2", mentions = "4"))
        assertEquals(5, pm)
        assertEquals(6, notif)
    }

    @Test
    fun `skin antiguo - lee contadores de la cabecera clasica`() {
        val old = """<html><body><ul><li class="link">
            <a href="/foro/member.php?u=1">yo</a> |
            <a href="/foro/private.php?">Privados</a>: <strong>3</strong> |
            <a href="/foro/usertag.php?do=profilenotif&tab=mobile">Notificaciones</a>: <strong>5</strong>
            </li></ul></body></html>"""
        val c = fetcher.parseCounts(old)
        assertEquals(3, c.pm)
        assertEquals(5, c.notifTotal)
    }

    @Test
    fun `skin antiguo real - cabecera con 0 sin leer`() {
        val html = javaClass.classLoader!!.getResourceAsStream("showthread_old.html")!!
            .bufferedReader(Charsets.UTF_8).readText()
        val c = fetcher.parseCounts(html)
        assertEquals(0, c.pm)
        assertEquals(0, c.notifTotal)
    }

    @Test
    fun `pagina real deslogueada da todo 0`() {
        val html = javaClass.classLoader!!.getResourceAsStream("mainpage_mobile.html")!!
            .bufferedReader(Charsets.UTF_8).readText()
        val c = fetcher.parseCounts(html)
        assertEquals(0, c.pm)
        assertEquals(0, c.notifTotal)
    }
}
