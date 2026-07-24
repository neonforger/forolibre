package com.fcplus.forocoches

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.EditText
import androidx.appcompat.widget.SwitchCompat
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var repo: IgnoreListRepository
    private lateinit var keywordRepo: KeywordRepository

    // ── Shell nativo (v2) ──
    private lateinit var nativePanel: View
    private lateinit var listRefresh: SwipeRefreshLayout
    private lateinit var threadList: RecyclerView
    private lateinit var listLoading: ProgressBar
    private lateinit var listEmpty: TextView
    // Barra inferior PROPIA (6 destinos, scroll lateral): contenedor + mapas por ítem.
    private lateinit var bottomNav: View
    private var selectedNavId = R.id.nav_home
    private val navIcons = HashMap<Int, android.widget.ImageView>()
    private val navLabels = HashMap<Int, TextView>()
    private val navBadges = HashMap<Int, TextView>()
    private val navIds = intArrayOf(
        R.id.nav_home, R.id.nav_favs, R.id.nav_mythreads, R.id.nav_participated,
        R.id.nav_notif, R.id.nav_quotes, R.id.nav_profile
    )
    private lateinit var adapter: ThreadListAdapter

    private lateinit var forumTabs: TabLayout

    // ── Panel de hilo nativo (Fase 2) ──
    private lateinit var threadPanel: View
    private lateinit var postList: RecyclerView
    private lateinit var threadLoading: ProgressBar
    private lateinit var threadTitle: TextView
    private lateinit var threadPageInfo: TextView
    private lateinit var threadFav: ImageButton
    private lateinit var postAdapter: PostAdapter

    // ── Pantalla "hilo restringido" (+HD) ──
    private lateinit var restrictedView: View
    private lateinit var restrictedMsg: TextView
    private lateinit var restrictedMeta: TextView
    private lateinit var restrictedLogin: TextView
    private lateinit var restrictedInvite: TextView
    private var restrictedInviteUrl = ""

    // ── Panel de respuesta nativo (Fase 3) ──
    private lateinit var replyPanel: View
    private lateinit var replyInput: android.widget.EditText
    private lateinit var replySend: TextView
    private lateinit var replyCancel: TextView
    private lateinit var replyQuotesContainer: android.widget.LinearLayout
    private lateinit var replyHeaderTitle: TextView
    private lateinit var replySubject: android.widget.EditText
    private var isReplyVisible = false
    private var sendingReply = false
    // Modo del panel de escritura: reply | edit | newthread. Editar/crear reusan el panel.
    private var replyMode = "reply"
    private var editingPid = ""
    // Citas de la respuesta en curso (única fuente de verdad, ordenadas). El "+" de cada
    // post y las tarjetas del panel leen de aquí.
    private val replyQuotes = LinkedHashMap<String, PostItem>()

    // ── Editor BBCode + smilies (Bloque A) ──
    private lateinit var bbcode: BbcodeEditor
    private var smileyCache: List<Smiley>? = null
    private var smileyDialogPending = false

    // ── Secciones nativas (Bloque B) ──
    private lateinit var noticesPanel: View
    private lateinit var noticesHeader: TextView
    private lateinit var noticesList: RecyclerView
    private lateinit var noticesLoading: ProgressBar
    private lateinit var noticesEmpty: TextView
    private lateinit var noticeAdapter: NoticeAdapter
    private var isNoticesVisible = false
    private var currentNoticesKind = ""

    private lateinit var profilePanel: View
    private lateinit var profileAvatar: android.widget.ImageView
    private lateinit var profileName: TextView
    private var isProfileVisible = false
    private var profileLogoutUrl = ""

    // ── Mensajes privados nativos ──
    private lateinit var pmPanel: View
    private lateinit var pmList: RecyclerView
    private lateinit var pmLoading: ProgressBar
    private lateinit var pmEmpty: TextView
    private lateinit var pmAdapter: PmInboxAdapter
    private lateinit var pmDetailPanel: View
    private lateinit var pmDetailSubject: TextView
    private lateinit var pmDetailSender: TextView
    private lateinit var pmDetailBody: TextView
    private lateinit var pmDetailReply: TextView
    private lateinit var pmComposePanel: View
    private lateinit var pmComposeTitle: TextView
    private lateinit var pmComposeTo: android.widget.EditText
    private lateinit var pmComposeSubject: android.widget.EditText
    private lateinit var pmComposeMessage: android.widget.EditText
    private lateinit var pmComposeSend: TextView
    private var isPmVisible = false
    private var isPmDetailVisible = false
    private var isPmComposeVisible = false
    private var currentPmId = ""           // MP abierto en el detalle
    private var currentPmSubject = ""      // asunto del MP abierto (para "Re:" al responder)
    private var pmComposeMode = "new"      // new | reply
    private var sendingPm = false

    // ── Perfil de otro usuario (mención tocada) ──
    private lateinit var memberPanel: View
    private lateinit var memberAvatar: android.widget.ImageView
    private lateinit var memberName: TextView
    private lateinit var memberPmBtn: TextView
    private lateinit var memberOpenWeb: TextView
    private var isMemberVisible = false
    private var currentMemberUid = ""
    private var currentMemberUsername = ""

    private lateinit var nativeHeader: TextView
    private lateinit var fabNewThread: View
    private var listSource = "home"        // home | favs | mine (qué alimenta la lista nativa)
    private var myThreadsBase = ""         // search.php?searchid=N para paginar Mis hilos

    // ── Opciones (fuente, avatar, filtros) ──
    private lateinit var optionsPanel: View
    private lateinit var options: OptionsController
    private var isOptionsVisible = false

    // ── Panel de login nativo (Fase 3) ──
    private lateinit var loginPanel: View
    private lateinit var loginUser: android.widget.EditText
    private lateinit var loginPass: android.widget.EditText
    private lateinit var loginError: TextView
    private lateinit var loginSubmit: TextView
    private var isLoginVisible = false
    private var sendingLogin = false
    private var pendingThreadUrl = ""      // hilo que pidió login (+HD invitado): se reabre al entrar
    private var pendingThreadTitle = ""

    private var currentThreadUrl = ""      // URL base del hilo abierto (sin &page=)
    private var currentThreadTid = ""      // t= del hilo abierto (para responder/citar)
    private var threadPage = 1
    private var threadPageCount = 1
    private var replaceOnLoad = false      // salto de página: la respuesta REEMPLAZA la lista
    private var prependOnLoad = false      // página anterior: la respuesta va DELANTE
    private var firstLoadedPage = 1        // primera página en memoria (threadPage = la última)
    private var pendingScrollPid = ""      // post al que saltar tras cargar (citas, deep links)
    private var pendingDeepLink = ""       // deep link recibido antes de tener motor listo
    private var searchQuery = ""           // término del buscador (listSource = "search")
    private var searchTitleOnly = true
    private var loadingThreadPage = false
    private var isThreadVisible = false
    private var cameFromThread = false     // para que atrás desde la web vuelva al hilo

    private var menuLinks: MenuLinks? = null
    private var isWebVisible = false
    private var engineReady = false      // el WebView ya cargó una página de FC con extractor
    private var listLoaded = false
    private var currentPage = 1
    private var loadingPage = false      // evita ráfagas de peticiones con scroll rápido
    private var currentForumId = 2       // General; se persiste el último elegido
    private var forumsRequested = false
    private var populatingTabs = false   // el alta programática de tabs no debe disparar cargas

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var swipeStartedInList = false   // el swipe de subforo solo cuenta si empieza en la lista

    // Vídeo de embed a pantalla completa (WebChromeClient.onShowCustomView).
    private var fullscreenView: View? = null
    private var fullscreenCallback: android.webkit.WebChromeClient.CustomViewCallback? = null

    companion object {
        private const val PREFS = "shell_prefs"
        private const val PREF_LAST_FID = "last_fid"
        private const val PREF_WELCOME_SHOWN = "welcome_shown"
    }

    private fun buildListUrl(page: Int): String {
        // Cache-buster _fp: FC sirve subscription.php tras Varnish con copias de hasta
        // ~1 min (x-cache: HIT); sin él, tras marcar/desmarcar un favorito la lista
        // llegaría rancia. El param único fuerza contenido fresco (gotcha 2026-07-22).
        val base = when (listSource) {
            "favs" -> "https://forocoches.com/foro/subscription.php?_fp=${System.currentTimeMillis()}"
            else -> "https://forocoches.com/foro/forumdisplay.php?f=$currentForumId"
        }
        if (page <= 1) return base
        return base + (if (base.contains('?')) "&" else "?") + "page=$page"
    }

    /**
     * Sesión = cookie bbuserid de vBulletin (la pone el login "recuérdame"; los invitados
     * nunca la tienen). OJO: el HTML del menú de FC NO sirve de señal — el esqueleto con
     * private.php/member.php se sirve IDÉNTICO a invitados y logueados (verificado por CDP).
     * CookieManager sí ve las cookies HttpOnly, a diferencia del document.cookie de JS.
     */
    private fun isLoggedIn(): Boolean {
        val c = CookieManager.getInstance().getCookie("https://forocoches.com") ?: return false
        return c.contains("bbuserid=")
    }

    /** La pestaña de cuenta de la barra inferior: "Entrar" de invitado, "Perfil" con sesión. */
    private fun updateAccountNavItem() {
        navLabels[R.id.nav_profile]?.text = if (isLoggedIn()) "Perfil" else "Entrar"
    }

    // ── Barra inferior propia ────────────────────────────────────────────────

    private fun configureBottomBar() {
        val icons = mapOf(
            R.id.nav_home to R.drawable.ic_nav_home,
            R.id.nav_favs to R.drawable.ic_nav_star,
            R.id.nav_mythreads to R.drawable.ic_nav_threads,
            R.id.nav_participated to R.drawable.ic_nav_participated,
            R.id.nav_notif to R.drawable.ic_nav_bell,
            R.id.nav_quotes to R.drawable.ic_nav_quote,
            R.id.nav_profile to R.drawable.ic_nav_person
        )
        val labels = mapOf(
            R.id.nav_home to "Inicio",
            R.id.nav_favs to "Favoritos",
            R.id.nav_mythreads to "Mis hilos",
            R.id.nav_participated to "Participados",
            R.id.nav_notif to "Menciones",
            R.id.nav_quotes to "Citas",
            R.id.nav_profile to "Perfil"
        )
        for (id in navIds) {
            val item = findViewById<View>(id)
            navIcons[id] = item.findViewById(R.id.nav_icon)
            navLabels[id] = item.findViewById(R.id.nav_label)
            navBadges[id] = item.findViewById(R.id.nav_badge)
            navIcons[id]?.setImageResource(icons.getValue(id))
            navLabels[id]?.text = labels.getValue(id)
            item.setOnClickListener { onNavClicked(id) }
        }
        setSelectedNav(R.id.nav_home)
    }

    private fun onNavClicked(id: Int) {
        val reselect = id == selectedNavId
        when (id) {
            R.id.nav_home -> { showHomeList(); if (reselect) requestThreadList(1) }
            R.id.nav_favs -> if (isLoggedIn()) showFavsList() else showLogin()
            R.id.nav_mythreads -> if (isLoggedIn()) showMyThreadsList() else showLogin()
            R.id.nav_participated -> if (isLoggedIn()) showParticipatedList() else showLogin()
            R.id.nav_notif -> if (isLoggedIn()) showNotices("mentions") else showLogin()
            R.id.nav_quotes -> if (isLoggedIn()) showNotices("quotes") else showLogin()
            R.id.nav_profile -> if (isLoggedIn()) showProfile() else showLogin()
        }
    }

    private fun setSelectedNav(id: Int) {
        selectedNavId = id
        val red = 0xFFC8102E.toInt()
        val gray = 0xFF757575.toInt()
        for (nid in navIds) {
            val c = if (nid == id) red else gray
            navIcons[nid]?.setColorFilter(c)
            navLabels[nid]?.setTextColor(c)
        }
    }

    /** Ítem de la barra que corresponde a la fuente actual de la lista nativa. */
    private fun navIdForList(): Int = when (listSource) {
        "favs" -> R.id.nav_favs
        "mine" -> R.id.nav_mythreads
        "participated" -> R.id.nav_participated
        else -> R.id.nav_home   // "search" incluido: la búsqueda cuelga de Inicio
    }

    // ── Secciones nativas (Bloque B) ─────────────────────────────────────────

    /** Inicio: la lista nativa vuelve al modo foro (pestañas de subforos visibles). */
    private fun showHomeList() {
        val wasOther = listSource != "home"
        listSource = "home"
        forumTabs.visibility = View.VISIBLE
        nativeHeader.text = "ForoPlus"
        showNative()
        fabNewThread.visibility = if (isLoggedIn()) View.VISIBLE else View.GONE
        setSelectedNav(R.id.nav_home)
        if (wasOther) {
            listLoaded = false
            adapter.submit(emptyList())
        }
        if (!listLoaded) requestThreadList(1)
    }

    /** Favoritos: la MISMA lista nativa, alimentada por subscription.php. */
    private fun showFavsList() {
        listSource = "favs"
        forumTabs.visibility = View.GONE
        nativeHeader.text = "Favoritos"
        listLoaded = false
        adapter.submit(emptyList())
        showNative()
        fabNewThread.visibility = View.GONE
        setSelectedNav(R.id.nav_favs)
        requestThreadList(1)
    }

    /** Mis hilos: los iniciados por el usuario. El motor resuelve el UID real (finduser). */
    private fun showMyThreadsList() {
        listSource = "mine"
        myThreadsBase = ""
        forumTabs.visibility = View.GONE
        nativeHeader.text = "Mis hilos"
        listLoaded = false
        adapter.submit(emptyList())
        showNative()
        fabNewThread.visibility = View.GONE
        setSelectedNav(R.id.nav_mythreads)
        requestThreadList(1)
    }

    /** Buscador: pide el término y enseña los resultados en la MISMA lista nativa. */
    private fun showSearchSheet() {
        if (!isLoggedIn()) { toast("La búsqueda de ForoCoches requiere iniciar sesión"); showLogin(); return }
        val view = layoutInflater.inflate(R.layout.sheet_search, null)
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        sheet.setContentView(view)
        val basePad = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePad + maxOf(bars.bottom, ime.bottom))
            insets
        }
        val input = view.findViewById<EditText>(R.id.search_input)
        val onlyTitles = view.findViewById<SwitchCompat>(R.id.search_titles_only)
        input.setText(searchQuery)
        onlyTitles.isChecked = searchTitleOnly
        view.findViewById<View>(R.id.search_go).setOnClickListener {
            val q = input.text.toString().trim()
            if (q.length < 3) { toast("Escribe al menos 3 caracteres"); return@setOnClickListener }
            searchQuery = q
            searchTitleOnly = onlyTitles.isChecked
            sheet.dismiss()
            runSearch()
        }
        sheet.show()
        input.requestFocus()
    }

    private fun runSearch() {
        listSource = "search"
        myThreadsBase = ""
        forumTabs.visibility = View.GONE
        nativeHeader.text = "\"$searchQuery\""
        listLoaded = false
        adapter.submit(emptyList())
        showNative()
        fabNewThread.visibility = View.GONE
        setSelectedNav(R.id.nav_home)
        requestThreadList(1)
    }

    /** Participados: hilos donde el usuario ha posteado (búsqueda por usuario, showposts=0). */
    private fun showParticipatedList() {
        listSource = "participated"
        myThreadsBase = ""
        forumTabs.visibility = View.GONE
        nativeHeader.text = "Participados"
        listLoaded = false
        adapter.submit(emptyList())
        showNative()
        fabNewThread.visibility = View.GONE
        setSelectedNav(R.id.nav_participated)
        requestThreadList(1)
    }

    // ── Mensajes privados nativos ─────────────────────────────────────────────

    private fun configurePmPanels() {
        pmPanel = findViewById(R.id.pm_panel)
        pmList = findViewById(R.id.pm_list)
        pmLoading = findViewById(R.id.pm_loading)
        pmEmpty = findViewById(R.id.pm_empty)
        pmAdapter = PmInboxAdapter { pm -> showPmDetail(pm.pmid, pm.subject) }
        pmList.layoutManager = LinearLayoutManager(this)
        pmList.adapter = pmAdapter
        findViewById<View>(R.id.pm_back).setOnClickListener { showProfile() }
        findViewById<View>(R.id.pm_new).setOnClickListener { showPmCompose("new", "", "") }

        pmDetailPanel = findViewById(R.id.pm_detail_panel)
        pmDetailSubject = findViewById(R.id.pm_detail_subject)
        pmDetailSender = findViewById(R.id.pm_detail_sender)
        pmDetailBody = findViewById(R.id.pm_detail_body)
        pmDetailBody.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        pmDetailReply = findViewById(R.id.pm_detail_reply)
        findViewById<View>(R.id.pm_detail_back).setOnClickListener { showPmInbox() }
        pmDetailReply.setOnClickListener { showPmCompose("reply", currentPmId, currentPmSubject) }

        pmComposePanel = findViewById(R.id.pm_compose_panel)
        pmComposeTitle = findViewById(R.id.pm_compose_title)
        pmComposeTo = findViewById(R.id.pm_compose_to)
        pmComposeSubject = findViewById(R.id.pm_compose_subject)
        pmComposeMessage = findViewById(R.id.pm_compose_message)
        pmComposeSend = findViewById(R.id.pm_compose_send)
        findViewById<View>(R.id.pm_compose_cancel).setOnClickListener { cancelPmCompose() }
        pmComposeSend.setOnClickListener { sendPm() }
    }

    /** Oculta los paneles de MP y el de perfil (al navegar a cualquier destino estándar). */
    private fun hidePmPanels() {
        isPmVisible = false; isPmDetailVisible = false; isPmComposeVisible = false
        isMemberVisible = false
        pmPanel.visibility = View.GONE
        pmDetailPanel.visibility = View.GONE
        pmComposePanel.visibility = View.GONE
        memberPanel.visibility = View.GONE
    }

    /** Oculta todas las capas estándar (para que un panel de MP/perfil quede solo). */
    private fun hideAllStandardPanels() {
        nativePanel.visibility = View.GONE
        threadPanel.visibility = View.GONE
        replyPanel.visibility = View.GONE
        loginPanel.visibility = View.GONE
        noticesPanel.visibility = View.GONE
        profilePanel.visibility = View.GONE
        optionsPanel.visibility = View.GONE
        memberPanel.visibility = View.GONE
        isMemberVisible = false
    }

    /** Bandeja de MPs nativa (la regla de oro prohíbe la capa web). */
    private fun showPmInbox() {
        if (!isLoggedIn()) { toast("Inicia sesión para ver tus mensajes"); showLogin(); return }
        isPmVisible = true; isPmDetailVisible = false; isPmComposeVisible = false
        isWebVisible = false; isThreadVisible = false; isReplyVisible = false
        isLoginVisible = false; isNoticesVisible = false; isProfileVisible = false; isOptionsVisible = false
        hideAllStandardPanels()
        pmPanel.visibility = View.VISIBLE
        pmDetailPanel.visibility = View.GONE
        pmComposePanel.visibility = View.GONE
        bottomNav.visibility = View.VISIBLE
        swipeRefresh.visibility = View.INVISIBLE
        setSelectedNav(R.id.nav_profile)
        pmAdapter.submit(emptyList())
        pmEmpty.visibility = View.GONE
        pmLoading.visibility = View.VISIBLE
        webView.evaluateJavascript("window.fcLoadPmInbox&&fcLoadPmInbox()", null)
    }

    private fun showPmDetail(pmid: String, subject: String) {
        currentPmId = pmid
        currentPmSubject = subject
        isPmDetailVisible = true; isPmVisible = false; isPmComposeVisible = false
        hideAllStandardPanels()
        pmPanel.visibility = View.GONE
        pmComposePanel.visibility = View.GONE
        pmDetailPanel.visibility = View.VISIBLE
        bottomNav.visibility = View.VISIBLE
        swipeRefresh.visibility = View.INVISIBLE
        pmDetailSubject.text = subject
        pmDetailSender.text = ""
        pmDetailBody.text = "Cargando…"
        pmDetailReply.visibility = View.GONE
        webView.evaluateJavascript("window.fcLoadPm&&fcLoadPm('${jsEscape(pmid)}')", null)
    }

    private fun showPmCompose(mode: String, pmid: String, subject: String) {
        pmComposeMode = mode
        isPmComposeVisible = true; isPmVisible = false; isPmDetailVisible = false
        hideAllStandardPanels()
        pmPanel.visibility = View.GONE
        pmDetailPanel.visibility = View.GONE
        pmComposePanel.visibility = View.VISIBLE
        bottomNav.visibility = View.GONE   // hay inputs de texto: teclado a pantalla limpia
        swipeRefresh.visibility = View.INVISIBLE
        sendingPm = false
        pmComposeSend.isEnabled = true
        pmComposeSend.text = "Enviar"
        pmComposeMessage.setText("")
        if (mode == "reply") {
            pmComposeTitle.text = "Responder"
            pmComposeTo.visibility = View.GONE
            pmComposeSubject.visibility = View.GONE
        } else {
            pmComposeTitle.text = "Nuevo mensaje"
            pmComposeTo.visibility = View.VISIBLE
            pmComposeSubject.visibility = View.VISIBLE
            pmComposeTo.setText("")
            pmComposeSubject.setText("")
        }
        pmComposeMessage.requestFocus()
    }

    private fun cancelPmCompose() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(pmComposePanel.windowToken, 0)
        if (pmComposeMode == "reply" && currentPmId.isNotEmpty()) showPmDetail(currentPmId, currentPmSubject)
        else showPmInbox()
    }

    private fun sendPm() {
        if (sendingPm) return
        val message = pmComposeMessage.text.toString().trim()
        if (message.isEmpty()) { toast("Escribe un mensaje"); return }
        val recipients: String
        val title: String
        val pmid: String
        if (pmComposeMode == "reply") {
            recipients = ""; title = ""; pmid = currentPmId
        } else {
            recipients = pmComposeTo.text.toString().trim()
            title = pmComposeSubject.text.toString().trim()
            pmid = ""
            if (recipients.isEmpty()) { toast("Indica el destinatario"); return }
            if (title.isEmpty()) { toast("Escribe un asunto"); return }
        }
        sendingPm = true
        pmComposeSend.isEnabled = false
        pmComposeSend.text = "…"
        webView.evaluateJavascript(
            "window.fcSendPm&&fcSendPm('${jsEscape(recipients)}','${jsEscape(title)}'," +
                "'${jsEscape(message)}','${jsEscape(pmid)}')", null
        )
    }

    /** Callback del motor para todos los estados de MP (bandeja/detalle/envío). */
    private fun onPmData(json: String) {
        val o = try { org.json.JSONObject(json) } catch (_: Exception) { return }
        when (o.optString("view")) {
            "inbox" -> {
                if (!isPmVisible) return
                pmLoading.visibility = View.GONE
                val err = o.optString("error", "")
                if (err == "cloudflare") {
                    // La regla de oro admite el challenge de CF como única excepción.
                    showWeb(); webView.loadUrl("https://forocoches.com/foro/private.php"); return
                }
                val items = parsePmInbox(json)
                pmAdapter.submit(items)
                if (items.isEmpty()) {
                    pmEmpty.visibility = View.VISIBLE
                    pmEmpty.text = if (err.isNotEmpty()) "No se pudieron cargar los mensajes"
                        else "No tienes mensajes privados"
                }
            }
            "detail" -> {
                if (!isPmDetailVisible) return
                if (o.optString("pmid") != currentPmId) return
                val err = o.optString("error", "")
                if (err.isNotEmpty()) { pmDetailBody.text = "No se pudo cargar el mensaje"; return }
                val sender = o.optString("sender").trim()
                pmDetailSender.text = if (sender.isNotEmpty()) "@$sender" else ""
                val subj = o.optString("subject").trim()
                if (subj.isNotEmpty()) { pmDetailSubject.text = subj; currentPmSubject = subj }
                pmDetailBody.text = androidx.core.text.HtmlCompat.fromHtml(
                    o.optString("body"), androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
                )
                pmDetailReply.visibility = if (o.optBoolean("canReply", false)) View.VISIBLE else View.GONE
            }
            "send" -> {
                sendingPm = false
                pmComposeSend.isEnabled = true
                pmComposeSend.text = "Enviar"
                if (o.optBoolean("ok", false)) {
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(pmComposePanel.windowToken, 0)
                    toast("Mensaje enviado")
                    showPmInbox()
                } else {
                    val err = o.optString("error", "")
                    toast(if (err.isNotEmpty()) err else "No se pudo enviar el mensaje")
                }
            }
        }
    }

    // ── Perfil de otro usuario (mención tocada) ───────────────────────────────

    private fun configureMemberPanel() {
        memberPanel = findViewById(R.id.member_panel)
        memberAvatar = findViewById(R.id.member_avatar)
        memberName = findViewById(R.id.member_name)
        memberPmBtn = findViewById(R.id.member_pm)
        memberOpenWeb = findViewById(R.id.member_open_web)
        findViewById<View>(R.id.member_back).setOnClickListener { exitMemberProfile() }
        memberPmBtn.setOnClickListener {
            if (!isLoggedIn()) { toast("Inicia sesión para enviar mensajes"); showLogin(); return@setOnClickListener }
            if (currentMemberUsername.isEmpty()) { toast("Perfil aún cargando…"); return@setOnClickListener }
            showPmCompose("new", "", "")
            pmComposeTo.setText(currentMemberUsername)
            pmComposeSubject.requestFocus()
        }
        memberOpenWeb.setOnClickListener {
            if (currentMemberUid.isNotEmpty())
                openExternal("https://forocoches.com/foro/member.php?u=$currentMemberUid")
        }
    }

    /** Muestra el perfil NATIVO de un usuario (la regla de oro prohíbe la web de FC). */
    private fun showMemberProfile(uid: String) {
        currentMemberUid = uid
        currentMemberUsername = ""
        // Ocultar todo primero; el flag isMemberVisible se pone AL FINAL (los helpers lo resetean).
        hideAllStandardPanels()
        pmPanel.visibility = View.GONE
        pmDetailPanel.visibility = View.GONE
        pmComposePanel.visibility = View.GONE
        isPmVisible = false; isPmDetailVisible = false; isPmComposeVisible = false
        isWebVisible = false; isThreadVisible = false; isReplyVisible = false
        isLoginVisible = false; isNoticesVisible = false; isProfileVisible = false; isOptionsVisible = false
        memberPanel.visibility = View.VISIBLE
        isMemberVisible = true
        bottomNav.visibility = View.VISIBLE
        swipeRefresh.visibility = View.INVISIBLE
        memberName.text = "…"
        memberAvatar.setImageDrawable(null)
        webView.evaluateJavascript("window.fcLoadMember&&fcLoadMember('${jsEscape(uid)}')", null)
    }

    private fun exitMemberProfile() {
        isMemberVisible = false
        memberPanel.visibility = View.GONE
        // Las menciones se tocan dentro de un hilo: volver a él (los posts siguen en memoria).
        if (currentThreadUrl.isNotEmpty() && postAdapter.itemCount > 0) showThread()
        else { showNative(); setSelectedNav(navIdForList()) }
    }

    private fun onMemberData(json: String) {
        if (!isMemberVisible) return
        val o = try { org.json.JSONObject(json) } catch (_: Exception) { return }
        if (o.optString("uid") != currentMemberUid) return
        if (o.optString("error").isNotEmpty()) { memberName.text = "No se pudo cargar el perfil"; return }
        val username = o.optString("username").trim()
        currentMemberUsername = username
        memberName.text = if (username.isNotEmpty()) "@$username" else "Perfil"
        val avatar = o.optString("avatar").trim()
        if (avatar.isNotEmpty() && !avatar.endsWith(".svg")) {
            PostImages.get(avatar)?.let { memberAvatar.setImageBitmap(it) }
                ?: PostImages.load(avatar) {
                    if (isMemberVisible && currentMemberUid == o.optString("uid"))
                        PostImages.get(avatar)?.let { memberAvatar.setImageBitmap(it) }
                }
        }
    }

    /** Citas o menciones AISLADAS en su propia página (no el batiburrillo de FC). */
    private fun showNotices(kind: String) {
        val url = if (kind == "quotes") menuLinks?.quotes else menuLinks?.mentions
        if (url == null) { toast("Conectando con el foro…"); return }
        currentNoticesKind = kind
        isNoticesVisible = true
        isWebVisible = false; isThreadVisible = false; isReplyVisible = false
        isLoginVisible = false; isProfileVisible = false; isOptionsVisible = false
        hidePmPanels()
        noticesPanel.visibility = View.VISIBLE
        nativePanel.visibility = View.GONE
        threadPanel.visibility = View.GONE
        replyPanel.visibility = View.GONE
        loginPanel.visibility = View.GONE
        profilePanel.visibility = View.GONE
        swipeRefresh.visibility = View.INVISIBLE
        bottomNav.visibility = View.VISIBLE
        setSelectedNav(if (kind == "quotes") R.id.nav_quotes else R.id.nav_notif)
        noticesHeader.text = if (kind == "quotes") "Citas" else "Menciones"
        noticeAdapter.submit(emptyList())
        noticesEmpty.visibility = View.GONE
        noticesLoading.visibility = View.VISIBLE
        webView.evaluateJavascript(
            "window.fcLoadNotices&&fcLoadNotices('${jsEscape(url)}','$kind')", null
        )
    }

    private fun onNoticesJson(json: String) {
        if (!isNoticesVisible) return
        noticesLoading.visibility = View.GONE
        val p = parseNoticesPayload(json) ?: return
        if (p.kind != currentNoticesKind) return
        if (p.error == "cloudflare") {
            showWeb()
            webView.loadUrl(if (currentNoticesKind == "quotes") menuLinks?.quotes ?: "" else menuLinks?.mentions ?: "")
            return
        }
        noticeAdapter.submit(p.items)
        if (p.items.isEmpty()) {
            noticesEmpty.visibility = View.VISIBLE
            noticesEmpty.text = if (currentNoticesKind == "quotes")
                "No tienes citas recientes" else "No tienes menciones recientes"
        }
    }

    /** Perfil nativo: nombre + avatar + cerrar sesión (el foro no se ve). */
    private fun showProfile() {
        isProfileVisible = true
        isWebVisible = false; isThreadVisible = false; isReplyVisible = false
        isLoginVisible = false; isNoticesVisible = false; isOptionsVisible = false
        hidePmPanels()
        profilePanel.visibility = View.VISIBLE
        nativePanel.visibility = View.GONE
        threadPanel.visibility = View.GONE
        replyPanel.visibility = View.GONE
        loginPanel.visibility = View.GONE
        noticesPanel.visibility = View.GONE
        optionsPanel.visibility = View.GONE
        swipeRefresh.visibility = View.INVISIBLE
        bottomNav.visibility = View.VISIBLE
        setSelectedNav(R.id.nav_profile)
        val url = menuLinks?.profile
        if (profileName.text.isNullOrEmpty()) profileName.text = "…"
        if (url != null) {
            webView.evaluateJavascript("window.fcLoadProfile&&fcLoadProfile('${jsEscape(url)}')", null)
        }
    }

    private fun onProfileJson(json: String) {
        try {
            val o = org.json.JSONObject(json)
            val name = o.optString("name")
            if (name.isNotEmpty()) profileName.text = name
            profileLogoutUrl = o.optString("logout", profileLogoutUrl)
            val av = o.optString("avatar")
            if (av.isNotEmpty()) {
                PostImages.get(av)?.let { profileAvatar.setImageBitmap(it) } ?: PostImages.load(av) {
                    PostImages.get(av)?.let { profileAvatar.setImageBitmap(it) }
                }
            }
        } catch (_: Exception) { }
    }

    // ── Opciones ─────────────────────────────────────────────────────────────

    private fun showOptions() {
        isOptionsVisible = true
        isNoticesVisible = false; isProfileVisible = false
        hidePmPanels()
        options.bind()
        optionsPanel.visibility = View.VISIBLE
        nativePanel.visibility = View.GONE
        threadPanel.visibility = View.GONE
        replyPanel.visibility = View.GONE
        loginPanel.visibility = View.GONE
        noticesPanel.visibility = View.GONE
        profilePanel.visibility = View.GONE
        swipeRefresh.visibility = View.INVISIBLE
        bottomNav.visibility = View.GONE   // tiene inputs de texto: teclado a pantalla limpia
    }

    private fun hideOptions() {
        isOptionsVisible = false
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(optionsPanel.windowToken, 0)
        optionsPanel.visibility = View.GONE
        bottomNav.visibility = View.VISIBLE
        // Vuelve a la lista (donde está el engranaje).
        showNative()
        setSelectedNav(navIdForList())
    }

    private fun doLogout() {
        if (profileLogoutUrl.isEmpty()) { toast("No se pudo cerrar sesión"); return }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Cerrar sesión")
            .setMessage("¿Seguro que quieres salir de tu cuenta?")
            .setPositiveButton("Salir") { _, _ ->
                webView.evaluateJavascript("window.fcLogout&&fcLogout('${jsEscape(profileLogoutUrl)}')", null)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun onLogoutDone(result: String) {
        CookieManager.getInstance().flush()
        if (isLoggedIn()) {
            toast("No se pudo cerrar sesión")
            return
        }
        toast("Sesión cerrada")
        profileLogoutUrl = ""
        profileName.text = ""
        menuLinks = null
        updateAccountNavItem()
        updateBadges(0, 0, 0)
        listLoaded = false
        adapter.submit(emptyList())
        showHomeList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        swipeRefresh = findViewById(R.id.swipe_refresh)
        webView = findViewById(R.id.webview)
        nativePanel = findViewById(R.id.native_panel)
        listRefresh = findViewById(R.id.list_refresh)
        threadList = findViewById(R.id.thread_list)
        listLoading = findViewById(R.id.list_loading)
        listEmpty = findViewById(R.id.list_empty)
        bottomNav = findViewById(R.id.bottom_nav)
        forumTabs = findViewById(R.id.forum_tabs)
        threadPanel = findViewById(R.id.thread_panel)
        postList = findViewById(R.id.post_list)
        threadLoading = findViewById(R.id.thread_loading)
        threadTitle = findViewById(R.id.thread_title)
        threadPageInfo = findViewById(R.id.thread_page_info)
        threadPageInfo.setOnClickListener { if (threadPageCount > 1) showPageJumpSheet() }
        threadFav = findViewById(R.id.thread_fav)
        threadFav.setOnClickListener { toggleFavorite() }
        nativeHeader = findViewById(R.id.native_header)
        noticesPanel = findViewById(R.id.notices_panel)
        noticesHeader = findViewById(R.id.notices_header)
        noticesList = findViewById(R.id.notices_list)
        noticesLoading = findViewById(R.id.notices_loading)
        noticesEmpty = findViewById(R.id.notices_empty)
        profilePanel = findViewById(R.id.profile_panel)
        profileAvatar = findViewById(R.id.profile_avatar)
        profileName = findViewById(R.id.profile_name)
        currentForumId = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_LAST_FID, 2)

        applyWindowInsets()
        configureWebView()
        configureSwipeRefresh()
        configureShell()
        // Atrás del sistema por el dispatcher (API 36 no llama a onBackPressed()).
        onBackPressedDispatcher.addCallback(this, onBackCallback)

        // El WebView arranca OCULTO como motor: al terminar de cargar, extractor.js queda
        // inyectado y pedimos el listado por fetch same-origin (nunca HTTP nativo).
        val startUrl = TrustedOrigins.trustedUrlOrDefault(intent.getStringExtra("url"))
        if (startUrl != TrustedOrigins.DEFAULT_URL &&
            (startUrl.contains("showthread.php") || startUrl.contains("private.php"))) {
            // Deep link de hilo o MP en frío: el motor aún no está listo, así que se guarda
            // y se abre en NATIVO en onEnginePageReady. Nada de enseñar el foro web.
            pendingDeepLink = startUrl
            showNative()
            listLoading.visibility = View.VISIBLE
            webView.loadUrl(TrustedOrigins.DEFAULT_URL)
        } else if (startUrl != TrustedOrigins.DEFAULT_URL) {
            showWeb()
            webView.loadUrl(startUrl)
        } else {
            showNative()
            listLoading.visibility = View.VISIBLE
            webView.loadUrl(TrustedOrigins.DEFAULT_URL)
        }

        fetchIgnoreListIfNeeded()
        requestNotificationPermission()
        startNotificationPolling()
        if (NotificationRepository(this).isInstantEnabled()) NotificationService.start(this)
        maybeShowWelcome()
    }

    /**
     * Popup de bienvenida: se muestra UNA sola vez (primer arranque). Agradece e invita a la
     * comunidad de Telegram. El "café" NO va aquí a propósito (queda en Opciones).
     */
    private fun maybeShowWelcome() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(PREF_WELCOME_SHOWN, false)) return
        prefs.edit().putBoolean(PREF_WELCOME_SHOWN, true).apply()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("¡Gracias por confiar en ForoPlus! 👋")
            .setMessage(
                "Soy un desarrollador independiente y hago esta app en mi tiempo libre, sin ánimo " +
                "de lucro. Que la uses ya es la mejor recompensa. Si quieres estar al día y proponer " +
                "mejoras, únete a la comunidad:"
            )
            .setPositiveButton("Unirme a la comunidad") { _, _ ->
                openExternal("https://t.me/foroplus")
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /**
     * Android 15 (targetSdk 35) fuerza dibujar edge-to-edge. Aplicamos los insets como
     * padding del contenedor raíz (la barra inferior queda sobre la de navegación).
     */
    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.root_container)
        root.setBackgroundColor(android.graphics.Color.WHITE)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // El teclado (IME) empuja el contenido hacia arriba para que el composer no quede tapado.
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // Respeta el tamaño de letra configurado en el sistema (accesibilidad), sin UI extra.
            textZoom = (this@MainActivity.resources.configuration.fontScale * 100).toInt()
            userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }
        repo = IgnoreListRepository(this)
        val notifRepo = NotificationRepository(this)
        keywordRepo = KeywordRepository(this)
        webView.webViewClient = ForocochesWebViewClient(
            this, repo, keywordRepo,
            onPageLoad = { url ->
                swipeRefresh.isRefreshing = false
                onEnginePageReady(url)
            }
        )
        webView.addJavascriptInterface(SettingsBridge(repo, notifRepo, keywordRepo, webView), "Android")
        webView.addJavascriptInterface(
            ShellBridge(
                onList = { json -> runOnUiThread { onThreadListJson(json) } },
                onError = { reason -> runOnUiThread { onThreadListError(reason) } },
                onForums = { json -> runOnUiThread { onForumListJson(json) } },
                onThreadData = { json -> runOnUiThread { onThreadJson(json) } },
                onThreadDataError = { reason -> runOnUiThread { onThreadError(reason) } },
                onReply = { json -> runOnUiThread { onReplyResult(json) } },
                onLogin = { json -> runOnUiThread { onLoginResult(json) } },
                onSmiliesData = { json -> runOnUiThread { onSmiliesJson(json) } },
                onNoticesData = { json -> runOnUiThread { onNoticesJson(json) } },
                onProfileData = { json -> runOnUiThread { onProfileJson(json) } },
                onLogout = { result -> runOnUiThread { onLogoutDone(result) } },
                onThreadActionData = { json -> runOnUiThread { onThreadActionResult(json) } },
                onEditLoadData = { json -> runOnUiThread { onEditLoad(json) } },
                onPmDataResult = { json -> runOnUiThread { onPmData(json) } },
                onMemberDataResult = { json -> runOnUiThread { onMemberData(json) } }
            ),
            "AndroidShell"
        )
    }

    private fun configureSwipeRefresh() {
        swipeRefresh.setColorSchemeColors(android.graphics.Color.parseColor("#C8102E"))
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> webView.canScrollVertically(-1) }
        swipeRefresh.setOnRefreshListener { webView.reload() }
    }

    // ── Shell nativo ─────────────────────────────────────────────────────────

    private fun configureShell() {
        adapter = ThreadListAdapter { item -> openThreadNative(item.url, item.title) }
        configureThreadPanel()

        // Secciones nativas (Bloque B): citas/menciones y perfil.
        noticeAdapter = NoticeAdapter { n -> openThreadNative(n.url, n.title) }
        noticesList.layoutManager = LinearLayoutManager(this)
        noticesList.adapter = noticeAdapter

        fabNewThread = findViewById(R.id.fab_new_thread)
        fabNewThread.setOnClickListener {
            if (!isLoggedIn()) { showLogin(); return@setOnClickListener }
            openNewThread()
        }

        // Opciones nativas (fuente, avatar, filtros ignorados/keywords).
        optionsPanel = findViewById(R.id.options_panel)
        options = OptionsController(
            panel = optionsPanel,
            ignoreRepo = repo,
            keywordRepo = keywordRepo,
            prefs = getSharedPreferences(PREFS, MODE_PRIVATE),
            onFontChanged = { postAdapter.postTextSp = OptionsController.fontSp(getSharedPreferences(PREFS, MODE_PRIVATE)) },
            onListsChanged = {
                // Re-aplica filtros al vuelo: recarga lista y, si hay, el hilo abierto.
                listLoaded = false
                loadingPage = false
                requestThreadList(1)
                if (currentThreadUrl.isNotEmpty()) reloadCurrentThread()
            },
            openUrl = { url -> openWeb(url) }
        )
        // Comunidad y apoyo: enlaces EXTERNOS (Telegram / navegador), nunca la capa web.
        optionsPanel.findViewById<View>(R.id.opt_telegram).setOnClickListener {
            openExternal("https://t.me/foroplus")
        }
        optionsPanel.findViewById<View>(R.id.opt_coffee).setOnClickListener {
            openExternal("https://paypal.me/neonforger")
        }
        postAdapter.postTextSp = OptionsController.fontSp(getSharedPreferences(PREFS, MODE_PRIVATE))
        findViewById<View>(R.id.native_options).setOnClickListener { showOptions() }
        findViewById<View>(R.id.native_search).setOnClickListener { showSearchSheet() }
        findViewById<View>(R.id.options_back).setOnClickListener { hideOptions() }
        configurePmPanels()
        configureMemberPanel()
        findViewById<View>(R.id.profile_logout).setOnClickListener { doLogout() }
        findViewById<View>(R.id.profile_pms).setOnClickListener { showPmInbox() }
        findViewById<View>(R.id.profile_open_web).setOnClickListener {
            val u = menuLinks?.profile ?: return@setOnClickListener
            try {
                startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u))
                        .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                )
            } catch (_: Exception) { }
        }
        val layoutManager = LinearLayoutManager(this)
        threadList.layoutManager = layoutManager
        threadList.adapter = adapter
        listRefresh.setColorSchemeColors(android.graphics.Color.parseColor("#C8102E"))
        listRefresh.setOnRefreshListener { requestThreadList(1) }

        // Scroll infinito BAJO DEMANDA (regla anti-crawler: solo se pide la página
        // siguiente cuando el usuario se acerca al final, nunca precarga en bucle).
        threadList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loadingPage || !listLoaded) return
                val last = layoutManager.findLastVisibleItemPosition()
                if (adapter.itemCount > 0 && last >= adapter.itemCount - 8) {
                    requestThreadList(currentPage + 1)
                }
            }
        })

        configureBottomBar()
        updateAccountNavItem()

        forumTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (populatingTabs) return
                val fid = tab.tag as? Int ?: return
                currentForumId = fid
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_LAST_FID, fid).apply()
                listLoaded = false
                adapter.submit(emptyList())
                showNative()
                requestThreadList(1)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) { requestThreadList(1) }
        })
    }

    /** Rellena las pestañas con los subforos reales del índice y marca el actual. */
    private fun onForumListJson(json: String) {
        val forums = parseForumListPayload(json)
        if (forums.isEmpty()) return
        populatingTabs = true
        forumTabs.removeAllTabs()
        var selectIdx = 0
        forums.forEachIndexed { idx, f ->
            val tab = forumTabs.newTab().setText(f.name)
            tab.tag = f.fid
            forumTabs.addTab(tab, false)
            if (f.fid == currentForumId) selectIdx = idx
        }
        forumTabs.getTabAt(selectIdx)?.select()
        populatingTabs = false
    }

    // ── Hilo nativo (Fase 2) ─────────────────────────────────────────────────

    private fun configureThreadPanel() {
        replyPanel = findViewById(R.id.reply_panel)
        replyInput = findViewById(R.id.reply_input)
        replySend = findViewById(R.id.reply_send)
        replyCancel = findViewById(R.id.reply_cancel)
        replyQuotesContainer = findViewById(R.id.reply_quotes)
        replyHeaderTitle = findViewById(R.id.reply_header_title)
        replySubject = findViewById(R.id.reply_subject)
        replySend.setOnClickListener { submitReply() }
        replyCancel.setOnClickListener { hideReply() }

        // Barra de formato BBCode (Bloque A): el editor propio es SIEMPRE el por defecto.
        bbcode = BbcodeEditor(this, replyInput)
        findViewById<View>(R.id.fmt_bold).setOnClickListener { bbcode.wrap("[B]", "[/B]") }
        findViewById<View>(R.id.fmt_italic).setOnClickListener { bbcode.wrap("[I]", "[/I]") }
        findViewById<View>(R.id.fmt_under).setOnClickListener { bbcode.wrap("[U]", "[/U]") }
        findViewById<View>(R.id.fmt_color).setOnClickListener { bbcode.pickColor() }
        findViewById<View>(R.id.fmt_size).setOnClickListener { bbcode.pickSize() }
        findViewById<View>(R.id.fmt_align).setOnClickListener { bbcode.pickAlign() }
        findViewById<View>(R.id.fmt_list).setOnClickListener { bbcode.pickList() }
        findViewById<View>(R.id.fmt_img).setOnClickListener { bbcode.askImg() }
        findViewById<View>(R.id.fmt_url).setOnClickListener { bbcode.askUrl() }
        findViewById<View>(R.id.fmt_embed).setOnClickListener { bbcode.pickEmbed() }
        findViewById<View>(R.id.fmt_quote).setOnClickListener { bbcode.wrap("[QUOTE]", "[/QUOTE]") }
        findViewById<View>(R.id.fmt_spoiler).setOnClickListener { bbcode.wrap("[SPOILER]", "[/SPOILER]") }
        findViewById<View>(R.id.fmt_smiley).setOnClickListener { openSmileyPicker() }

        postAdapter = PostAdapter(
            onLinkClick = { url -> onPostLinkClick(url) },
            // Citar: añade la cita y abre la pestaña de respuesta.
            onQuote = { post -> quotePost(post) },
            // "+": alterna la cita en la respuesta (sin abrir el panel).
            onMultiquoteToggle = { post -> toggleMultiquote(post) },
            isSelected = { pid -> replyQuotes.containsKey(pid) },
            onMenu = { post, anchor -> showPostMenu(post, anchor) },
            onEmbedFullscreen = { view, cb -> onEmbedFullscreen(view, cb) }
        )
        val lm = LinearLayoutManager(this)
        postList.layoutManager = lm
        postList.adapter = postAdapter
        // Página siguiente bajo demanda al acercarse al final (misma regla anti-crawler).
        postList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (postAdapter.itemCount == 0) return
                // Indicador de página según lo que se VE (sube y baja con el scroll,
                // en ambas direcciones), no según lo último cargado.
                val first = lm.findFirstVisibleItemPosition()
                if (first >= 0) showThreadPageInfo(postAdapter.pageAt(first))
                // Hacia arriba: traer la página anterior si la hay (tras un salto).
                if (dy < 0 && !loadingThreadPage && firstLoadedPage > 1 && first <= 3) {
                    requestPrevThreadPage()
                    return
                }
                if (dy <= 0 || loadingThreadPage) return
                if (threadPage >= threadPageCount) return
                val last = lm.findLastVisibleItemPosition()
                if (last >= postAdapter.itemCount - 5) {
                    requestThreadPage(threadPage + 1)
                }
            }
        })
        findViewById<View>(R.id.thread_open_web).setOnClickListener {
            if (currentThreadUrl.isNotEmpty()) {
                cameFromThread = true
                showWeb()
                webView.loadUrl(threadPageUrl(threadPage))
            }
        }
        // Responder: abre la pestaña de respuesta nativa (con las multicitas pendientes,
        // si las hay). El texto se manda con fcSubmitReply, que reusa el securitytoken
        // del form real de FC (cero reimplementación del posteo).
        findViewById<View>(R.id.thread_reply).setOnClickListener {
            if (currentThreadTid.isEmpty()) return@setOnClickListener
            if (!isLoggedIn()) { showLogin(); return@setOnClickListener }
            openReply()
        }
        restrictedView = findViewById(R.id.thread_restricted)
        restrictedMsg = findViewById(R.id.restricted_msg)
        restrictedMeta = findViewById(R.id.restricted_meta)
        restrictedLogin = findViewById(R.id.restricted_login)
        restrictedInvite = findViewById(R.id.restricted_invite)
        restrictedLogin.setOnClickListener { showLogin() }
        restrictedInvite.setOnClickListener {
            // Guía de invitaciones: fuera de la app (el foro no se ve dentro).
            if (restrictedInviteUrl.isNotEmpty()) {
                try {
                    startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(restrictedInviteUrl)
                        ).addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                    )
                } catch (_: Exception) { }
            }
        }
        configureLoginPanel()
    }

    /** Hilo restringido: reproduce la info de FC con nuestros estilos + acceso al login. */
    private fun showRestricted(msg: String, meta: String, inviteUrl: String) {
        if (!isLoggedIn()) {
            // El login (botón o pestaña) reabrirá este hilo al entrar.
            pendingThreadUrl = currentThreadUrl
            pendingThreadTitle = threadTitle.text.toString()
        }
        restrictedMsg.text = msg.ifEmpty { "Este hilo no está disponible con tu cuenta actual" }
        restrictedMeta.text = meta
        restrictedMeta.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
        restrictedLogin.visibility = if (isLoggedIn()) View.GONE else View.VISIBLE
        restrictedInviteUrl = inviteUrl
        restrictedInvite.visibility = if (inviteUrl.isEmpty()) View.GONE else View.VISIBLE
        restrictedView.visibility = View.VISIBLE
    }

    /** Indicador "Página X de Y" de la cabecera; con varias páginas es el botón de salto. */
    private fun showThreadPageInfo(visiblePage: Int) {
        val multi = threadPageCount > 1
        threadPageInfo.text = if (multi) "Página $visiblePage de $threadPageCount  ▾" else ""
        threadPageInfo.isClickable = multi
        threadPageInfo.setTextColor(if (multi) 0xFF757575.toInt() else 0xFF9E9E9E.toInt())
    }

    // ── Panel de respuesta nativo (Fase 3) ───────────────────────────────────

    private fun quoteBlock(p: PostItem): String =
        "[QUOTE=${p.author};${p.pid}]${postAdapter.quoteBodyOf(p)}[/QUOTE]\n"

    /** "Citar" en un post: lo añade a la respuesta y abre la pestaña de respuesta. */
    private fun quotePost(post: PostItem) {
        if (!isLoggedIn()) { showLogin(); return }
        replyQuotes[post.pid] = post
        postAdapter.refreshSelection()
        openReply()
    }

    /** "+" en un post: alterna su cita en la respuesta en curso (sin abrir el panel). */
    private fun toggleMultiquote(post: PostItem) {
        val added = if (replyQuotes.containsKey(post.pid)) {
            replyQuotes.remove(post.pid); false
        } else {
            replyQuotes[post.pid] = post; true
        }
        postAdapter.refreshSelection()
        if (isReplyVisible) renderReplyQuotes()
        if (added) {
            val n = replyQuotes.size
            toast(if (n > 1) "Cita añadida ($n)" else "Cita añadida")
        }
    }

    /** Abre la pantalla de escritura en el modo dado (reply | edit | newthread). */
    private fun showComposer(mode: String, title: String, showSubject: Boolean) {
        replyMode = mode
        isReplyVisible = true
        hidePmPanels()
        replyPanel.visibility = View.VISIBLE
        threadPanel.visibility = View.GONE
        nativePanel.visibility = View.GONE
        // Pantalla de escritura = sin barra de navegación: el teclado ocupa su hueco
        // y se escribe cómodo (la barra no "sube" con el teclado).
        bottomNav.visibility = View.GONE
        replyHeaderTitle.text = title
        replySubject.visibility = if (showSubject) View.VISIBLE else View.GONE
        renderReplyQuotes()
        val focus = if (showSubject) replySubject else replyInput
        focus.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(focus, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun openReply() = showComposer("reply", "Responder", showSubject = false)

    /** FAB: nuevo hilo en el subforo actual (asunto + editor). */
    private fun openNewThread() {
        replyQuotes.clear()
        postAdapter.refreshSelection()
        replyInput.setText("")
        replySubject.setText("")
        editingPid = ""
        showComposer("newthread", "Nuevo hilo", showSubject = true)
    }

    /** Menú ⋮ de un post propio: editar o borrar. */
    private fun showPostMenu(post: PostItem, anchor: View) {
        val menu = androidx.appcompat.widget.PopupMenu(this, anchor)
        if (post.own) {
            menu.menu.add(0, 1, 0, "Editar")
            menu.menu.add(0, 2, 1, "Borrar")
        } else {
            menu.menu.add(0, 3, 0, "Reportar")
        }
        menu.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> { startEdit(post); true }
                2 -> { confirmDelete(post); true }
                3 -> { showReportDialog(post); true }
                else -> false
            }
        }
        menu.show()
    }

    /**
     * Diálogo NATIVO de reporte (replica report.php de FC: comentario + motivo). Al confirmar,
     * el envío pasa el Cloudflare por un WebView tapado con capa nativa y auto-envía el form real.
     */
    private fun showReportDialog(post: PostItem) {
        val view = layoutInflater.inflate(R.layout.dialog_report, null)
        val comment = view.findViewById<EditText>(R.id.report_comment)
        val commentBox = view.findViewById<View>(R.id.report_comment_box)
        val group = view.findViewById<android.widget.RadioGroup>(R.id.report_reasons)
        // Etiqueta FC de cada motivo: el auto-submit la empareja con el radio real del formulario.
        val reasonLabels = mapOf(
            R.id.reason_18 to "+18", R.id.reason_spam to "Spam", R.id.reason_troll to "Troll",
            R.id.reason_flood to "Flood", R.id.reason_content to "Contenido", R.id.reason_other to "Otros"
        )
        // El comentario solo tiene sentido en "Otros" (detalle del reporte): aparece al marcarlo.
        group.setOnCheckedChangeListener { _, checkedId ->
            commentBox.visibility = if (checkedId == R.id.reason_other) View.VISIBLE else View.GONE
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Reportar mensaje de ${post.author}")
            .setView(view)
            .setPositiveButton("Enviar reporte", null) // se sobreescribe abajo para validar sin cerrar
            .setNegativeButton("Cancelar", null)
            .create().apply {
                setOnShowListener {
                    getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val reason = reasonLabels[group.checkedRadioButtonId]
                        if (reason == null) { toast("Elige un motivo"); return@setOnClickListener }
                        val txt = comment.text.toString().trim()
                        if (reason == "Otros" && txt.isEmpty()) { toast("Escribe el motivo del reporte"); return@setOnClickListener }
                        dismiss()
                        submitReport(post, reason, txt)
                    }
                }
            }.show()
    }

    // ── Reporte: WebView invisible (tapado por capa nativa) que resuelve el Cloudflare ────────
    private var reportOverlay: android.widget.FrameLayout? = null
    private var reportWeb: android.webkit.WebView? = null
    private var reportPoll: Runnable? = null
    private var reportCover: View? = null

    private fun submitReport(post: PostItem, reason: String, comment: String) {
        startReportFlow(post, reason, comment)
    }

    /**
     * report.php está tras un Cloudflare challenge por-ruta que un fetch NO puede resolver (solo un
     * navegador VISIBLE que renderice). Truco: un WebView a pantalla completa que SÍ renderiza (así
     * el challenge se resuelve) pero TAPADO por una capa nativa opaca → el usuario nunca ve el foro
     * web (regla de oro). Cuando cae el formulario real, [FASE 3] se auto-rellena y envía.
     */
    private fun startReportFlow(post: PostItem, reason: String, comment: String) {
        // El content FrameLayout (NO el LinearLayout vertical root_container): un hijo a pantalla
        // completa se superpone limpiamente sin descolocar la barra inferior.
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        val overlay = android.widget.FrameLayout(this)
        val wv = object : android.webkit.WebView(this) {
            // Chromium throttla el render de un WebView que Android considera NO visible; al taparlo
            // con una capa opaca, onVisibilityAggregated pasa a false y el challenge de CF se congela.
            // Forzamos "visible" para que siga renderizando por debajo del cover y resuelva el CF.
            override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(true) }
        }.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.userAgentString = webView.settings.userAgentString
            // Sin WebViewClient, el redirect post-reporte (a showthread) abriría CHROME. Este lo
            // mantiene TODO dentro del WebView tapado (devolver false = lo carga el propio WebView).
            webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: android.webkit.WebView, req: android.webkit.WebResourceRequest
                ): Boolean = false
            }
        }
        overlay.addView(wv, android.widget.FrameLayout.LayoutParams(-1, -1))
        val cover = buildReportCover(post)
        // El cover arranca ARRIBA (tapando). Solo se BAJA cuando hay un Cloudflare INTERACTIVO que el
        // usuario debe resolver (excepción de la regla de oro). Así el formulario/foro NUNCA se ven:
        // si no hay CF, el cover no se baja jamás; si lo hay, se baja solo para pulsar la casilla.
        cover.visibility = View.VISIBLE
        overlay.addView(cover, android.widget.FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, android.view.ViewGroup.LayoutParams(-1, -1))
        reportOverlay = overlay; reportWeb = wv; reportCover = cover

        wv.loadUrl("https://forocoches.com/foro/report.php?do=report&p=${post.pid}")
        val started = System.currentTimeMillis()
        var submitted = false
        val poll = object : Runnable {
            override fun run() {
                val w = reportWeb ?: return
                w.evaluateJavascript(
                    """(function(){
                        var b=document.body?document.body.innerText:'';
                        var cf=/Un momento|Just a moment|Verificaci.n de seguridad|Verifique que/i.test((document.title||'')+b);
                        var form=!!document.querySelector('textarea[name="reason"]');
                        return JSON.stringify({cf:cf,form:form});
                    })()"""
                ) { res ->
                    val r = try {
                        val inner = org.json.JSONTokener(res).nextValue() as? String
                        if (inner != null) org.json.JSONObject(inner) else null
                    } catch (e: Exception) { null }
                    val cf = r?.optBoolean("cf") == true
                    val form = r?.optBoolean("form") == true
                    val elapsed = System.currentTimeMillis() - started
                    // Cover ARRIBA salvo cuando hay CF interactivo (y aún no cayó el form): solo
                    // entonces se baja para que el usuario pulse la casilla del Cloudflare.
                    reportCover?.visibility = if (cf && !form) View.GONE else View.VISIBLE
                    when {
                        form && !submitted -> { submitted = true; onReportFormReady(post, reason, comment) }
                        elapsed > 90000 -> { toast("No se pudo completar la verificación"); closeReportOverlay() }
                        else -> reportHandler.postDelayed(this, 150)
                    }
                }
            }
        }
        reportPoll = poll
        reportHandler.postDelayed(poll, 250)
    }

    private val reportHandler by lazy { android.os.Handler(mainLooper) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Capa nativa opaca que oculta el WebView del report (el usuario solo ve ESTO). */
    private fun buildReportCover(post: PostItem): View {
        val ll = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(0xFFFFFFFF.toInt())
            isClickable = true; isFocusable = true // absorbe toques: no se toca el WebView de debajo
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        ll.addView(android.widget.ProgressBar(this))
        ll.addView(android.widget.TextView(this).apply {
            text = "Enviando reporte…"
            setTextColor(0xFF1A1A1A.toInt())
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(18), 0, dp(6))
        })
        ll.addView(android.widget.TextView(this).apply {
            text = "Verificando con ForoCoches"
            setTextColor(0xFF9E9E9E.toInt())
            textSize = 13f
            gravity = android.view.Gravity.CENTER
        })
        val cancel = android.widget.TextView(this).apply {
            text = "Cancelar"
            setTextColor(0xFFC8102E.toInt())
            textSize = 15f
            setPadding(dp(20), dp(24), dp(20), dp(10))
            setOnClickListener { closeReportOverlay() }
        }
        ll.addView(cancel)
        return ll
    }

    // Motivo (etiqueta del diálogo) → valor del radio 'tipo' del form real de FC (verificado por CDP).
    private val reportTipo = mapOf(
        "+18" to "6", "Spam" to "1", "Troll" to "2", "Flood" to "5", "Contenido" to "3", "Otros" to "4"
    )

    private fun onReportFormReady(post: PostItem, reason: String, comment: String) {
        // Formulario cargado → TAPAR ya para que el foro web no se vea mientras se auto-envía.
        reportCover?.visibility = View.VISIBLE
        val tipo = reportTipo[reason] ?: "4"
        // Se rellena el textarea 'reason', se marca el radio 'tipo' y se envía el form REAL (ya trae
        // securitytoken/s/postid…). No reimplementamos el POST: reutilizamos el form con su token.
        val js = """(function(){
            var f=document.querySelector('form[action*="do=sendemail"]')||document.querySelector('form');
            if(!f) return 'no-form';
            var ta=f.querySelector('textarea[name="reason"]'); if(ta) ta.value='${jsEscape(comment)}';
            var r=f.querySelector('input[name="tipo"][value="$tipo"]'); if(!r) return 'no-tipo'; r.checked=true;
            f.submit(); return 'ok';
        })()"""
        reportWeb?.evaluateJavascript(js) { res ->
            if (res.contains("ok")) {
                // f.submit() ya ha enviado el POST → el reporte queda hecho en el servidor. NO cargamos
                // el redirect a showthread: bajo el cover (WebView ocluido) relanza un Cloudflare que
                // no puede renderizar y colgaba el flujo. Cerramos con un timeout INDEPENDIENTE (no
                // atado al callback de evaluateJavascript, que era lo que se quedaba sin responder).
                reportPoll?.let { reportHandler.removeCallbacks(it) }; reportPoll = null
                reportHandler.postDelayed({ toast("Reporte enviado ✓"); closeReportOverlay() }, 2500)
            } else { toast("No se pudo enviar el reporte"); closeReportOverlay() }
        }
    }

    private fun closeReportOverlay() {
        reportPoll?.let { reportHandler.removeCallbacks(it) }; reportPoll = null
        reportWeb?.let { it.stopLoading(); it.loadUrl("about:blank"); it.destroy() }; reportWeb = null
        reportOverlay?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }; reportOverlay = null
        reportCover = null
    }

    private fun startEdit(post: PostItem) {
        editingPid = post.pid
        replyQuotes.clear()
        replyInput.setText("")   // se rellena al cargar el BBCode real
        replySubject.setText("")
        showComposer("edit", "Editar mensaje", showSubject = false)
        replySend.isEnabled = false
        replySend.text = "…"
        webView.evaluateJavascript("window.fcLoadPostForEdit&&fcLoadPostForEdit('${jsEscape(post.pid)}')", null)
    }

    private fun onEditLoad(json: String) {
        replySend.isEnabled = true
        replySend.text = "Guardar"
        try {
            val o = org.json.JSONObject(json)
            if (!o.optBoolean("ok", false)) {
                toast("No se pudo cargar el mensaje")
                hideReply()
                return
            }
            if (o.optString("pid") != editingPid) return
            replyInput.setText(o.optString("message"))
            replyInput.setSelection(replyInput.text.length)
            if (o.optBoolean("hasSubject", false)) {
                replySubject.setText(o.optString("subject"))
                replySubject.visibility = View.VISIBLE
            }
        } catch (_: Exception) { }
    }

    private fun confirmDelete(post: PostItem) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Borrar mensaje")
            .setMessage("¿Seguro que quieres borrar este mensaje? No se puede deshacer.")
            .setPositiveButton("Borrar") { _, _ ->
                toast("Borrando…")
                webView.evaluateJavascript("window.fcDeletePost&&fcDeletePost('${jsEscape(post.pid)}')", null)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun hideReply() {
        isReplyVisible = false
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(replyInput.windowToken, 0)
        replyInput.clearFocus()
        replySubject.clearFocus()
        replyPanel.visibility = View.GONE
        bottomNav.visibility = View.VISIBLE
        // Nuevo hilo se abrió desde la lista; responder/editar desde el hilo.
        if (replyMode == "newthread") {
            nativePanel.visibility = View.VISIBLE
        } else {
            threadPanel.visibility = View.VISIBLE
        }
        replyMode = "reply"
    }

    /** Reconstruye las tarjetas de cita del panel a partir de replyQuotes. */
    private fun renderReplyQuotes() {
        replyQuotesContainer.removeAllViews()
        val inflater = layoutInflater
        for (post in replyQuotes.values.toList()) {
            val card = inflater.inflate(R.layout.item_reply_quote, replyQuotesContainer, false)
            card.findViewById<TextView>(R.id.quote_author).text =
                if (post.author.isNotEmpty()) "@${post.author}" else "(anónimo)"
            val preview = postAdapter.quoteBodyOf(post).replace(Regex("\\s+"), " ").trim()
            card.findViewById<TextView>(R.id.quote_preview).text = preview
            card.findViewById<View>(R.id.quote_remove).setOnClickListener {
                replyQuotes.remove(post.pid)
                postAdapter.refreshSelection()
                renderReplyQuotes()
            }
            replyQuotesContainer.addView(card)
        }
    }

    /** Smilies reales de FC: se piden una vez por sesión y se cachean en memoria. */
    private fun openSmileyPicker() {
        smileyCache?.let { bbcode.showSmilies(it); return }
        if (!engineReady) { toast("Conectando con el foro…"); return }
        smileyDialogPending = true
        toast("Cargando emoticonos…")
        webView.evaluateJavascript("window.fcLoadSmilies&&fcLoadSmilies()", null)
    }

    private fun onSmiliesJson(json: String) {
        val list = parseSmilies(json)
        if (list.isEmpty()) return
        smileyCache = list
        if (smileyDialogPending) {
            smileyDialogPending = false
            if (isReplyVisible) bbcode.showSmilies(list)
        }
    }

    /** Escapa un String para incrustarlo entre comillas simples en evaluateJavascript. */
    private fun jsEscape(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'")
            .replace("\r", "").replace("\n", "\\n")

    private val signature = "\n\n\n[SIZE=1]Enviado desde ForoPlus[/SIZE]"

    private fun submitReply() {
        if (sendingReply) return
        when (replyMode) {
            "newthread" -> submitNewThread()
            "edit" -> submitEdit()
            else -> submitReplyPost()
        }
    }

    private fun submitReplyPost() {
        val body = replyInput.text.toString().trim()
        if (body.isEmpty() && replyQuotes.isEmpty()) { toast("Escribe algo antes de enviar"); return }
        if (currentThreadTid.isEmpty()) { toast("No se pudo identificar el hilo"); return }
        // Mensaje final = citas (BBCode) + texto del usuario + firma invisible de la app
        // (el usuario no la ve en el editor; se añade al enviar, con aire por encima).
        val quotes = replyQuotes.values.joinToString("") { quoteBlock(it) }
        var msg = (quotes + body).trim()
        if (msg.isEmpty()) { toast("Escribe algo antes de enviar"); return }
        msg += signature
        startSending("Enviando…")
        webView.evaluateJavascript(
            "window.fcSubmitReply&&fcSubmitReply('${jsEscape(currentThreadTid)}','${jsEscape(msg)}')",
            null
        )
    }

    private fun submitNewThread() {
        val subject = replySubject.text.toString().trim()
        val body = replyInput.text.toString().trim()
        if (subject.isEmpty()) { toast("Ponle un título al hilo"); return }
        if (body.isEmpty()) { toast("Escribe el mensaje del hilo"); return }
        val msg = body + signature
        startSending("Creando…")
        webView.evaluateJavascript(
            "window.fcCreateThread('$currentForumId','${jsEscape(subject)}','${jsEscape(msg)}')", null
        )
    }

    private fun submitEdit() {
        val body = replyInput.text.toString().trim()
        if (body.isEmpty()) { toast("El mensaje no puede quedar vacío"); return }
        if (editingPid.isEmpty()) { toast("No se pudo identificar el mensaje"); return }
        // Primer post del hilo: FC muestra el asunto, hay que reenviarlo.
        val subj = if (replySubject.visibility == View.VISIBLE) replySubject.text.toString().trim() else ""
        // Al editar NO se añade firma (el texto cargado ya la lleva si la tenía).
        startSending("Guardando…")
        webView.evaluateJavascript(
            "window.fcEditPost('${jsEscape(editingPid)}','${jsEscape(body)}','${jsEscape(subj)}')", null
        )
    }

    private fun startSending(label: String) {
        sendingReply = true
        replySend.isEnabled = false
        replySend.text = label
    }

    private fun onReplyResult(json: String) {
        sendingReply = false
        replySend.isEnabled = true
        replySend.text = "Enviar"
        val ok: Boolean
        val err: String
        try {
            val o = org.json.JSONObject(json)
            ok = o.optBoolean("ok", false)
            err = o.optString("error", "")
        } catch (_: Exception) {
            toast("No se pudo enviar la respuesta")
            return
        }
        if (ok) {
            replyInput.setText("")
            replyQuotes.clear()
            postAdapter.refreshSelection()
            hideReply()
            toast("Respuesta publicada")
            // Refresca la última página para ver el post recién enviado (pid nuevo → se añade).
            loadingThreadPage = false
            requestThreadPage(threadPageCount)
        } else {
            toast(if (err.isNotEmpty()) err else "No se pudo enviar la respuesta")
        }
    }

    /** Resultado de crear hilo / editar / borrar. */
    private fun onThreadActionResult(json: String) {
        sendingReply = false
        replySend.isEnabled = true
        val action: String
        val ok: Boolean
        val err: String
        val tid: String
        try {
            val o = org.json.JSONObject(json)
            action = o.optString("action")
            ok = o.optBoolean("ok", false)
            err = o.optString("error", "")
            tid = o.optString("tid", "")
        } catch (_: Exception) { return }
        when (action) {
            "create" -> {
                replySend.text = "Enviar"
                if (ok) {
                    replyInput.setText(""); replySubject.setText("")
                    hideReply()
                    toast("Hilo creado")
                    if (tid.isNotEmpty()) openThreadNative("https://forocoches.com/foro/showthread.php?t=$tid", "")
                    else showHomeList()
                } else toast(if (err.isNotEmpty()) err else "No se pudo crear el hilo")
            }
            "edit" -> {
                replySend.text = "Guardar"
                if (ok) {
                    replyInput.setText("")
                    hideReply()
                    toast("Mensaje editado")
                    reloadCurrentThread()
                } else toast(if (err.isNotEmpty()) err else "No se pudo editar el mensaje")
            }
            "delete" -> {
                if (ok) {
                    toast("Mensaje borrado")
                    reloadCurrentThread()
                } else toast(if (err.isNotEmpty()) err else "No se pudo borrar el mensaje")
            }
            "fav" -> {
                threadFav.isEnabled = true
                val fav = try { org.json.JSONObject(json).optBoolean("fav", false) } catch (_: Exception) { false }
                if (ok) {
                    threadFav.setImageResource(if (fav) R.drawable.ic_fav_on else R.drawable.ic_fav)
                    toast(if (fav) "Añadido a favoritos" else "Quitado de favoritos")
                } else toast(if (err == "login") "Inicia sesión para usar favoritos"
                             else if (err.isNotEmpty()) err else "No se pudo cambiar el favorito")
            }
        }
    }

    /** Favoritos: alterna la suscripción del hilo abierto. El estado real vive en
     *  subscription.php (el botón de FC en el hilo es estático), así que el motor
     *  consulta, alterna y verifica; aquí solo se refleja el resultado. */
    private fun toggleFavorite() {
        if (!isLoggedIn()) { toast("Inicia sesión para usar favoritos"); showLogin(); return }
        if (currentThreadTid.isEmpty()) { toast("No se pudo identificar el hilo"); return }
        threadFav.isEnabled = false
        webView.evaluateJavascript(
            "window.fcToggleFavorite&&fcToggleFavorite('${jsEscape(currentThreadTid)}')", null
        )
    }

    /** Recarga el hilo abierto desde la página 1 (tras editar/borrar un post). */
    private fun reloadCurrentThread() {
        if (currentThreadUrl.isEmpty()) return
        threadPage = 1
        postAdapter.clear()
        loadingThreadPage = false
        requestThreadPage(1)
    }

    // ── Panel de login nativo (Fase 3) ───────────────────────────────────────

    private fun configureLoginPanel() {
        loginPanel = findViewById(R.id.login_panel)
        loginUser = findViewById(R.id.login_user)
        loginPass = findViewById(R.id.login_pass)
        loginError = findViewById(R.id.login_error)
        loginSubmit = findViewById(R.id.login_submit)
        loginSubmit.setOnClickListener { submitLogin() }
        findViewById<View>(R.id.login_skip).setOnClickListener { hideLogin() }
        // Registro: fuera de la app (navegador del sistema); el foro nunca se ve dentro.
        findViewById<View>(R.id.login_register).setOnClickListener {
            try {
                startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://forocoches.com/foro/register.php")
                    ).addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                )
            } catch (_: Exception) { }
        }
    }

    private fun showLogin() {
        isLoginVisible = true
        isNoticesVisible = false
        isProfileVisible = false
        loginPanel.visibility = View.VISIBLE
        nativePanel.visibility = View.GONE
        threadPanel.visibility = View.GONE
        replyPanel.visibility = View.GONE
        noticesPanel.visibility = View.GONE
        profilePanel.visibility = View.GONE
        bottomNav.visibility = View.GONE   // pantalla de escritura: teclado a pantalla limpia
        loginError.visibility = View.GONE
        loginUser.requestFocus()
    }

    private fun hideLogin() {
        isLoginVisible = false
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(loginPanel.windowToken, 0)
        loginPanel.visibility = View.GONE
        bottomNav.visibility = View.VISIBLE
        pendingThreadUrl = ""
        pendingThreadTitle = ""
        // Vuelve a donde estaba: al hilo si venía de uno CON contenido (un +HD que no
        // cargó por falta de cuenta no cuenta), si no a la lista.
        if (isThreadVisible && currentThreadUrl.isNotEmpty() && postAdapter.itemCount > 0) {
            showThread()
        } else {
            showNative()
            setSelectedNav(navIdForList())
        }
    }

    private fun submitLogin() {
        if (sendingLogin) return
        val user = loginUser.text.toString().trim()
        val pass = loginPass.text.toString()
        if (user.isEmpty() || pass.isEmpty()) {
            loginError.text = "Rellena usuario y contraseña"
            loginError.visibility = View.VISIBLE
            return
        }
        if (!engineReady) {
            loginError.text = "Conectando con el foro… prueba en unos segundos"
            loginError.visibility = View.VISIBLE
            return
        }
        sendingLogin = true
        loginError.visibility = View.GONE
        loginSubmit.text = "Entrando…"
        webView.evaluateJavascript(
            "window.fcLogin&&fcLogin('${jsEscape(user)}','${jsEscape(pass)}')", null
        )
    }

    private fun onLoginResult(json: String) {
        sendingLogin = false
        loginSubmit.text = "Iniciar sesión"
        var err = ""
        try {
            val o = org.json.JSONObject(json)
            err = o.optString("error", "")
        } catch (_: Exception) { }
        // Veredicto REAL: la cookie de sesión bbuserid tras el POST (el HTML de FC
        // no distingue invitado de logueado — esqueleto idéntico).
        val ok = isLoggedIn()
        if (!ok) {
            loginError.text = when {
                err == "cloudflare" -> "ForoCoches está pidiendo verificación. Inténtalo de nuevo en un momento."
                err.isNotEmpty() -> err
                else -> "Usuario o contraseña incorrectos"
            }
            loginError.visibility = View.VISIBLE
            return
        }
        // Dentro. Persistimos cookies ya y refrescamos todo con la sesión nueva.
        CookieManager.getInstance().flush()
        updateAccountNavItem()
        loginPass.setText("")
        isLoginVisible = false
        loginPanel.visibility = View.GONE
        bottomNav.visibility = View.VISIBLE
        toast("Sesión iniciada")
        listLoaded = false
        loadingPage = false
        val backToThread = pendingThreadUrl
        val backToTitle = pendingThreadTitle
        pendingThreadUrl = ""
        pendingThreadTitle = ""
        if (backToThread.isNotEmpty()) {
            // Venía de un hilo que exigía cuenta (+HD): lo reabrimos ya logueado.
            openThreadNative(backToThread, backToTitle)
        } else if (isThreadVisible && currentThreadUrl.isNotEmpty()) {
            showThread()
        } else {
            showNative()
            setSelectedNav(navIdForList())
        }
        requestThreadList(1)   // refresca lista + menuLinks + badges con la sesión
    }

    private fun toast(m: String) =
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()

    private fun threadPageUrl(page: Int): String =
        if (page > 1) "$currentThreadUrl&page=$page" else currentThreadUrl

    /**
     * Salto de página: carga esa página REEMPLAZANDO la lista, nunca trayendo las
     * intermedias (en un hilo de 1000 páginas eso sería suicida). A partir de ahí el
     * scroll infinito sigue hacia delante con normalidad desde donde se ha caído.
     */
    private fun jumpToThreadPage(page: Int) {
        val p = page.coerceIn(1, threadPageCount)
        loadingThreadPage = false          // un salto siempre manda sobre la carga en curso
        prependOnLoad = false
        replaceOnLoad = true
        requestThreadPage(p)
    }

    /**
     * Página ANTERIOR al llegar arriba del todo. Sin esto, tras saltar a la 800 la única
     * forma de ver la 799 era reabrir el panel: el hilo era infinito hacia abajo pero
     * tenía un muro hacia arriba.
     */
    private fun requestPrevThreadPage() {
        if (!engineReady || loadingThreadPage || firstLoadedPage <= 1) return
        if (currentThreadUrl.isEmpty()) return
        loadingThreadPage = true
        prependOnLoad = true
        webView.evaluateJavascript(
            "window.fcLoadThread&&fcLoadThread('${threadPageUrl(firstLoadedPage - 1)}')", null
        )
    }

    // OJO: NADA de goto=lastpost / goto=newpost. Verificado por CDP que FC los IGNORA:
    // devuelve 200 con la PÁGINA 1 (sin redirección y sin <link rel="next/prev">), así que
    // el salto parecía no hacer nada. La última página se pide por page=threadPageCount,
    // que sí funciona. "Primer mensaje sin leer" queda fuera hasta encontrar señal fiable.

    /** Hoja de salto: se abre tocando el "Página X de Y" de la cabecera. */
    private fun showPageJumpSheet() {
        if (currentThreadTid.isEmpty()) return
        val view = layoutInflater.inflate(R.layout.sheet_thread_pages, null)
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        sheet.setContentView(view)
        // Sin esto la fila del "Ir a página N" queda DEBAJO de la barra de navegación
        // del sistema y es inalcanzable (el sheet se dibuja a pantalla completa).
        val basePad = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight,
                basePad + maxOf(bars.bottom, ime.bottom))
            insets
        }
        view.findViewById<TextView>(R.id.jump_range).text =
            if (threadPageCount > 1) "Este hilo tiene $threadPageCount páginas"
            else "Este hilo tiene una sola página"
        val input = view.findViewById<EditText>(R.id.jump_input)
        fun go(block: () -> Unit) { sheet.dismiss(); block() }
        view.findViewById<View>(R.id.jump_last).setOnClickListener { go { jumpToThreadPage(threadPageCount) } }
        view.findViewById<View>(R.id.jump_first).setOnClickListener { go { jumpToThreadPage(1) } }
        view.findViewById<View>(R.id.jump_go).setOnClickListener {
            val n = input.text.toString().trim().toIntOrNull()
            if (n == null || n < 1 || n > threadPageCount) {
                toast("Introduce una página entre 1 y $threadPageCount")
            } else go { jumpToThreadPage(n) }
        }
        sheet.show()
    }

    /** pid de una URL de post: showthread.php?p=NNN o .../showthread.php?t=1#post NNN. */
    private fun pidFromUrl(url: String): String =
        Regex("[?&]p=(\\d+)").find(url)?.groupValues?.get(1)
            ?: Regex("#post(\\d+)").find(url)?.groupValues?.get(1) ?: ""

    /**
     * Deep link (notificación de cita/mención, enlace a post). REGLA DE ORO: el foro de
     * debajo NO se ve. Antes esto hacía showWeb()+loadUrl y al tocar la notificación se
     * abría el ForoCoches web crudo — justo lo que no debe pasar. Ahora los enlaces de hilo
     * abren la vista NATIVA y saltan al post concreto; solo lo que aún no tiene vista propia
     * (MPs) cae a la capa web.
     */
    private fun openDeepLink(url: String): Boolean {
        if (!TrustedOrigins.isTrustedForocochesUrl(url)) return false
        // MP: la notificación apunta a private.php → bandeja nativa (nunca la capa web).
        if (url.contains("private.php")) {
            if (!engineReady) { pendingDeepLink = url; return true }
            showPmInbox()
            return true
        }
        if (!url.contains("showthread.php")) return false
        if (!engineReady) { pendingDeepLink = url; return true }  // se reintenta al arrancar
        openThreadNative(url, "")
        return true
    }

    private fun openThreadNative(url: String, title: String) {
        currentThreadUrl = url.substringBefore("&page=")
        currentThreadTid = Regex("[?&]t=(\\d+)").find(url)?.groupValues?.get(1) ?: ""
        threadPage = 1
        threadPageCount = 1
        firstLoadedPage = 1
        replaceOnLoad = false
        prependOnLoad = false
        // Si la URL apunta a un post concreto (?p= o #postN), al cargar se salta a él en vez
        // de dejar al usuario buscándolo a mano por el scroll. FC sirve en ?p= la página que
        // contiene ese post, así que siempre está entre los que llegan.
        pendingScrollPid = pidFromUrl(url)
        postAdapter.clear()
        // Respuesta limpia por hilo: sin borrador ni citas heredadas.
        replyInput.setText("")
        replySubject.setText("")
        replyQuotes.clear()
        editingPid = ""
        replyMode = "reply"
        if (isReplyVisible) { isReplyVisible = false; replyPanel.visibility = View.GONE }
        restrictedView.visibility = View.GONE
        threadTitle.text = title
        threadPageInfo.text = ""
        // El estado de favorito NO se puede leer del hilo (el botón de FC es estático):
        // estrella neutra hasta que el usuario la toque y el toggle confirme.
        threadFav.setImageResource(R.drawable.ic_fav)
        threadFav.isEnabled = true
        showThread()
        requestThreadPage(1)
    }

    private fun requestThreadPage(page: Int) {
        if (!engineReady || loadingThreadPage || currentThreadUrl.isEmpty()) return
        loadingThreadPage = true
        if (page <= 1) threadLoading.visibility = View.VISIBLE
        webView.evaluateJavascript(
            "window.fcLoadThread&&fcLoadThread('${threadPageUrl(page)}')", null
        )
    }

    private fun onThreadJson(json: String) {
        loadingThreadPage = false
        threadLoading.visibility = View.GONE
        restrictedView.visibility = View.GONE
        val t = parseThreadPayload(json) ?: return
        // Respuesta tardía de otro hilo (el user ya abrió otro): descartar.
        if (!t.url.startsWith(currentThreadUrl)) return
        // Hilos abiertos por enlace p= (citas/menciones): canonicaliza a t= para que
        // la paginación y responder funcionen con normalidad.
        if (t.tid.isNotEmpty() && !currentThreadUrl.contains("t=")) {
            currentThreadUrl = "https://forocoches.com/foro/showthread.php?t=${t.tid}"
        }
        if (currentThreadTid.isEmpty() && t.tid.isNotEmpty()) currentThreadTid = t.tid
        updateBadges(t.pmCount, t.quotesCount, t.mentionsCount)
        if (t.title.isNotEmpty()) threadTitle.text = t.title
        threadPageCount = t.pageCount
        // threadPage = última página en memoria; firstLoadedPage = la primera. Al insertar
        // hacia atrás solo se mueve la primera (si se pisara threadPage, el scroll hacia
        // abajo volvería a pedir páginas ya cargadas).
        if (prependOnLoad) {
            firstLoadedPage = t.page
        } else {
            threadPage = t.page
            // El indicador refleja la página VISIBLE: al cargar la 1 es la 1; en appends de
            // scroll no se pisa (lo actualiza el listener de scroll con lo que se ve). Tras
            // un salto sí se fija, porque la página visible pasa a ser la de destino.
            if (replaceOnLoad || t.page <= 1) {
                firstLoadedPage = t.page
                showThreadPageInfo(t.page)
            }
        }

        // Filtro de ignorados, mismas reglas que content.js: autor ignorado, post
        // colapsado por FC ("oculto porque") o post que cita a un ignorado.
        val ignored = repo.getIgnoredUsers().map { it.lowercase() }
        val visible = t.posts.filter { p ->
            if (p.author.isNotEmpty() && ignored.contains(p.author.lowercase())) return@filter false
            val body = p.html.lowercase()
            if (body.contains("oculto porque")) return@filter false
            if (ignored.any { body.contains("<b>$it dijo:</b>") }) return@filter false
            true
        }
        val jumped = replaceOnLoad
        val prepended = prependOnLoad
        replaceOnLoad = false
        prependOnLoad = false
        when {
            // OJO al orden: una página anterior puede ser la 1, y la rama de abajo
            // (t.page <= 1) la trataría como carga inicial y BORRARÍA lo ya leído.
            prepended -> {
                // Reanclaje: se guarda el ítem visible y su desplazamiento ANTES de meter
                // contenido por encima, y se vuelve a él después. Sin esto la pantalla
                // pega un salto en cuanto entra la página anterior.
                val lm = postList.layoutManager as LinearLayoutManager
                val anchorPos = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
                val anchorOff = lm.findViewByPosition(anchorPos)?.top ?: 0
                val added = postAdapter.prepend(visible)
                if (added > 0) lm.scrollToPositionWithOffset(anchorPos + added, anchorOff)
            }
            jumped || t.page <= 1 -> {
                postAdapter.submit(visible)
                if (jumped) postList.scrollToPosition(0)
            }
            else -> postAdapter.append(visible)
        }

        // Salto al post citado: se hace DESPUÉS de poblar la lista y una sola vez.
        if (pendingScrollPid.isNotEmpty()) {
            val pos = postAdapter.indexOfPid(pendingScrollPid)
            if (pos >= 0) {
                pendingScrollPid = ""
                val lm = postList.layoutManager as LinearLayoutManager
                postList.post { lm.scrollToPositionWithOffset(pos, 0) }
            }
        }
    }

    private fun onThreadError(reason: String) {
        loadingThreadPage = false
        replaceOnLoad = false
        prependOnLoad = false
        threadLoading.visibility = View.GONE
        when {
            reason == "cloudflare" -> {
                // Único caso donde asoma el WebView: el challenge solo lo resuelve
                // un navegador visible. Al pasarlo, atrás vuelve al hilo nativo.
                cameFromThread = true
                showWeb()
                webView.loadUrl(threadPageUrl(threadPage))
            }
            reason == "login" -> {
                // Página que pide identificarse: NUESTRO login, nunca el foro.
                // Al entrar se reabre este hilo automáticamente.
                pendingThreadUrl = currentThreadUrl
                pendingThreadTitle = threadTitle.text.toString()
                showLogin()
            }
            reason.startsWith("restricted") -> {
                // FC redirigió a su página informativa (+HD). Reproducimos SU info
                // con nuestros estilos, dentro del panel de hilo nativo.
                var msg = ""; var meta = ""; var invite = ""
                try {
                    val o = org.json.JSONObject(reason.substringAfter("restricted:", "{}"))
                    msg = o.optString("msg"); meta = o.optString("meta"); invite = o.optString("invite")
                } catch (_: Exception) { }
                showRestricted(msg, meta, invite)
            }
            else -> {
                android.util.Log.w("FC_SHELL", "thread error: $reason")
                // El foro de debajo NO se enseña: error nativo y de vuelta a la lista.
                if (postAdapter.itemCount == 0) {
                    toast("No se pudo cargar el hilo")
                    showNative()
                }
            }
        }
    }

    /**
     * Links dentro de posts. La regla de oro exige NO caer a la capa web: lo que la app
     * sabe pintar en nativo se abre en nativo; solo lo que no (perfiles de miembro, páginas
     * sueltas de FC) va al NAVEGADOR EXTERNO — nunca al WebView motor visible.
     */
    private fun onPostLinkClick(url: String) {
        when {
            url.contains("showthread.php") && TrustedOrigins.isTrustedForocochesUrl(url) ->
                openThreadNative(url.substringBefore("&page="), "")
            // Subforo → lista nativa (antes abría la web).
            url.contains("forumdisplay.php") && TrustedOrigins.isTrustedForocochesUrl(url) -> {
                val fid = Regex("[?&]f=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()
                if (fid != null) openForumNative(fid) else openExternal(url)
            }
            // MPs → bandeja nativa.
            url.contains("private.php") && TrustedOrigins.isTrustedForocochesUrl(url) -> showPmInbox()
            // Perfil de usuario (mención): el enlace trae el uid real → perfil NATIVO.
            url.contains("member.php") && TrustedOrigins.isTrustedForocochesUrl(url) -> {
                val uid = Regex("[?&]u=(\\d+)").find(url)?.groupValues?.get(1)
                if (uid != null && uid != "0") showMemberProfile(uid) else openExternal(url)
            }
            // Resto de FC (misc.php, etc.): navegador externo, NO la capa web.
            else -> openExternal(url)
        }
    }

    /** Abre un subforo concreto en la lista nativa de Inicio (selecciona su pestaña si existe). */
    private fun openForumNative(fid: Int) {
        currentForumId = fid
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_LAST_FID, fid).apply()
        listSource = "home"
        forumTabs.visibility = View.VISIBLE
        // Si el subforo es una de las pestañas, seleccionarla; si no, cargarlo igualmente.
        var tabIdx = -1
        for (i in 0 until forumTabs.tabCount) if (forumTabs.getTabAt(i)?.tag == fid) { tabIdx = i; break }
        listLoaded = false
        adapter.submit(emptyList())
        showNative()
        setSelectedNav(R.id.nav_home)
        if (tabIdx >= 0) forumTabs.getTabAt(tabIdx)?.select() else requestThreadList(1)
    }

    /**
     * Vídeo de embed a pantalla completa: el reproductor pide mostrar su vista custom;
     * la ponemos sobre todo, ocultando las barras del sistema. NO es el foro (es el
     * reproductor de X/YouTube/TikTok), así que la regla de oro no aplica.
     */
    private fun onEmbedFullscreen(view: View?, callback: android.webkit.WebChromeClient.CustomViewCallback?) {
        val decor = window.decorView as android.view.ViewGroup
        val controller = WindowInsetsControllerCompat(window, decor)
        if (view != null) {
            fullscreenView = view
            fullscreenCallback = callback
            decor.addView(view, android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            ))
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            fullscreenView?.let { decor.removeView(it) }
            fullscreenView = null
            try { fullscreenCallback?.onCustomViewHidden() } catch (_: Exception) {}
            fullscreenCallback = null
            controller.show(WindowInsetsCompat.Type.systemBars())
            // El reproductor a veces gira a horizontal y no revierte: volver a vertical.
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            window.decorView.post {
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    /** Abre una URL en el navegador externo (nunca en el WebView motor visible). */
    private fun openExternal(url: String) {
        try {
            startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
            )
        } catch (_: Exception) { }
    }

    // ── Capas ────────────────────────────────────────────────────────────────

    private fun openWeb(url: String?) {
        cameFromThread = false
        showWeb()
        webView.loadUrl(url ?: TrustedOrigins.DEFAULT_URL)
    }

    private fun showNative() {
        isWebVisible = false
        isThreadVisible = false
        isReplyVisible = false
        isLoginVisible = false
        isNoticesVisible = false
        isProfileVisible = false
        isOptionsVisible = false
        cameFromThread = false
        hidePmPanels()
        nativePanel.visibility = View.VISIBLE
        threadPanel.visibility = View.GONE
        replyPanel.visibility = View.GONE
        loginPanel.visibility = View.GONE
        noticesPanel.visibility = View.GONE
        profilePanel.visibility = View.GONE
        optionsPanel.visibility = View.GONE
        bottomNav.visibility = View.VISIBLE
        // FAB de crear hilo: solo en un subforo real y con sesión.
        fabNewThread.visibility = if (listSource == "home" && isLoggedIn()) View.VISIBLE else View.GONE
        // invisible (no gone): el WebView sigue vivo debajo como motor de datos.
        swipeRefresh.visibility = View.INVISIBLE
    }

    private fun showThread() {
        isWebVisible = false
        isThreadVisible = true
        isReplyVisible = false
        isLoginVisible = false
        isNoticesVisible = false
        isProfileVisible = false
        isOptionsVisible = false
        hidePmPanels()
        threadPanel.visibility = View.VISIBLE
        nativePanel.visibility = View.GONE
        replyPanel.visibility = View.GONE
        loginPanel.visibility = View.GONE
        noticesPanel.visibility = View.GONE
        profilePanel.visibility = View.GONE
        optionsPanel.visibility = View.GONE
        bottomNav.visibility = View.VISIBLE
        swipeRefresh.visibility = View.INVISIBLE
    }

    private fun showWeb() {
        isWebVisible = true
        isReplyVisible = false
        isLoginVisible = false
        isNoticesVisible = false
        isProfileVisible = false
        isOptionsVisible = false
        hidePmPanels()
        swipeRefresh.visibility = View.VISIBLE
        nativePanel.visibility = View.GONE
        threadPanel.visibility = View.GONE
        replyPanel.visibility = View.GONE
        loginPanel.visibility = View.GONE
        noticesPanel.visibility = View.GONE
        profilePanel.visibility = View.GONE
        optionsPanel.visibility = View.GONE
        bottomNav.visibility = View.VISIBLE
    }

    /** El WebView terminó una página. Si es de FC, el extractor ya está inyectado. */
    private fun onEnginePageReady(url: String) {
        if (!TrustedOrigins.isTrustedForocochesUrl(url)) return
        engineReady = true
        if (!forumsRequested) {
            forumsRequested = true
            webView.evaluateJavascript(
                "window.fcLoadForumList&&fcLoadForumList('https://forocoches.com/foro/')", null
            )
        }
        if (!listLoaded) requestThreadList(1)
        if (pendingDeepLink.isNotEmpty()) {
            val dl = pendingDeepLink
            pendingDeepLink = ""
            openDeepLink(dl)
        }
    }

    private fun requestThreadList(page: Int) {
        if (!engineReady || loadingPage) return
        loadingPage = true
        if (page <= 1 && !listRefresh.isRefreshing) listLoading.visibility = View.VISIBLE
        listEmpty.visibility = View.GONE
        val js = when (listSource) {
            // Mis hilos / Participados: el motor resuelve el UID/usuario real (el DOM vivo
            // trae u=0) y busca; para paginar reusa la URL con searchid (myThreadsBase).
            "search" -> "window.fcSearch&&fcSearch('${jsEscape(searchQuery)}',${searchTitleOnly}," +
                "'${jsEscape(if (page > 1 && myThreadsBase.isNotEmpty()) myThreadsBase + "&page=$page" else "")}')"
            "mine", "participated" -> {
                val mode = if (listSource == "mine") "started" else "participated"
                val pageUrl = if (page > 1 && myThreadsBase.isNotEmpty())
                    myThreadsBase + "&page=$page" else ""
                "window.fcLoadOwnThreads&&fcLoadOwnThreads('$mode','${jsEscape(pageUrl)}')"
            }
            else -> "window.fcLoadThreadList&&fcLoadThreadList('${buildListUrl(page)}')"
        }
        webView.evaluateJavascript(js, null)
    }

    private fun onThreadListJson(json: String) {
        loadingPage = false
        listLoading.visibility = View.GONE
        listRefresh.isRefreshing = false
        val parsed = parseThreadListPayload(json) ?: run {
            onThreadListError("parse")
            return
        }
        if (parsed.menu.pm != null || parsed.menu.profile != null) menuLinks = parsed.menu
        updateAccountNavItem()
        updateBadges(parsed.pmCount, parsed.quotesCount, parsed.mentionsCount)
        // Mis hilos: la búsqueda redirige a search.php?searchid=N — se guarda como base
        // de paginación (sin su posible page=).
        if ((listSource == "mine" || listSource == "participated" || listSource == "search") && parsed.finalUrl.contains("searchid=")) {
            myThreadsBase = parsed.finalUrl.replace(Regex("[&?]page=\\d+"), "")
        }

        // Filtrado nativo: ignorados y keywords (mismos datos que usa content.js).
        val ignored = repo.getIgnoredUsers().map { it.lowercase() }.toHashSet()
        val kwEnabled = keywordRepo.isEnabled()
        val keywords = if (kwEnabled) keywordRepo.getKeywords().map { it.lowercase() } else emptyList()
        val visible = parsed.threads.filter { t ->
            if (t.author.isNotEmpty() && ignored.contains(t.author.lowercase())) return@filter false
            if (keywords.isNotEmpty()) {
                val title = t.title.lowercase()
                if (keywords.any { title.contains(it) }) return@filter false
            }
            true
        }
        listLoaded = true
        currentPage = parsed.page
        if (parsed.page <= 1) adapter.submit(visible) else adapter.append(visible)
        listEmpty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
        listEmpty.text = "No hay hilos que mostrar"
    }

    /** Badges de la barra inferior: MP en Perfil, citas en Citas, menciones en Menciones.
     *  Los contadores vienen gratis en el HTML de cada listado (cero peticiones extra). */
    private fun updateBadges(pm: Int, quotes: Int, mentions: Int) {
        fun set(id: Int, n: Int) {
            navBadges[id]?.apply {
                if (n > 0) {
                    visibility = View.VISIBLE
                    text = if (n > 99) "99+" else n.toString()
                } else {
                    visibility = View.GONE
                }
            }
        }
        set(R.id.nav_notif, mentions)
        set(R.id.nav_quotes, quotes)
        set(R.id.nav_profile, pm)
    }

    private fun onThreadListError(reason: String) {
        loadingPage = false
        listLoading.visibility = View.GONE
        listRefresh.isRefreshing = false
        when (reason) {
            "cloudflare" -> {
                // Challenge de CF: solo un navegador visible puede resolverlo. Enseñamos
                // la web; al pasarlo, el usuario vuelve a Inicio y la lista carga.
                showWeb()
                webView.loadUrl(buildListUrl(1))
            }
            else -> {
                if (adapter.itemCount == 0) {
                    listEmpty.visibility = View.VISIBLE
                    listEmpty.text = "No se pudo cargar el listado.\nDesliza para reintentar."
                }
                android.util.Log.w("FC_SHELL", "thread list error: $reason")
            }
        }
    }

    // ── Comportamiento heredado ──────────────────────────────────────────────

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Gesto atrás/adelante por swipe en la capa web; en la lista nativa de Inicio
        // (pestañas visibles), swipe horizontal = subforo anterior/siguiente (testers).
        val tabsSwipe = !isWebVisible && !isThreadVisible && !isReplyVisible &&
            !isLoginVisible && !isNoticesVisible && !isProfileVisible && !isOptionsVisible &&
            listSource == "home" && forumTabs.visibility == View.VISIBLE
        if (!isWebVisible && !tabsSwipe) return super.dispatchTouchEvent(ev)
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = ev.x
                touchDownY = ev.y
                // El swipe de subforo solo vale si arranca SOBRE la lista de hilos: así,
                // deslizar la barra inferior (que scrollea en horizontal) o las pestañas
                // ya no cambia de subforo por accidente.
                swipeStartedInList = touchInside(threadList, ev)
            }
            MotionEvent.ACTION_UP -> {
                val diffX = ev.x - touchDownX
                val diffY = ev.y - touchDownY
                if (isWebVisible && abs(diffX) > abs(diffY) * 2f && abs(diffX) > 100f) {
                    if (diffX > 0 && webView.canGoBack()) { webView.goBack(); return true }
                    if (diffX < 0 && webView.canGoForward()) { webView.goForward(); return true }
                }
                // Umbral más exigente que en la web: la lista scrollea en vertical y un
                // arrastre diagonal no debe cambiar de subforo por accidente.
                if (tabsSwipe && swipeStartedInList && abs(diffX) > abs(diffY) * 2f && abs(diffX) > 150f) {
                    selectAdjacentTab(if (diffX < 0) 1 else -1)
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** ¿El toque cae dentro de los límites en pantalla de [view]? (coords absolutas rawX/rawY). */
    private fun touchInside(view: View, ev: MotionEvent): Boolean {
        if (view.visibility != View.VISIBLE || view.width == 0 || view.height == 0) return false
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val x = ev.rawX.toInt()
        val y = ev.rawY.toInt()
        return x in loc[0]..(loc[0] + view.width) && y in loc[1]..(loc[1] + view.height)
    }

    /** Pestaña vecina (delta ±1); select() dispara onTabSelected → carga del subforo. */
    private fun selectAdjacentTab(delta: Int) {
        val idx = forumTabs.selectedTabPosition
        val next = idx + delta
        if (idx < 0 || next < 0 || next >= forumTabs.tabCount) return
        forumTabs.getTabAt(next)?.select()
    }

    private fun fetchIgnoreListIfNeeded() {
        if (repo.getLastUpdated() != 0L) return
        lifecycleScope.launch {
            delay(3_000)
            val cookie = CookieManager.getInstance().getCookie("https://forocoches.com")
                ?: return@launch
            if (cookie.isBlank()) return@launch
            try {
                val users = IgnoreListFetcher().fetch(cookie)
                if (users.isNotEmpty()) repo.setIgnoredUsers(users)
            } catch (_: Exception) { }
        }
    }

    private fun startNotificationPolling() {
        lifecycleScope.launch {
            delay(5_000)
            while (true) {
                val cookie = CookieManager.getInstance().getCookie("https://forocoches.com")
                if (!cookie.isNullOrBlank()) {
                    try {
                        NotificationChecker.check(this@MainActivity, cookie)
                    } catch (_: Exception) { }
                }
                delay(60_000)
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    override fun onPause() {
        super.onPause()
        // Persiste las cookies de sesión a disco para que el NotificationWorker en
        // background no haga el fetch deslogueado.
        CookieManager.getInstance().flush()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        val url = TrustedOrigins.trustedUrlOrDefault(intent.getStringExtra("url"))
        if (openDeepLink(url)) return       // hilo → vista nativa, saltando al post citado
        showWeb()
        webView.loadUrl(url)
    }

    /**
     * Atrás del sistema. En API 33+ (y por defecto al apuntar a 35/36) el atrás se despacha
     * por OnBackInvokedCallback → hay que registrar la navegación en el OnBackPressedDispatcher.
     * Sobrescribir `onBackPressed()` ya NO se llama en API 36 (la app se salía al escritorio).
     * Registrado en onCreate con `onBackPressedDispatcher.addCallback`.
     */
    private val onBackCallback = object : androidx.activity.OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            // El overlay de reporte tiene prioridad: atrás lo cancela.
            if (reportOverlay != null) { closeReportOverlay(); return }
            // Salir de la pantalla completa de vídeo antes que nada.
            if (fullscreenView != null) { onEmbedFullscreen(null, null); return }
            if (isWebVisible) {
                when {
                    webView.canGoBack() -> webView.goBack()
                    cameFromThread -> showThread()   // la web se abrió desde un hilo nativo
                    else -> { showNative(); setSelectedNav(navIdForList()) } // web → lista nativa
                }
                return
            }
            if (isLoginVisible) { hideLogin(); return }
            if (isReplyVisible) { hideReply(); return }
            if (isOptionsVisible) { hideOptions(); return }
            if (isMemberVisible) { exitMemberProfile(); return }
            // MP: compositor → detalle/bandeja; detalle → bandeja; bandeja → perfil.
            if (isPmComposeVisible) { cancelPmCompose(); return }
            if (isPmDetailVisible) { showPmInbox(); return }
            if (isPmVisible) { showProfile(); return }
            if (isNoticesVisible || isProfileVisible) { showNative(); setSelectedNav(navIdForList()); return }
            if (isThreadVisible) { showNative(); return }
            // Raíz (lista de Inicio): nada que deshacer → comportamiento por defecto (salir).
            // Se desactiva el callback y se re-despacha para que el sistema haga el finish.
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
            isEnabled = true
        }
    }
}
