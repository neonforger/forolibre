package com.fcplus.forocoches

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

data class ThreadItem(
    val tid: String,
    val title: String,
    val author: String,
    val replies: String,
    val time: String,
    val url: String
)

data class MenuLinks(
    val pm: String?,
    val mentions: String?,
    val quotes: String?,
    val favs: String?,
    val profile: String?
)

data class ForumTab(val fid: Int, val name: String)

/** Parsea el payload de fcLoadForumList. Lista vacía si el JSON no es válido. */
fun parseForumListPayload(json: String): List<ForumTab> {
    return try {
        val arr = JSONObject(json).getJSONArray("forums")
        val list = ArrayList<ForumTab>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val fid = o.optString("fid").toIntOrNull() ?: continue
            val name = o.optString("name").trim()
            if (name.isEmpty()) continue
            list.add(ForumTab(fid, name))
        }
        list
    } catch (_: Exception) {
        emptyList()
    }
}

data class ShellPayload(
    val url: String,
    val menu: MenuLinks,
    val threads: List<ThreadItem>,
    val pmCount: Int = 0,
    val quotesCount: Int = 0,
    val mentionsCount: Int = 0
) {
    /** Página del listado que representa este payload (forumdisplay ...&page=N). */
    val page: Int
        get() = Regex("[?&]page=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
}

/** Parsea el payload de extractor.js. Devuelve null si el JSON no es válido. */
fun parseThreadListPayload(json: String): ShellPayload? {
    return try {
        val root = JSONObject(json)
        val menuObj = root.optJSONObject("menu")
        val menu = MenuLinks(
            pm = menuObj?.optString("pm")?.ifBlank { null },
            mentions = menuObj?.optString("mentions")?.ifBlank { null },
            quotes = menuObj?.optString("quotes")?.ifBlank { null },
            favs = menuObj?.optString("favs")?.ifBlank { null },
            profile = menuObj?.optString("profile")?.ifBlank { null }
        )
        val arr = root.getJSONArray("threads")
        val list = ArrayList<ThreadItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val title = o.optString("title").trim()
            val tid = o.optString("tid").trim()
            if (title.isEmpty() || tid.isEmpty()) continue
            list.add(
                ThreadItem(
                    tid = tid,
                    title = title,
                    author = o.optString("author").trim(),
                    replies = o.optString("replies").trim(),
                    time = o.optString("time").trim(),
                    url = o.optString("url").trim()
                )
            )
        }
        val counts = root.optJSONObject("counts")
        ShellPayload(
            root.optString("url"), menu, list,
            pmCount = counts?.optInt("pm") ?: 0,
            quotesCount = counts?.optInt("quotes") ?: 0,
            mentionsCount = counts?.optInt("mentions") ?: 0
        )
    } catch (_: Exception) {
        null
    }
}

class ThreadListAdapter(
    private val onClick: (ThreadItem) -> Unit
) : RecyclerView.Adapter<ThreadListAdapter.Holder>() {

    private val items = ArrayList<ThreadItem>()
    // vBulletin ordena por último post y el foro se mueve entre páginas: un hilo de la
    // página 1 puede reaparecer en la 2. Dedupe por tid al hacer append.
    private val seenTids = HashSet<String>()

    fun submit(list: List<ThreadItem>) {
        items.clear()
        seenTids.clear()
        for (t in list) if (seenTids.add(t.tid)) items.add(t)
        notifyDataSetChanged()
    }

    /** Añade una página más (scroll infinito), saltando hilos ya mostrados. */
    fun append(list: List<ThreadItem>) {
        var added = 0
        for (t in list) {
            if (seenTids.add(t.tid)) { items.add(t); added++ }
        }
        if (added > 0) notifyItemRangeInserted(items.size - added, added)
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: ImageView = v.findViewById(R.id.thread_avatar)
        val title: TextView = v.findViewById(R.id.thread_title)
        val meta: TextView = v.findViewById(R.id.thread_meta)
        val replies: TextView = v.findViewById(R.id.thread_replies)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_thread, parent, false)
        return Holder(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: Holder, pos: Int) {
        val item = items[pos]
        h.title.text = item.title
        val author = if (item.author.isNotEmpty()) "@${item.author}" else ""
        h.meta.text = listOf(author, item.time).filter { it.isNotEmpty() }.joinToString("  ·  ")
        h.replies.text = if (item.replies.isNotEmpty()) "💬 ${item.replies}" else ""
        h.avatar.setImageBitmap(initialAvatar(item.author))
        h.itemView.setOnClickListener { onClick(item) }
    }

    // Paleta estable por hash del nombre (el listado de FC no trae avatares).
    private val palette = intArrayOf(
        0xFFC8102E.toInt(), 0xFF00695C.toInt(), 0xFF3949AB.toInt(),
        0xFFEF6C00.toInt(), 0xFF6A1B9A.toInt(), 0xFF2E7D32.toInt(),
        0xFF00838F.toInt(), 0xFF8D6E63.toInt()
    )

    private val avatarCache = HashMap<String, Bitmap>()

    private fun initialAvatar(name: String): Bitmap {
        val key = name.ifBlank { "?" }
        avatarCache[key]?.let { return it }
        val size = 96
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
