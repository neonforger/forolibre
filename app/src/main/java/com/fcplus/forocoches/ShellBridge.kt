package com.fcplus.forocoches

import android.webkit.JavascriptInterface

/**
 * Bridge del shell nativo (rama v2-shell): extractor.js entrega aquí el listado
 * parseado (JSON) o un error. Los callbacks llegan en el pool de JS del WebView;
 * quien los reciba debe saltar al hilo de UI.
 */
class ShellBridge(
    private val onList: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onForums: (String) -> Unit
) {
    @JavascriptInterface
    fun onThreadList(json: String) = onList(json)

    @JavascriptInterface
    fun onListError(reason: String) = onError(reason)

    @JavascriptInterface
    fun onForumList(json: String) = onForums(json)
}
