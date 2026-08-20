package com.familyvault.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * ====================================================================
 *  ΑΛΛΑΞΕ ΕΔΩ το raw URL του entries.json στο GitHub repo σου, π.χ.:
 *  "https://raw.githubusercontent.com/<user>/<repo>/main/data/entries.json"
 * ====================================================================
 */
private const val ENTRIES_URL =
    "https://raw.githubusercontent.com/USERNAME/REPO/main/data/entries.json"

private const val CACHE_FILE_NAME = "entries_cache.json"

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                loadEntries()
            }
        }

        webView.loadUrl("file:///android_asset/vault.html")
    }

    /** Φέρνει entries.json από GitHub. Αν αποτύχει, πέφτει πίσω στο τελευταίο cached αντίγραφο. */
    private fun loadEntries() {
        CoroutineScope(Dispatchers.Main).launch {
            val json = withContext(Dispatchers.IO) { fetchFresh() ?: readCache() }
            if (json != null) {
                injectEntries(json)
            }
        }
    }

    private fun fetchFresh(): String? {
        return try {
            val conn = URL(ENTRIES_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            // αποφυγή cached απάντησης από το raw.githubusercontent.com CDN
            conn.setRequestProperty("Cache-Control", "no-cache")

            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                writeCache(text)
                text
            } else {
                conn.disconnect()
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun cacheFile(): File = File(filesDir, CACHE_FILE_NAME)

    private fun writeCache(text: String) {
        try { cacheFile().writeText(text) } catch (_: Exception) { }
    }

    private fun readCache(): String? {
        return try {
            val f = cacheFile()
            if (f.exists()) f.readText() else null
        } catch (e: Exception) {
            null
        }
    }

    private fun injectEntries(entriesJson: String) {
        // περνάμε το JSON ως string literal μέσα σε JS - Base64 ώστε να μην χρειάζεται escaping
        val b64 = android.util.Base64.encodeToString(
            entriesJson.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        val js = "window.setEntriesFromNative && window.setEntriesFromNative('$b64');"
        webView.evaluateJavascript(js, null)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
