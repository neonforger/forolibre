package com.fcplus.forocoches

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors

data class PostItem(
    val pid: String,
    val author: String,
    val avatar: String,
    val date: String,
    val html: String,
    val page: Int = 1,
    val own: Boolean = false
)

data class ThreadPayload(
    val url: String,
    val tid: String,
    val title: String,
    val page: Int,
    val pageCount: Int,
    val posts: List<PostItem>,
    val pmCount: Int,
    val quotesCount: Int,
    val mentionsCount: Int
)

fun parseThreadPayload(json: String): ThreadPayload? {
    return try {
        val root = JSONObject(json)
        val pageNum = root.optInt("page", 1)
        val arr = root.getJSONArray("posts")
        val posts = ArrayList<PostItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val pid = o.optString("pid").trim()
            if (pid.isEmpty()) continue
            posts.add(
                PostItem(
                    pid = pid,
                    author = o.optString("author").trim(),
                    avatar = o.optString("avatar").trim(),
                    date = o.optString("date").trim(),
                    html = o.optString("html"),
                    page = pageNum,
                    own = o.optBoolean("own", false)
                )
            )
        }
        val counts = root.optJSONObject("counts")
        ThreadPayload(
            url = root.optString("url"),
            tid = root.optString("tid"),
            title = root.optString("title"),
            page = root.optInt("page", 1),
            pageCount = root.optInt("pageCount", 1),
            posts = posts,
            pmCount = counts?.optInt("pm") ?: 0,
            quotesCount = counts?.optInt("quotes") ?: 0,
            mentionsCount = counts?.optInt("mentions") ?: 0
        )
    } catch (_: Exception) {
        null
    }
}

/**
 * Cargador de imágenes de posts (avatares, imágenes, smilies) con caché LRU.
 * NOTA de alcance: esto son GETs de imágenes (no scraping de HTML); a los hosts de FC
 * se les adjunta cookie de sesión + UA como hace NotificationFetcher.
 */
object PostImages {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = Collections.synchronizedSet(HashSet<String>())
    private val inflight = Collections.synchronizedSet(HashSet<String>())
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun get(url: String): Bitmap? = cache.get(url)

    fun load(url: String, onDone: () -> Unit) {
        if (cache.get(url) != null || failed.contains(url)) return
        if (!inflight.add(url)) return
        pool.execute {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", UA)
                if (url.contains("forocoches.com")) {
                    CookieManager.getInstance().getCookie("https://forocoches.com")
                        ?.let { conn.setRequestProperty("Cookie", it) }
                }
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                conn.disconnect()
                if (bmp != null) {
                    cache.put(url, bmp)
                    main.post { onDone() }
                } else {
                    failed.add(url)
                }
            } catch (_: Exception) {
                failed.add(url)
            } finally {
                inflight.remove(url)
            }
        }
    }
}

class PostAdapter(
    private val onLinkClick: (String) -> Unit,
    private val onQuote: (PostItem) -> Unit = {},
    private val onMultiquoteToggle: (PostItem) -> Unit = {},
    // Única fuente de verdad de la selección: la mantiene MainActivity (replyQuotes).
    private val isSelected: (String) -> Boolean = { false },
    // Menú ⋮ de un post propio (editar / borrar). El View es el ancla del popup.
    private val onMenu: (PostItem, View) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<PostAdapter.Holder>() {

    private val items = ArrayList<PostItem>()
    private val seenPids = HashSet<String>()

    /** Tamaño de letra del cuerpo del post (Opciones → Fuente). Se aplica al renderizar. */
    var postTextSp = 15f
        set(value) { field = value; notifyDataSetChanged() }

    /** Texto plano de un post para citar: sin HTML y sin citas anidadas previas. */
    fun quoteBodyOf(item: PostItem): String {
        val noQuotes = item.html.replace(Regex("(?is)<blockquote.*?</blockquote>"), " ")
        val text = HtmlCompat.fromHtml(noQuotes, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        return text.replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    fun submit(list: List<PostItem>) {
        items.clear()
        seenPids.clear()
        for (p in list) if (seenPids.add(p.pid)) items.add(p)
        notifyDataSetChanged()
    }

    fun append(list: List<PostItem>) {
        var added = 0
        for (p in list) if (seenPids.add(p.pid)) { items.add(p); added++ }
        if (added > 0) notifyItemRangeInserted(items.size - added, added)
    }

    /**
     * Inserta una página ANTERIOR al principio (scroll hacia arriba tras un salto).
     * Devuelve cuántos entró: quien llama lo necesita para reanclar el scroll y que la
     * pantalla no pegue un salto al meter contenido por encima.
     */
    fun prepend(list: List<PostItem>): Int {
        val nuevos = list.filter { seenPids.add(it.pid) }
        if (nuevos.isEmpty()) return 0
        items.addAll(0, nuevos)
        notifyItemRangeInserted(0, nuevos.size)
        return nuevos.size
    }

    fun clear() = submit(emptyList())

    /** Página del post en la posición dada (para el indicador de página al hacer scroll). */
    fun pageAt(pos: Int): Int = items.getOrNull(pos)?.page ?: 1

    override fun getItemCount() = items.size

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: ImageView = v.findViewById(R.id.post_avatar)
        val author: TextView = v.findViewById(R.id.post_author)
        val date: TextView = v.findViewById(R.id.post_date)
        val content: TextView = v.findViewById(R.id.post_content)
        val quote: TextView = v.findViewById(R.id.post_quote)
        val multiquote: TextView = v.findViewById(R.id.post_multiquote)
        val menu: TextView = v.findViewById(R.id.post_menu)
        var boundPid: String = ""
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_post, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(h: Holder, pos: Int) {
        val item = items[pos]
        h.boundPid = item.pid
        h.author.text = if (item.author.isNotEmpty()) "@${item.author}" else "(anónimo)"
        h.date.text = item.date
        bindAvatar(h, item)
        renderContent(h, item)
        h.quote.setOnClickListener { onQuote(item) }
        paintMultiquote(h, isSelected(item.pid))
        h.multiquote.setOnClickListener { onMultiquoteToggle(item) }
        h.menu.visibility = if (item.own) View.VISIBLE else View.GONE
        h.menu.setOnClickListener { onMenu(item, h.menu) }
    }

    /** Repinta los marcadores de "+"/"✓" tras cambiar la selección desde fuera. */
    fun refreshSelection() = notifyDataSetChanged()

    private fun paintMultiquote(h: Holder, active: Boolean) {
        h.multiquote.text = if (active) "✓" else "＋"
        h.multiquote.setTextColor(if (active) 0xFFC8102E.toInt() else 0xFF9E9E9E.toInt())
    }

    private fun bindAvatar(h: Holder, item: PostItem) {
        // Avatar por defecto de FC (svg, no decodificable) o vacío → inicial de color.
        val url = item.avatar
        if (url.isEmpty() || url.endsWith(".svg")) {
            h.avatar.setImageBitmap(initialAvatar(item.author))
            return
        }
        val cached = PostImages.get(url)
        if (cached != null) {
            h.avatar.setImageBitmap(cached)
        } else {
            h.avatar.setImageBitmap(initialAvatar(item.author))
            val pid = item.pid
            PostImages.load(url) {
                if (h.boundPid == pid) PostImages.get(url)?.let { h.avatar.setImageBitmap(it) }
            }
        }
    }

    /** Renderiza el HTML simplificado del extractor. Las imágenes cargan async y
     *  re-renderizan el post cuando llegan (la caché evita bucles de descarga). */
    private fun renderContent(h: Holder, item: PostItem) {
        val tv = h.content
        tv.textSize = postTextSp
        val maxW = (tv.context.resources.displayMetrics.widthPixels * 0.78f).toInt()
        val pid = item.pid
        val getter = Html.ImageGetter { src ->
            val bmp = PostImages.get(src)
            if (bmp != null) {
                BitmapDrawable(tv.context.resources, bmp).apply {
                    val scale = if (bmp.width > maxW) maxW.toFloat() / bmp.width else 1f
                    setBounds(0, 0, (bmp.width * scale).toInt(), (bmp.height * scale).toInt())
                }
            } else {
                PostImages.load(src) { if (h.boundPid == pid) renderContent(h, item) }
                ColorDrawable(Color.TRANSPARENT).apply { setBounds(0, 0, 2, 2) }
            }
        }
        val spanned = HtmlCompat.fromHtml(item.html, HtmlCompat.FROM_HTML_MODE_LEGACY, getter, null)
        if (spanned is Spannable) {
            for (span in spanned.getSpans(0, spanned.length, URLSpan::class.java)) {
                val start = spanned.getSpanStart(span)
                val end = spanned.getSpanEnd(span)
                val flags = spanned.getSpanFlags(span)
                val url = span.url
                spanned.removeSpan(span)
                spanned.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) = onLinkClick(url)
                }, start, end, flags)
            }
        }
        tv.text = spanned
        tv.movementMethod = LinkMovementMethod.getInstance()
    }

    private val palette = intArrayOf(
        0xFFC8102E.toInt(), 0xFF00695C.toInt(), 0xFF3949AB.toInt(),
        0xFFEF6C00.toInt(), 0xFF6A1B9A.toInt(), 0xFF2E7D32.toInt(),
        0xFF00838F.toInt(), 0xFF8D6E63.toInt()
    )
    private val avatarCache = HashMap<String, Bitmap>()

    private fun initialAvatar(name: String): Bitmap {
        val key = name.ifBlank { "?" }
        avatarCache[key]?.let { return it }
        val size = 84
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = palette[Math.abs(key.lowercase().hashCode()) % palette.size]
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = Color.WHITE
        paint.textSize = size * 0.45f
        paint.textAlign = Paint.Align.CENTER
        val initial = key.trim().firstOrNull()?.uppercase() ?: "?"
        val fm = paint.fontMetrics
        canvas.drawText(initial, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, paint)
        avatarCache[key] = bmp
        return bmp
    }
}
