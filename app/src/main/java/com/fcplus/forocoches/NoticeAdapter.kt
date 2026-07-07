package com.fcplus.forocoches

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

data class NoticeItem(val url: String, val title: String, val who: String, val text: String)

data class NoticesPayload(val kind: String, val items: List<NoticeItem>, val error: String)

fun parseNoticesPayload(json: String): NoticesPayload? = try {
    val root = JSONObject(json)
    val err = root.optString("error", "")
    val arr = root.optJSONArray("items")
    val items = ArrayList<NoticeItem>()
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val url = o.optString("url").trim()
            if (url.isEmpty()) continue
            items.add(
                NoticeItem(
                    url = url,
                    title = o.optString("title").trim(),
                    who = o.optString("who").trim(),
                    text = o.optString("text").trim()
                )
            )
        }
    }
    NoticesPayload(root.optString("kind"), items, err)
} catch (_: Exception) {
    null
}

/** Lista nativa de citas o menciones (Bloque B), aisladas de todo lo demás. */
class NoticeAdapter(
    private val onClick: (NoticeItem) -> Unit
) : RecyclerView.Adapter<NoticeAdapter.Holder>() {

    private val items = ArrayList<NoticeItem>()

    fun submit(list: List<NoticeItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.notice_title)
        val sub: TextView = v.findViewById(R.id.notice_sub)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_notice, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: Holder, pos: Int) {
        val n = items[pos]
        h.title.text = n.title.ifEmpty { "(hilo)" }
        // Subtítulo: quién + el resto de la fila (fecha etc.) sin repetir el título.
        val extra = n.text.replace(n.title, "").replace(Regex("\\s+"), " ").trim()
        h.sub.text = when {
            n.who.isNotEmpty() && extra.isNotEmpty() -> "@${n.who} · $extra"
            n.who.isNotEmpty() -> "@${n.who}"
            else -> extra
        }
        h.itemView.setOnClickListener { onClick(n) }
    }
}
