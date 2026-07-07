package com.fcplus.forocoches

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
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
        R.id.nav_home, R.id.nav_favs, R.id.nav_mythreads,
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
    private var isReplyVisible = false
    private var sendingReply = false
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

    private lateinit var nativeHeader: TextView
    private var listSource = "home"        // home | favs | mine (qué alimenta la lista nativa)
    private var myThreadsBase = ""         // search.php?searchid=N para paginar Mis hilos

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

    companion object {
        private const val PREFS = "shell_prefs"
        private const val PREF_LAST_FID = "last_fid"
    }

    private fun buildListUrl(page: Int): String {
        val base = when (listSource) {
            "favs" -> "https://forocoches.com/foro/subscription.php"
            "mine" -> {
                // Página 1: búsqueda estándar de vBulletin "hilos iniciados por mí".
                // Siguientes: la URL con searchid que devolvió la búsqueda (finalUrl).
                if (page > 1 && myThreadsBase.isNotEmpty()) myThreadsBase
                else {
                    val uid = Regex("u=(\\d+)").find(menuLinks?.profile ?: "")?.groupValues?.get(1) ?: ""
                    "https://forocoches.com/foro/search.php?do=finduser&u=$uid&starteronly=1"
                }
            }
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
            R.id.nav_notif to R.drawable.ic_nav_bell,
            R.id.nav_quotes to R.drawable.ic_nav_quote,
            R.id.nav_profile to R.drawable.ic_nav_person
        )
        val labels = mapOf(
            R.id.nav_home to "Inicio",
            R.id.nav_favs to "Favoritos",
            R.id.nav_mythreads to "Mis hilos",
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
        else -> R.id.nav_home
    }

    // ── Secciones nativas (Bloque B) ─────────────────────────────────────────

    /** Inicio: la lista nativa vuelve al modo foro (pestañas de subforos visibles). */
    private fun showHomeList() {
        val wasOther = listSource != "home"
        listSource = "home"
        forumTabs.visibility = View.VISIBLE
        nativeHeader.text = "ForoPlus"
        showNative()
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
        setSelectedNav(R.id.nav_favs)
        requestThreadList(1)
    }

    /** Mis hilos: los iniciados por el usuario, vía la búsqueda estándar de vBulletin. */
    private fun showMyThreadsList() {
        if (menuLinks?.profile == null) { toast("Conectando con el foro…"); return }
        listSource = "mine"
        myThreadsBase = ""
        forumTabs.visibility = View.GONE
        nativeHeader.text = "Mis hilos"
        listLoaded = false
        adapter.submit(emptyList())
        showNative()
        setSelectedNav(R.id.nav_mythreads)
        requestThreadList(1)
    }

    /** Citas o menciones AISLADAS en su propia página (no el batiburrillo de FC). */
    private fun showNotices(kind: String) {
        val url = if (kind == "quotes") menuLinks?.quotes else menuLinks?.mentions
        if (url == null) { toast("Conectando con el foro…"); return }
        currentNoticesKind = kind
        isNoticesVisible = true
        isWebVisible = false; isThreadVisible = false; isReplyVisible = false
        isLoginVisible = false; isProfileVisible = false
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
        isLoginVisible = false; isNoticesVisible = false
        profilePanel.visibility = View.VISIBLE
        nativePanel.visibility = View.GONE
        threadPanel.visibility = View.GONE
        replyPanel.visibility = View.GONE
        loginPanel.visibility = View.GONE
        noticesPanel.visibility = View.GONE
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

        // El WebView arranca OCULTO como motor: al terminar de cargar, extractor.js queda
        // inyectado y pedimos el listado por fetch same-origin (nunca HTTP nativo).
        val startUrl = TrustedOrigins.trustedUrlOrDefault(intent.getStringExtra("url"))
        if (startUrl != TrustedOrigins.DEFAULT_URL) {
            // Deep link (p. ej. notificación): directo a la capa web, como siempre.
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
                onLogout = { result -> runOnUiThread { onLogoutDone(result) } }
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
        noticeAdapter = NoticeAdapter { n -> openThreadNative(n.url.substringBefore("#"), n.title) }
        noticesList.layoutManager = LinearLayoutManager(this)
        noticesList.adapter = noticeAdapter
        findViewById<View>(R.id.profile_logout).setOnClickListener { doLogout() }
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
        findViewById<View>(R.id.fmt_smiley).setOnClickListener { openSmileyPicker() }

        postAdapter = PostAdapter(
            onLinkClick = { url -> onPostLinkClick(url) },
            // Citar: añade la cita y abre la pestaña de respuesta.
            onQuote = { post -> quotePost(post) },
            // "+": alterna la cita en la respuesta (sin abrir el panel).
            onMultiquoteToggle = { post -> toggleMultiquote(post) },
            isSelected = { pid -> replyQuotes.containsKey(pid) }
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

    /** Indicador "Página X de Y" de la cabecera del hilo. */
    private fun showThreadPageInfo(visiblePage: Int) {
        threadPageInfo.text =
            if (threadPageCount > 1) "Página $visiblePage de $threadPageCount" else ""
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

    private fun openReply() {
        isReplyVisible = true
        replyPanel.visibility = View.VISIBLE
        threadPanel.visibility = View.GONE
        // Pantalla de escritura = sin barra de navegación: el teclado ocupa su hueco
        // y se escribe cómodo (la barra no "sube" con el teclado).
        bottomNav.visibility = View.GONE
        renderReplyQuotes()
        replyInput.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(replyInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideReply() {
        isReplyVisible = false
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(replyInput.windowToken, 0)
        replyInput.clearFocus()
        replyPanel.visibility = View.GONE
        bottomNav.visibility = View.VISIBLE
        threadPanel.visibility = View.VISIBLE
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

    private fun submitReply() {
        if (sendingReply) return
        val body = replyInput.text.toString().trim()
        if (body.isEmpty() && replyQuotes.isEmpty()) { toast("Escribe algo antes de enviar"); return }
        if (currentThreadTid.isEmpty()) { toast("No se pudo identificar el hilo"); return }
        // Mensaje final = citas (BBCode) + texto del usuario + firma invisible de la app
        // (el usuario no la ve en el editor; se añade al enviar, con aire por encima).
        val quotes = replyQuotes.values.joinToString("") { quoteBlock(it) }
        var msg = (quotes + body).trim()
        if (msg.isEmpty()) { toast("Escribe algo antes de enviar"); return }
        msg += "\n\n\n[SIZE=1]Enviado desde ForoPlus[/SIZE]"
        sendingReply = true
        replySend.isEnabled = false
        replySend.text = "Enviando…"
        webView.evaluateJavascript(
            "window.fcSubmitReply&&fcSubmitReply('${jsEscape(currentThreadTid)}','${jsEscape(msg)}')",
            null
        )
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

    private fun openThreadNative(url: String, title: String) {
        currentThreadUrl = url.substringBefore("&page=")
        currentThreadTid = Regex("[?&]t=(\\d+)").find(url)?.groupValues?.get(1) ?: ""
        threadPage = 1
        threadPageCount = 1
        postAdapter.clear()
        // Respuesta limpia por hilo: sin borrador ni citas heredadas.
        replyInput.setText("")
        replyQuotes.clear()
        if (isReplyVisible) hideReply()
        restrictedView.visibility = View.GONE
        threadTitle.text = title
        threadPageInfo.text = ""
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
        threadPage = t.page
        threadPageCount = t.pageCount
        // El indicador refleja la página VISIBLE: al cargar la 1 es la 1; en appends de
        // scroll no se pisa (lo actualiza el listener de scroll con lo que se ve).
        if (t.page <= 1) showThreadPageInfo(1)

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
        if (t.page <= 1) postAdapter.submit(visible) else postAdapter.append(visible)
    }

    private fun onThreadError(reason: String) {
        loadingThreadPage = false
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

    /** Links dentro de posts: hilos de FC → nativo; resto de FC → capa web; fuera → navegador. */
    private fun onPostLinkClick(url: String) {
        when {
            url.contains("showthread.php") && TrustedOrigins.isTrustedForocochesUrl(url) ->
                openThreadNative(url.substringBefore("&page="), "")
            TrustedOrigins.isTrustedForocochesUrl(url) -> {
                cameFromThread = true
                showWeb()
                webView.loadUrl(url)
            }
            else -> {
                try {
                    startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                    )
                } catch (_: Exception) { }
            }
        }
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
        cameFromThread = false
        nativePanel.visibility = View.VISIBLE
        threadPanel.visibility = View.GONE
        replyPanel.visibility = View.GONE
        loginPanel.visibility = View.GONE
        noticesPanel.visibility = View.GONE
        profilePanel.visibility = View.GONE
        bottomNav.visibility = View.VISIBLE
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
        threadPanel.visibility = View.VISIBLE
        nativePanel.visibility = View.GONE
        replyPanel.visibility = View.GONE
        loginPanel.visibility = View.GONE
        noticesPanel.visibility = View.GONE
        profilePanel.visibility = View.GONE
        bottomNav.visibility = View.VISIBLE
        swipeRefresh.visibility = View.INVISIBLE
    }

    private fun showWeb() {
        isWebVisible = true
        isReplyVisible = false
        isLoginVisible = false
        isNoticesVisible = false
        isProfileVisible = false
        swipeRefresh.visibility = View.VISIBLE
        nativePanel.visibility = View.GONE
        threadPanel.visibility = View.GONE
        replyPanel.visibility = View.GONE
        loginPanel.visibility = View.GONE
        noticesPanel.visibility = View.GONE
        profilePanel.visibility = View.GONE
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
    }

    private fun requestThreadList(page: Int) {
        if (!engineReady || loadingPage) return
        loadingPage = true
        if (page <= 1 && !listRefresh.isRefreshing) listLoading.visibility = View.VISIBLE
        listEmpty.visibility = View.GONE
        webView.evaluateJavascript(
            "window.fcLoadThreadList&&fcLoadThreadList('${buildListUrl(page)}')", null
        )
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
        if (listSource == "mine" && parsed.finalUrl.contains("searchid=")) {
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
        // Gesto atrás/adelante por swipe: solo tiene sentido en la capa web.
        if (!isWebVisible) return super.dispatchTouchEvent(ev)
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = ev.x
                touchDownY = ev.y
            }
            MotionEvent.ACTION_UP -> {
                val diffX = ev.x - touchDownX
                val diffY = ev.y - touchDownY
                if (abs(diffX) > abs(diffY) * 2f && abs(diffX) > 100f) {
                    if (diffX > 0 && webView.canGoBack()) { webView.goBack(); return true }
                    if (diffX < 0 && webView.canGoForward()) { webView.goForward(); return true }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
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
        showWeb()
        webView.loadUrl(url)
    }

    @Deprecated("Needed for API < 33")
    override fun onBackPressed() {
        if (isWebVisible) {
            when {
                webView.canGoBack() -> webView.goBack()
                cameFromThread -> showThread()   // la web se abrió desde un hilo nativo
                else -> {
                    // De la web se vuelve a la lista nativa, no se sale de la app.
                    showNative()
                    setSelectedNav(navIdForList())
                }
            }
            return
        }
        if (isLoginVisible) {
            hideLogin()
            return
        }
        if (isReplyVisible) {
            hideReply()
            return
        }
        if (isNoticesVisible || isProfileVisible) {
            showNative()
            setSelectedNav(navIdForList())
            return
        }
        if (isThreadVisible) {
            showNative()
            return
        }
        super.onBackPressed()
    }
}
