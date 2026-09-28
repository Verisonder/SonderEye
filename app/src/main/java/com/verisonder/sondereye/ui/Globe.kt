package com.verisonder.sondereye.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.verisonder.sondereye.core.Json

/** What the page may call. Everything is posted to the main thread. */
class GlobeBridge(
    private val onReady: () -> Unit,
    private val onSelect: (String?) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface fun ready() { main.post(onReady) }
    @JavascriptInterface fun select(id: String) { main.post { onSelect(id.ifEmpty { null }) } }
    @JavascriptInterface fun error(message: String) { main.post { onError(message.take(300)) } }
}

/**
 * Owns the globe WebView. Data sent before the page is ready is kept and delivered on
 * ready, so nothing depends on timing. After a renderer crash a fresh WebView is built
 * by [rebuild]; the last data is replayed into it the same way.
 */
class GlobeController(
    private val context: Context,
    private val onReady: () -> Unit,
    private val onSelect: (String?) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var ready = false
    private var quakePayload: String? = null
    private var selectedId: String? = null

    // Declared after the fields above: build() starts the page load, and its callbacks
    // must find them initialised.
    var webView: WebView = build()
        private set

    fun setQuakes(payload: String) {
        quakePayload = payload
        if (ready) js("SE.setQuakes($payload)")
    }

    fun select(id: String?) {
        selectedId = id
        if (ready) js("SE.select(${if (id == null) "null" else Json.str(id)})")
    }

    fun home() {
        selectedId = null
        if (ready) js("SE.home()")
    }

    /** Throws the current WebView away and starts a new one. Returns the new view. */
    fun rebuild(): WebView {
        ready = false
        runCatching { webView.destroy() }
        webView = build()
        return webView
    }

    fun onResume() = webView.onResume()
    fun onPause() = webView.onPause()
    fun destroy() = runCatching { webView.destroy() }

    private fun js(code: String) = webView.evaluateJavascript(code, null)

    private fun handleReady() {
        ready = true
        quakePayload?.let { js("SE.setQuakes($it)") }
        selectedId?.let { js("SE.select(${Json.str(it)})") }
        onReady()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun build(): WebView {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        return WebView(context).apply {
            setBackgroundColor(0xFF03060A.toInt()) // no white flash before the page paints
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            addJavascriptInterface(GlobeBridge(::handleReady, onSelect, onError), "SonderEyeApp")

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)

                // The page never navigates. Links (imagery credits) open in the browser.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    return true
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) onError("Globe page failed to load: ${error.description}")
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    ready = false
                    onError(
                        if (detail.didCrash()) "Globe crashed. Tap here to restart it."
                        else "Globe was stopped by the system to free memory. Tap here to restart it."
                    )
                    return true // the app survives; the view is replaced on tap
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        onError("Globe: ${m.message().take(240)}")
                    }
                    return true
                }
            }

            loadUrl(PAGE)
        }
    }

    companion object {
        const val PAGE = "https://appassets.androidplatform.net/assets/globe/index.html"

        fun openUrl(context: Context, url: String) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
    }
}
