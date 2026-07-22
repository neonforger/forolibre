package com.fcplus.forocoches

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray

/** Un embed dentro de un post: red social o multimedia con su reproductor oficial. */
data class EmbedSpec(
    val kind: String,   // twitter | instagram | tiktok | youtube | video | iframe
    val url: String,
    val id: String
)

fun parseEmbeds(arr: JSONArray?): List<EmbedSpec> {
    if (arr == null) return emptyList()
    val out = ArrayList<EmbedSpec>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val kind = o.optString("kind").trim()
        if (kind.isEmpty()) continue
        out.add(EmbedSpec(kind, o.optString("url").trim(), o.optString("id").trim()))
    }
    return out
}

/**
 * Embed interactivo. Muestra una tarjeta-marcador ligera (nativa) y, al tocarla, monta un
 * WebView que carga el reproductor OFICIAL de la plataforma y reproduce el vídeo inline —
 * sin salir de la app. Cargar bajo demanda mantiene los hilos largos fluidos (nada de un
 * WebView por tweet de golpe). No es el foro: la regla de oro no aplica a contenido de X/
 * IG/TikTok/YouTube dentro de nuestra tarjeta.
 */
@SuppressLint("SetJavaScriptEnabled")
class EmbedView(context: Context) : FrameLayout(context) {

    private var web: WebView? = null
    private var spec: EmbedSpec? = null

    /** Callback para pedir modo pantalla completa de vídeo al Activity anfitrión. */
    var onFullscreen: ((View?, WebChromeClient.CustomViewCallback?) -> Unit)? = null

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    fun bind(spec: EmbedSpec) {
        this.spec = spec
        release()
        removeAllViews()
        addView(buildPlaceholder(spec))
    }

    /** Libera el WebView (al reciclar el post o salir del hilo). */
    fun release() {
        web?.let {
            it.stopLoading()
            it.loadUrl("about:blank")
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        web = null
    }

    private fun buildPlaceholder(spec: EmbedSpec): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6); bottomMargin = dp(2) }
            background = roundedBg(0xFFF2F2F2.toInt())
            isClickable = true
            setOnClickListener { load(spec) }
        }
        val play = TextView(context).apply {
            text = "▶"
            setTextColor(labelColor(spec.kind))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(12) }
        }
        val label = TextView(context).apply {
            text = labelFor(spec.kind)
            setTextColor(0xFF1A1A1A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        row.addView(play)
        row.addView(label)
        return row
    }

    private fun load(spec: EmbedSpec) {
        removeAllViews()
        val w = WebView(context.applicationContext).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(220))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.mediaPlaybackRequiresUserGesture = false  // vídeo inline sin 2º toque
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setBackgroundColor(Color.WHITE)
            addJavascriptInterface(HeightBridge(), "AndroidEmbed")
            webViewClient = object : android.webkit.WebViewClient() {
                // Solo intercepta la navegación del MARCO PRINCIPAL (clic en un enlace del
                // embed → navegador externo). Los subrecursos (widgets.js, iframe de vídeo,
                // etc.) NO son main-frame y se cargan con normalidad: el reproductor funciona.
                override fun shouldOverrideUrlLoading(
                    view: WebView, req: android.webkit.WebResourceRequest
                ): Boolean {
                    if (!req.isForMainFrame) return false
                    val u = req.url.toString()
                    if (u.startsWith("http")) {
                        try {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, req.url)
                                    .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                            )
                        } catch (_: Exception) {}
                        return true
                    }
                    return false
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    onFullscreen?.invoke(view, callback)
                }
                override fun onHideCustomView() { onFullscreen?.invoke(null, null) }
            }
        }
        web = w
        addView(w)
        w.loadDataWithBaseURL(baseUrlFor(spec.kind), pageHtml(spec), "text/html", "UTF-8", null)
    }

    private inner class HeightBridge {
        @JavascriptInterface
        fun onHeight(cssPx: Int) {
            if (cssPx <= 0) return
            post {
                val w = web ?: return@post
                val px = (cssPx * resources.displayMetrics.density).toInt()
                    .coerceIn(dp(120), dp(2200))
                if (w.layoutParams.height != px) {
                    w.layoutParams = w.layoutParams.apply { height = px }
                    w.requestLayout()
                }
            }
        }
    }

    private fun baseUrlFor(kind: String) = when (kind) {
        "twitter" -> "https://twitter.com"
        "instagram" -> "https://www.instagram.com"
        "tiktok" -> "https://www.tiktok.com"
        else -> "https://www.youtube.com"
    }

    /** HTML mínimo que monta el embed OFICIAL de cada plataforma + reporte de altura. */
    private fun pageHtml(spec: EmbedSpec): String {
        val body = when (spec.kind) {
            "twitter" ->
                """<blockquote class="twitter-tweet" data-dnt="true" data-conversation="none">
                   <a href="${esc(spec.url)}"></a></blockquote>
                   <script async src="https://platform.twitter.com/widgets.js"></script>"""
            "instagram" ->
                """<blockquote class="instagram-media" data-instgrm-permalink="${esc(spec.url)}"
                   data-instgrm-version="14" style="margin:0;width:100%"></blockquote>
                   <script async src="https://www.instagram.com/embed.js"></script>"""
            "tiktok" ->
                """<blockquote class="tiktok-embed" cite="${esc(spec.url)}"
                   data-video-id="${esc(spec.id)}" style="margin:0"><section></section></blockquote>
                   <script async src="https://www.tiktok.com/embed.js"></script>"""
            "youtube" ->
                """<div class="yt"><iframe src="https://www.youtube.com/embed/${esc(spec.id)}?playsinline=1&autoplay=1&rel=0"
                   frameborder="0" allow="autoplay; encrypted-media; picture-in-picture" allowfullscreen></iframe></div>"""
            "video" ->
                """<video src="${esc(spec.url)}" controls playsinline autoplay style="width:100%;height:auto"></video>"""
            else ->
                """<iframe src="${esc(spec.url)}" frameborder="0" allow="autoplay; encrypted-media; fullscreen"
                   allowfullscreen style="width:100%;border:0;min-height:200px"></iframe>"""
        }
        return """<!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
            <style>
              html,body{margin:0;padding:0;background:#fff;overflow-x:hidden}
              .yt{position:relative;width:100%;padding-bottom:56.25%;height:0}
              .yt iframe{position:absolute;top:0;left:0;width:100%;height:100%}
              iframe,video{max-width:100%}
            </style></head><body>$body
            <script>
              function rep(){try{AndroidEmbed.onHeight(document.body.scrollHeight);}catch(e){}}
              window.addEventListener('load',rep);
              var n=0,t=setInterval(function(){rep();if(++n>20)clearInterval(t);},400);
              try{new ResizeObserver(rep).observe(document.body);}catch(e){}
            </script></body></html>"""
    }

    private fun labelFor(kind: String) = when (kind) {
        "twitter" -> "Ver publicación de X"
        "instagram" -> "Ver publicación de Instagram"
        "tiktok" -> "Ver vídeo de TikTok"
        "youtube" -> "Ver vídeo de YouTube"
        "video" -> "Reproducir vídeo"
        else -> "Ver contenido"
    }

    private fun labelColor(kind: String) = when (kind) {
        "youtube" -> 0xFFFF0000.toInt()
        "instagram" -> 0xFFC13584.toInt()
        else -> 0xFFC8102E.toInt()
    }

    private fun roundedBg(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(10).toFloat()
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "&quot;")
        .replace("<", "&lt;").replace(">", "&gt;")
}
