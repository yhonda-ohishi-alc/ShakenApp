package org.ippoan.shaken

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ippoan.shaken.nfc.CardType
import org.ippoan.shaken.nfc.NfcBridgeServer
import org.ippoan.shaken.nfc.NfcReader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val BASE_URL = "https://nuxt-pwa-carins.mtamaramu.com"
        private const val FILE_CHOOSER_REQUEST_CODE = 1001
    }

    private lateinit var webView: WebView
    private var nfcAdapter: NfcAdapter? = null
    private val nfcReader = NfcReader()
    private var nfcBridgeServer: NfcBridgeServer? = null

    // 共有 Intent で受信したファイル
    private val sharedFiles = mutableListOf<SharedFileInfo>()

    // File chooser callback
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    data class SharedFileInfo(
        val name: String,
        val mimeType: String,
        val base64Data: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        setupWebView()
        setupNfc()
        handleIntent(intent)

        if (sharedFiles.isEmpty()) {
            webView.loadUrl(BASE_URL)
        }
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // Google OAuth 対応: WebView マーカーを除去
            userAgentString = userAgentString.replace("; wv", "")
        }

        // Cookie 永続化
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.addJavascriptInterface(AndroidBridge(), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                // 外部リンク（tel:, mailto: 等）はシステムに委譲
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 共有ファイルがある場合、ページ読み込み完了後に通知
                if (sharedFiles.isNotEmpty()) {
                    webView.evaluateJavascript(
                        "window.dispatchEvent(new CustomEvent('shared-files-received', " +
                        "{ detail: { count: ${sharedFiles.size} } }))",
                        null
                    )
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: return false
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE)
                } catch (e: Exception) {
                    fileUploadCallback = null
                    return false
                }
                return true
            }
        }
    }

    private fun setupNfc() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        nfcBridgeServer = NfcBridgeServer().apply {
            isReuseAddr = true
            connectionLostTimeout = 5
            try {
                start()
                Log.i(TAG, "NFC Bridge WebSocket server started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start NFC bridge server", e)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        if (NfcAdapter.ACTION_TECH_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TAG_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action
        ) {
            @Suppress("DEPRECATION")
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
            handleNfcTag(tag)
        }

        // 共有 Intent の場合
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            handleShareIntent(intent)
        }
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> handleShareIntent(intent)
        }
    }

    private val ALLOWED_MIME_TYPES = setOf(
        "application/pdf",
        "application/json",
        "text/json"
    )

    private fun handleShareIntent(intent: Intent) {
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                extractSharedFiles(intent)
            }
            // PDF/JSON 以外のファイルを除外（MIME type + 拡張子で判定）
            val filtered = files.filter { file ->
                file.mimeType in ALLOWED_MIME_TYPES ||
                file.name.endsWith(".pdf", ignoreCase = true) ||
                file.name.endsWith(".json", ignoreCase = true)
            }
            if (filtered.isNotEmpty()) {
                sharedFiles.clear()
                sharedFiles.addAll(filtered)
                Log.i(TAG, "Received ${filtered.size} shared files (${files.size - filtered.size} rejected)")
                webView.loadUrl(BASE_URL)
            } else if (files.isNotEmpty()) {
                Toast.makeText(this@MainActivity, "PDF/JSON ファイルのみ対応しています", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun extractSharedFiles(intent: Intent): List<SharedFileInfo> {
        val result = mutableListOf<SharedFileInfo>()
        val mimeType = intent.type ?: "application/octet-stream"

        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return result
                readFileFromUri(uri, mimeType)?.let { result.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: return result
                for (uri in uris) {
                    readFileFromUri(uri, mimeType)?.let { result.add(it) }
                }
            }
        }
        return result
    }

    private fun readFileFromUri(uri: Uri, fallbackMimeType: String): SharedFileInfo? {
        return try {
            val fileName = getFileName(uri) ?: "file"
            // ContentResolver から正確な MIME type を取得、取れなければファイル名から推定
            val resolvedMimeType = contentResolver.getType(uri)
                ?: when {
                    fileName.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
                    fileName.endsWith(".json", ignoreCase = true) -> "application/json"
                    else -> fallbackMimeType
                }
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val bytes = inputStream.readBytes()
            inputStream.close()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            SharedFileInfo(
                name = fileName,
                mimeType = resolvedMimeType,
                base64Data = base64
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read shared file", e)
            null
        }
    }

    private fun getFileName(uri: Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null) ?: return null
        return cursor.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) it.getString(nameIndex) else null
            } else null
        }
    }

    private fun handleNfcTag(tag: Tag) {
        lifecycleScope.launch {
            val cardData = withContext(Dispatchers.IO) {
                nfcReader.readCard(tag)
            }

            Log.i(TAG, "NFC card read: type=${cardData.cardType}, id=${cardData.cardId}")

            val server = nfcBridgeServer ?: return@launch

            when (cardData.cardType) {
                CardType.DRIVER_LICENSE -> {
                    server.broadcastLicenseRead(cardData)
                }
                else -> {
                    server.broadcastNfcRead(cardData.cardId)
                }
            }

            Toast.makeText(
                this@MainActivity,
                "NFC: カード読み取り完了",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun enableNfcForegroundDispatch() {
        val adapter = nfcAdapter ?: return
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )
        adapter.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    private fun disableNfcForegroundDispatch() {
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onResume() {
        super.onResume()
        enableNfcForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        disableNfcForegroundDispatch()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            nfcBridgeServer?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop NFC bridge server", e)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            val results = if (resultCode == RESULT_OK && data != null) {
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            } else null
            fileUploadCallback?.onReceiveValue(results)
            fileUploadCallback = null
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    inner class AndroidBridge {

        @JavascriptInterface
        fun getAppVersion(): String {
            return try {
                val pInfo = packageManager.getPackageInfo(packageName, 0)
                pInfo.versionName ?: "unknown"
            } catch (e: Exception) {
                "unknown"
            }
        }

        @JavascriptInterface
        fun isNfcAvailable(): Boolean {
            return nfcAdapter?.isEnabled == true
        }

        @JavascriptInterface
        fun getSharedFiles(): String {
            val jsonArray = JSONArray()
            for (file in sharedFiles) {
                jsonArray.put(JSONObject().apply {
                    put("name", file.name)
                    put("mimeType", file.mimeType)
                    put("base64Data", file.base64Data)
                })
            }
            return jsonArray.toString()
        }

        @JavascriptInterface
        fun clearSharedFiles() {
            sharedFiles.clear()
        }
    }
}
