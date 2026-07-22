package com.fcplus.forocoches

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

/** Una fila de la bandeja de MPs (extraída de private.php por el motor). */
data class PmItem(
    val pmid: String,
    val subject: String,
    val sender: String,
    val senderId: String,
    val date: String,
    val unread: Boolean
)

fun parsePmInbox(json: String): List<PmItem> {
    val arr = try { JSONObject(json).optJSONArray("pms") } catch (_: Exception) { null } ?: return emptyList()
    val out = ArrayList<PmItem>()
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        val pmid = o.optString("pmid").trim()
        if (pmid.isEmpty()) continue
        out.add(
            PmItem(
                pmid = pmid,
                subject = o.optString("subject").trim(),
                sender = o.optString("sender").trim(),
                senderId = o.optString("senderId").trim(),
                date = o.optString("date").trim(),
                unread = o.optBoolean("unread", false)
            )
        )
    }
    return out
}

/** Bandeja nativa de mensajes privados. */
class PmInboxAdapter(
    private val onClick: (PmItem) -> Unit
) : RecyclerView.Adapter<PmInboxAdapter.Holder>() {

    private val items = ArrayList<PmItem>()

    fun submit(list: List<PmItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val dot: View = v.findViewById(R.id.pm_unread_dot)
        val subject: TextView = v.findViewById(R.id.pm_subject)
        val meta: TextView = v.findViewById(R.id.pm_meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_pm, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: Holder, pos: Int) {
        val pm = items[pos]
        h.subject.text = pm.subject.ifEmpty { "(sin asunto)" }
        h.meta.text = when {
            pm.sender.isNotEmpty() && pm.date.isNotEmpty() -> "@${pm.sender} · ${pm.date}"
            pm.sender.isNotEmpty() -> "@${pm.sender}"
            else -> pm.date
        }
        h.dot.visibility = if (pm.unread) View.VISIBLE else View.GONE
        h.itemView.setOnClickListener { onClick(pm) }
    }
}
