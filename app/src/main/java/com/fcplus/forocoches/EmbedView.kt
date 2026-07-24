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
 * Embed interactivo. Al enlazarse el post monta DIRECTAMENTE (sin toque) un WebView que carga
 * el reproductor OFICIAL de la plataforma; el vídeo se reproduce inline al darle al play del
 * propio reproductor, sin salir de la app. La carga se difiere ~300 ms: así los posts que
 * pasan volando en un fling NO llegan a crear un WebView (se cancela al reciclar), y los hilos
 * largos siguen fluidos. No es el foro: la regla de oro no aplica a contenido de X/IG/TikTok/
 * YouTube dentro de nuestra tarjeta.
 */
@SuppressLint("SetJavaScriptEnabled")
class EmbedView(context: Context) : FrameLayout(context) {

    private var web: WebView? = null
    private var spec: EmbedSpec? = null
    private var pendingLoad: Runnable? = null

    /** Callback para pedir modo pantalla completa de vídeo al Activity anfitrión. */
    var onFullscreen: ((View?, WebChromeClient.CustomViewCallback?) -> Unit)? = null

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    fun bind(spec: EmbedSpec) {
        this.spec = spec
        release()
        removeAllViews()
        addView(buildLoading())
        // Auto-carga diferida: si el post se recicla antes de los 300 ms (fling), el WebView
        // no llega a crearse (release() cancela el Runnable).
        pendingLoad = Runnable { load(spec) }.also { postDelayed(it, 300) }
    }

    /** Libera el WebView y cancela una carga pendiente (al reciclar el post o salir del hilo). */
    fun release() {
        pendingLoad?.let { removeCallbacks(it) }
        pendingLoad = null
        web?.let {
            it.stopLoading()
            it.loadUrl("about:blank")
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        web = null
    }

    /** Marcador ligero mientras carga el reproductor (no interactivo, evita saltos bruscos). */
    private fun buildLoading(): View {
        return TextView(context).apply {
            text = "Cargando contenido…"
            setTextColor(0xFF9E9E9E.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(16), dp(14), dp(16))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6); bottomMargin = dp(2) }
            background = roundedBg(0xFFF2F2F2.toInt())
        }
    }

    private fun load(spec: EmbedSpec) {
        removeAllViews()
        val w = WebView(context.applicationContext).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(220))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            // El embed se muestra montado pero NO autoreproduce: el vídeo arranca al pulsar el
            // play del propio reproductor (un gesto del usuario), inline. Sin audio al scrollear.
            settings.mediaPlaybackRequiresUserGesture = true
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
        // El embed de YouTube es un iframe DIRECTO a youtube.com/embed y su handshake postMessage
        // exige ser cross-origin respecto a la página anfitriona. Si la base fuera youtube.com el
        // iframe quedaría mismo-origen y el reproductor falla (error 150/152). Origen de tercero:
        "youtube" -> "https://forocoches.com"
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
                """<div class="yt"><iframe src="https://www.youtube.com/embed/${esc(spec.id)}?playsinline=1&rel=0&origin=https%3A%2F%2Fforocoches.com"
                   frameborder="0" allow="encrypted-media; picture-in-picture" allowfullscreen></iframe></div>"""
            "video" ->
                """<video src="${esc(spec.url)}" controls playsinline preload="metadata" style="width:100%;height:auto"></video>"""
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

    private fun roundedBg(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(10).toFloat()
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "&quot;")
        .replace("<", "&lt;").replace(">", "&gt;")
}
