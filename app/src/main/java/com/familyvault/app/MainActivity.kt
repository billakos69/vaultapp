package com.familyvault.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * ====================================================================
 *  ΑΛΛΑΞΕ ΕΔΩ το raw URL του entries.json στο GitHub repo σου, π.χ.:
 *  "https://raw.githubusercontent.com/<user>/<repo>/main/data/entries.json"
 * ====================================================================
 */
private const val ENTRIES_URL =
    "https://raw.githubusercontent.com/billakos69/vaultapp/main/data/entries.json"

private const val CACHE_FILE_NAME = "entries_cache.json"

private data class PendingDownload(val url: String, val fileName: String, val mime: String?)
private data class ProbeResult(val code: Int, val mime: String?, val disposition: String?)

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingDownload: PendingDownload? = null

    // Μόνο για Android 8/9 (API 26-28): χρειάζεται άδεια για αποθήκευση στις Λήψεις.
    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val p = pendingDownload
            pendingDownload = null
            if (granted && p != null) enqueue(p)
            else toast("Χρειάζεται άδεια αποθήκευσης για να κατέβει το αρχείο.")
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false   // τα assets φορτώνονται κανονικά και χωρίς αυτό

        // Γέφυρα JS -> Android: Android.download(url, name) / Android.open(url)
        webView.addJavascriptInterface(NativeBridge(), "Android")

        webView.setDownloadListener { url, _, _, _, _ -> startDownload(url, "") }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                loadEntries()
            }

            // Οτιδήποτε δεν είναι το τοπικό vault.html ανοίγει εξωτερικά, όχι μέσα στο WebView.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.scheme == "file") return false
                openExternal(request.url.toString())
                return true
            }
        }

        webView.loadUrl("file:///android_asset/vault.html")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    //  ΛΗΨΗ ΑΡΧΕΙΩΝ
    // ------------------------------------------------------------------

    inner class NativeBridge {
        @JavascriptInterface
        fun download(url: String?, name: String?) {
            if (url.isNullOrBlank()) return
            startDownload(url.trim(), name?.trim().orEmpty())
        }

        @JavascriptInterface
        fun open(url: String?) {
            if (url.isNullOrBlank()) return
            runOnUiThread { openExternal(url.trim()) }
        }
    }

    private fun startDownload(rawUrl: String, displayName: String) {
        val direct = toDirectUrl(rawUrl)
        if (direct == null) {
            toast("Ο σύνδεσμος δεν υποστηρίζεται (οι φάκελοι του Drive δεν κατεβαίνουν· βάλε σύνδεσμο αρχείου).")
            return
        }
        toast("Ξεκινά η λήψη…")
        scope.launch {
            val info = withContext(Dispatchers.IO) { probe(direct) }
            when {
                info == null ->
                    toast("Αποτυχία σύνδεσης. Έλεγξε το internet.")
                info.code == 404 ->
                    toast("Το αρχείο δεν βρέθηκε (ο σύνδεσμος έχει διαγραφεί ή είναι λάθος).")
                info.code != 200 || info.mime?.startsWith("text/html") == true ->
                    // Το Drive επιστρέφει σελίδα σύνδεσης αντί για αρχείο όταν δεν είναι κοινόχρηστο.
                    toast("Το αρχείο δεν είναι κοινόχρηστο. Στο Drive όρισε «Οποιοσδήποτε με τον σύνδεσμο».")
                else -> {
                    val serverName = serverFileName(info.disposition)
                    val name = buildFileName(displayName, info.mime, serverName)
                    val ext = Regex("\\.([A-Za-z0-9]+)$").find(name)?.groupValues?.get(1)?.lowercase()
                    val mime = ext?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
                        ?: info.mime?.substringBefore(';')?.trim()
                    val p = PendingDownload(direct, name, mime)
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                        ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingDownload = p
                        storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        enqueue(p)
                    }
                }
            }
        }
    }

    /** Μετατρέπει συνδέσμους Google Drive/Docs σε σύνδεσμο άμεσης λήψης. */
    private fun toDirectUrl(raw: String): String? {
        val uri = try { Uri.parse(raw) } catch (_: Exception) { return null }
        if (uri.scheme != "https") return null
        val host = uri.host ?: return null
        val isGoogle = host == "drive.google.com" || host == "docs.google.com" ||
            host == "drive.usercontent.google.com"
        if (!isGoogle) return raw

        val path = uri.path ?: ""
        if (path.contains("/folders/")) return null
        val id = Regex("/(?:file|document|spreadsheets|presentation)/d/([A-Za-z0-9_-]+)")
            .find(path)?.groupValues?.get(1)
            ?: uri.getQueryParameter("id")
            ?: return null

        return when {
            path.startsWith("/document/") ->
                "https://docs.google.com/document/d/$id/export?format=pdf"
            path.startsWith("/spreadsheets/") ->
                "https://docs.google.com/spreadsheets/d/$id/export?format=xlsx"
            path.startsWith("/presentation/") ->
                "https://docs.google.com/presentation/d/$id/export?format=pptx"
            // confirm=t προσπερνά την προειδοποίηση «δεν ελέγχεται για ιούς» των μεγάλων αρχείων
            else -> "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t"
        }
    }

    /** Ανοίγει τη σύνδεση μόνο για να διαβάσει τα headers (όνομα/τύπο αρχείου) και την κλείνει. */
    private fun probe(url: String): ProbeResult? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            val code = conn.responseCode
            val result = ProbeResult(code, conn.contentType, conn.getHeaderField("Content-Disposition"))
            conn.disconnect()
            result
        } catch (e: Exception) {
            null
        }
    }

    private fun serverFileName(cd: String?): String? {
        if (cd == null) return null
        Regex("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let {
            return try {
                val charset = it.groupValues[1].ifBlank { "UTF-8" }
                URLDecoder.decode(it.groupValues[2].trim().replace("+", "%2B"), charset)
            } catch (_: Exception) { null }
        }
        Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)?.let {
            return it.groupValues[1].trim()
        }
        return null
    }

    private fun buildFileName(display: String, mime: String?, serverName: String?): String {
        val extRegex = Regex("\\.[A-Za-z][A-Za-z0-9]{0,4}$")
        var base = display.ifBlank { serverName ?: "αρχείο" }
        if (!extRegex.containsMatchIn(base)) {
            val ext = serverName?.let { extRegex.find(it)?.value }
                ?: mime?.substringBefore(';')?.trim()
                    ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                    ?.let { ".$it" }
            if (ext != null) base += ext
        }
        return base.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(120)
    }

    private fun enqueue(p: PendingDownload) {
        try {
            val req = DownloadManager.Request(Uri.parse(p.url))
                .setTitle(p.fileName)
                .setDescription("Family Vault")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, p.fileName)
                .setAllowedOverMetered(true)
            p.mime?.let { req.setMimeType(it) }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast("Η λήψη ξεκίνησε. Θα το βρεις στις Λήψεις (Downloads) του κινητού.")
        } catch (e: Exception) {
            toast("Η λήψη απέτυχε: ${e.message ?: "άγνωστο σφάλμα"}")
        }
    }

    private fun openExternal(url: String) {
        try {
            val uri = Uri.parse(url)
            if (uri.scheme != "https") return
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            toast("Δεν βρέθηκε εφαρμογή για να ανοίξει ο σύνδεσμος.")
        } catch (_: Exception) { }
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    // ------------------------------------------------------------------
    //  ΦΟΡΤΩΣΗ entries.json
    // ------------------------------------------------------------------

    /** Φέρνει entries.json από GitHub. Αν αποτύχει, πέφτει πίσω στο τελευταίο cached αντίγραφο. */
    private fun loadEntries() {
        scope.launch {
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
