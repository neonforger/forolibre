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
import com.google.android.material.bottomnavigation.BottomNavigationView
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
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var adapter: ThreadListAdapter

    private lateinit var forumTabs: TabLayout

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
        private const val LOGIN_URL = "https://forocoches.com/foro/login.php"
        private const val PREFS = "shell_prefs"
        private const val PREF_LAST_FID = "last_fid"
    }

    private fun buildListUrl(page: Int): String {
        val base = "https://forocoches.com/foro/forumdisplay.php?f=$currentForumId"
        return if (page > 1) "$base&page=$page" else base
    }

    /** Con sesión, el menú de FC trae el enlace a MP; sin ella, somos invitado. */
    private fun isLoggedIn() = menuLinks?.pm != null

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
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
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
                onForums = { json -> runOnUiThread { onForumListJson(json) } }
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
        adapter = ThreadListAdapter { item ->
            showWeb()
            webView.loadUrl(item.url)
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

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { showNative(); if (!listLoaded) requestThreadList(1); true }
                R.id.nav_favs -> { openWeb(menuLinks?.favs ?: "https://forocoches.com/foro/subscription.php"); true }
                // Sin sesión estas secciones no existen: llevamos al login directamente.
                R.id.nav_notif -> { openWeb(if (isLoggedIn()) menuLinks?.mentions else LOGIN_URL); true }
                R.id.nav_quotes -> { openWeb(if (isLoggedIn()) menuLinks?.quotes else LOGIN_URL); true }
                R.id.nav_profile -> { openWeb(if (isLoggedIn()) menuLinks?.profile else LOGIN_URL); true }
                else -> false
            }
        }
        bottomNav.setOnItemReselectedListener { item ->
            if (item.itemId == R.id.nav_home) { showNative(); requestThreadList(1) }
        }

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

    private fun openWeb(url: String?) {
        showWeb()
        webView.loadUrl(url ?: TrustedOrigins.DEFAULT_URL)
    }

    private fun showNative() {
        isWebVisible = false
        nativePanel.visibility = View.VISIBLE
        // invisible (no gone): el WebView sigue vivo debajo como motor de datos.
        swipeRefresh.visibility = View.INVISIBLE
    }

    private fun showWeb() {
        isWebVisible = true
        swipeRefresh.visibility = View.VISIBLE
        nativePanel.visibility = View.GONE
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
                else -> {
                    // De la web se vuelve a la lista nativa, no se sale de la app.
                    showNative()
                    bottomNav.selectedItemId = R.id.nav_home
                }
            }
            return
        }
        super.onBackPressed()
    }
}
