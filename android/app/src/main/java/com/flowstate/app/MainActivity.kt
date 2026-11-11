
package com.flowstate.app

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    /** Items shared into FlowState, already copied into app-private storage. */
    private val pendingImports = mutableListOf<ImportedFile>()

    private data class ImportedFile(
        val id: String,
        val name: String,
        val mime: String,
        val size: Long,
        val url: String,
        val text: String
    )

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileCallback
        fileCallback = null
        if (callback == null) return@registerForActivityResult

        val data = result.data
        val uris = if (result.resultCode == RESULT_OK && data != null) {
            val clip = data.clipData
            when {
                clip != null -> Array(clip.itemCount) { index -> clip.getItemAt(index).uri }
                data.data != null -> arrayOf(data.data!!)
                else -> null
            }
        } else null
        callback.onReceiveValue(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        webView.settings.setSupportMultipleWindows(false)
        webView.settings.loadsImagesAutomatically = true
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.mediaPlaybackRequiresUserGesture = true

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // Only our own page can reach this bridge: everything else is handed to
        // the system browser, so arbitrary websites get no native access.
        webView.addJavascriptInterface(ImportBridge(), "FlowStateAndroid")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                if (uri.scheme == "flowstate" && uri.host == "browse") {
                    val target = uri.getQueryParameter("url")
                    if (target != null && (target.startsWith("https://") || target.startsWith("http://"))) {
                        openInAppBrowser(target)
                    }
                    return true
                }
                val host = uri.host?.lowercase() ?: return true
                if (uri.scheme == "https" && host in trustedHosts) return false

                if (uri.scheme == "https" || uri.scheme == "http") {
                    openExternal(uri)
                }
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val uri = request.url
                if (uri.host != ASSET_HOST) return null
                val path = uri.path ?: return null
                if (!path.startsWith("/shared/")) return null
                val rel = path.removePrefix("/shared/")
                if (rel.isEmpty() || rel.contains("..") || rel.contains('/')) return null

                val root = sharedDir()
                val file = File(root, rel)
                if (!file.exists() || !file.canonicalPath.startsWith(root.canonicalPath)) return null

                val mime = mimeFor(file.name)
                val headers = HashMap<String, String>()
                headers["Access-Control-Allow-Origin"] = "*"
                headers["Cache-Control"] = "no-store"
                return WebResourceResponse(
                    mime,
                    null,
                    200,
                    "OK",
                    headers,
                    FileInputStream(file)
                )
            }

            override fun onPageFinished(view: WebView, url: String) {
                notifyWeb()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.fileCallback?.onReceiveValue(null)
                this@MainActivity.fileCallback = filePathCallback
                return try {
                    val intent = fileChooserParams?.createIntent()
                        ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                    filePicker.launch(intent)
                    true
                } catch (_: Exception) {
                    this@MainActivity.fileCallback = null
                    filePathCallback?.onReceiveValue(null)
                    Toast.makeText(this@MainActivity, "Unable to open file picker", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimeType)
                request.addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let {
                    request.addRequestHeader("Cookie", it)
                }
                request.setTitle(fileName)
                request.setDescription("Downloading from FlowState")
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                manager.enqueue(request)
                Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Download could not be started", Toast.LENGTH_LONG).show()
            }
        })

        handleShareIntent(intent)

        if (savedInstanceState == null) {
            webView.loadUrl(FLOWSTATE_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.scheme == "flowstate" && intent.host == "browse") {
            intent.getQueryParameter("url")?.let { openInAppBrowser(it) }
            return
        }
        handleShareIntent(intent)
    }

    /* ---------------- sharing recordings into FlowState ---------------- */

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) {
                    copySharedUri(uri, intent.type)
                } else {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (!text.isNullOrBlank()) addTextShare(text)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                list?.forEach { copySharedUri(it, intent.type) }
            }
        }
        notifyWeb()
    }

    /** Copies the shared stream into app storage so it survives the source app. */
    private fun copySharedUri(uri: Uri, fallbackMime: String?) {
        try {
            val mime = contentResolver.getType(uri) ?: fallbackMime ?: "application/octet-stream"
            val rawName = displayName(uri) ?: "shared-${System.currentTimeMillis()}"
            val safeName = rawName.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80)
            val stored = "${UUID.randomUUID()}-$safeName"
            val target = File(sharedDir(), stored)

            contentResolver.openInputStream(uri).use { input ->
                if (input == null) {
                    Toast.makeText(this, "Could not read the shared file", Toast.LENGTH_SHORT).show()
                    return
                }
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }

            val item = ImportedFile(
                id = UUID.randomUUID().toString(),
                name = rawName,
                mime = mime,
                size = target.length(),
                url = "https://$ASSET_HOST/shared/$stored",
                text = ""
            )
            synchronized(pendingImports) { pendingImports.add(item) }
            Toast.makeText(this, "Copied $rawName into FlowState", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Could not copy shared file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addTextShare(text: String) {
        val item = ImportedFile(
            id = UUID.randomUUID().toString(),
            name = "Shared transcript",
            mime = "text/plain",
            size = text.length.toLong(),
            url = "",
            text = text
        )
        synchronized(pendingImports) { pendingImports.add(item) }
    }

    private fun displayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    private fun notifyWeb() {
        if (!::webView.isInitialized) return
        webView.post {
            webView.evaluateJavascript(
                "if(typeof checkSharedImports==='function'){checkSharedImports()}",
                null
            )
        }
    }

    private inner class ImportBridge {
        @JavascriptInterface
        fun pendingImports(): String {
            val array = JSONArray()
            synchronized(pendingImports) {
                pendingImports.forEach { file ->
                    array.put(
                        JSONObject().apply {
                            put("id", file.id)
                            put("name", file.name)
                            put("mime", file.mime)
                            put("size", file.size)
                            put("url", file.url)
                            put("text", file.text)
                        }
                    )
                }
            }
            return array.toString()
        }

        @JavascriptInterface
        fun clearPending(id: String) {
            synchronized(pendingImports) {
                pendingImports.removeAll { it.id == id }
            }
        }
    }

    private fun sharedDir(): File = File(filesDir, "shared").apply { mkdirs() }

    private fun mimeFor(name: String): String = when {
        name.endsWith(".m4a", true) -> "audio/mp4"
        name.endsWith(".mp3", true) -> "audio/mpeg"
        name.endsWith(".wav", true) -> "audio/wav"
        name.endsWith(".ogg", true) -> "audio/ogg"
        name.endsWith(".txt", true) -> "text/plain"
        name.endsWith(".pdf", true) -> "application/pdf"
        name.endsWith(".json", true) -> "application/json"
        else -> "application/octet-stream"
    }

    /* ---------------- sandboxed in-app browser ---------------- */

    private fun openInAppBrowser(url: String) {
        val browser = WebView(this)
        browser.settings.javaScriptEnabled = true
        browser.settings.domStorageEnabled = true
        browser.settings.allowFileAccess = false
        browser.settings.allowContentAccess = false
        browser.settings.javaScriptCanOpenWindowsAutomatically = false
        browser.settings.setSupportMultipleWindows(false)
        browser.settings.mediaPlaybackRequiresUserGesture = true
        // No JavascriptInterface here on purpose: arbitrary sites get no
        // access to FlowState data or native features.
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val scheme = request.url.scheme ?: return true
                return scheme != "https" && scheme != "http"
            }
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fun control(label: String, action: () -> Unit) {
            val button = Button(this)
            button.text = label
            button.textSize = 13f
            button.setOnClickListener { action() }
            controls.addView(
                button,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        control("◀") { if (browser.canGoBack()) browser.goBack() }
        control("▶") { if (browser.canGoForward()) browser.goForward() }
        control("⟳") { browser.reload() }
        control("↗") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(browser.url ?: url)))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
            }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(controls, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(browser, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("FlowState browser")
            .setView(container)
            .setNegativeButton("Close", null)
            .setOnDismissListener { browser.destroy() }
            .create()

        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && browser.canGoBack()) {
                browser.goBack()
                true
            } else false
        }

        browser.loadUrl(url)
        dialog.show()
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val FLOWSTATE_URL =
            "https://cag0123abc-debug.github.io/FlowStateFinal/"
        private const val ASSET_HOST = "flowstate.local"

        private val trustedHosts = setOf(
            "cag0123abc-debug.github.io",
            "script.google.com",
            "script.googleusercontent.com"
        )
    }
}
