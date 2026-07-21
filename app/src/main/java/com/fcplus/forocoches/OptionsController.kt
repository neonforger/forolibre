package com.fcplus.forocoches

import android.content.SharedPreferences
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat

/**
 * Controlador del panel de Opciones (nativo): tamaño de fuente, cuenta (avatar/perfil),
 * filtro de palabras y usuarios ignorados. La lógica de filtrado ya existe en los repos;
 * esto solo le pone UI de gestión.
 */
class OptionsController(
    private val panel: View,
    private val ignoreRepo: IgnoreListRepository,
    private val keywordRepo: KeywordRepository,
    private val prefs: SharedPreferences,
    private val onFontChanged: () -> Unit,
    private val onListsChanged: () -> Unit,
    private val openUrl: (String) -> Unit
) {
    companion object {
        const val PREF_FONT_IDX = "post_font_idx"
        private val SP = floatArrayOf(13f, 15f, 18f)   // pequeña / normal / grande
        fun fontSp(prefs: SharedPreferences): Float =
            SP[prefs.getInt(PREF_FONT_IDX, 1).coerceIn(0, 2)]
    }

    private val fontSmall: TextView = panel.findViewById(R.id.opt_font_small)
    private val fontNormal: TextView = panel.findViewById(R.id.opt_font_normal)
    private val fontLarge: TextView = panel.findViewById(R.id.opt_font_large)
    private val kwSwitch: SwitchCompat = panel.findViewById(R.id.opt_kw_switch)
    private val kwInput: EditText = panel.findViewById(R.id.opt_kw_input)
    private val kwChips: LinearLayout = panel.findViewById(R.id.opt_kw_chips)
    private val igInput: EditText = panel.findViewById(R.id.opt_ig_input)
    private val igChips: LinearLayout = panel.findViewById(R.id.opt_ig_chips)

    init {
        fontSmall.setOnClickListener { setFont(0) }
        fontNormal.setOnClickListener { setFont(1) }
        fontLarge.setOnClickListener { setFont(2) }

        panel.findViewById<View>(R.id.opt_avatar).setOnClickListener {
            openUrl("https://forocoches.com/foro/profile.php?do=editavatar")
        }
        panel.findViewById<View>(R.id.opt_edit_profile).setOnClickListener {
            openUrl("https://forocoches.com/foro/profile.php?do=editprofile")
        }

        kwSwitch.setOnCheckedChangeListener { _, checked -> keywordRepo.setEnabled(checked) }
        panel.findViewById<View>(R.id.opt_kw_add).setOnClickListener { addKeyword() }
        panel.findViewById<View>(R.id.opt_ig_add).setOnClickListener { addIgnored() }
    }

    /** Refresca todo el panel con el estado actual (llamar al abrirlo). */
    fun bind() {
        paintFont(prefs.getInt(PREF_FONT_IDX, 1))
        kwSwitch.isChecked = keywordRepo.isEnabled()
        renderKeywords()
        renderIgnored()
    }

    private fun setFont(idx: Int) {
        prefs.edit().putInt(PREF_FONT_IDX, idx).apply()
        paintFont(idx)
        onFontChanged()
    }

    private fun paintFont(idx: Int) {
        val chips = listOf(fontSmall, fontNormal, fontLarge)
        chips.forEachIndexed { i, tv ->
            val on = i == idx
            tv.setBackgroundColor(if (on) 0xFFC8102E.toInt() else 0xFFF0F0F0.toInt())
            tv.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xFF616161.toInt())
        }
    }

    private fun addKeyword() {
        val w = kwInput.text.toString().trim()
        if (w.isEmpty()) return
        keywordRepo.addKeyword(w)
        kwInput.setText("")
        renderKeywords()
        onListsChanged()
    }

    private fun addIgnored() {
        val u = igInput.text.toString().trim().removePrefix("@")
        if (u.isEmpty()) return
        val cur = ignoreRepo.getIgnoredUsers().toMutableList()
        if (cur.none { it.equals(u, ignoreCase = true) }) cur.add(u)
        ignoreRepo.setIgnoredUsers(cur)
        igInput.setText("")
        renderIgnored()
        onListsChanged()
    }

    private fun renderKeywords() {
        kwChips.removeAllViews()
        for (w in keywordRepo.getKeywords().sortedBy { it.lowercase() }) {
            kwChips.addView(chipRow(w) {
                keywordRepo.removeKeyword(w)
                renderKeywords()
                onListsChanged()
            })
        }
    }

    private fun renderIgnored() {
        igChips.removeAllViews()
        val users = ignoreRepo.getIgnoredUsers().sortedBy { it.lowercase() }
        for (u in users) {
            igChips.addView(chipRow("@$u") {
                ignoreRepo.setIgnoredUsers(users.filterNot { it == u })
                renderIgnored()
                onListsChanged()
            })
        }
    }

    private fun chipRow(label: String, onRemove: () -> Unit): View {
        val row = LayoutInflater.from(panel.context).inflate(R.layout.item_opt_chip, kwChips, false)
        row.findViewById<TextView>(R.id.chip_label).text = label
        row.findViewById<View>(R.id.chip_remove).setOnClickListener { onRemove() }
        return row
    }
}
