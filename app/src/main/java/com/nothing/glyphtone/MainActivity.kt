package com.nothing.glyphtone

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.webkit.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val clipData = result.data?.clipData
            if (clipData != null) {
                val uris = Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                fileUploadCallback?.onReceiveValue(uris)
            } else {
                val uri = result.data?.data
                fileUploadCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
            }
        } else {
            fileUploadCallback?.onReceiveValue(null)
        }
        fileUploadCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        
        // Set WebView and window background to black to eliminate white borders/flashes
        webView.setBackgroundColor(Color.BLACK)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        setContentView(webView)

        setupWebView()
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        // Expose JavaScript bridge to handle local blob exports
        webView.addJavascriptInterface(BlobDownloadBridge(this), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
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

                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "audio/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }

                try {
                    filePickerLauncher.launch(Intent.createChooser(intent, "Select Audio File"))
                } catch (e: Exception) {
                    fileUploadCallback = null
                    Toast.makeText(this@MainActivity, "Cannot open file chooser", Toast.LENGTH_SHORT).show()
                    return false
                }
                return true
            }
        }

        // Handle Download requests (Blob URLs, Data URIs, and standard HTTP/HTTPS)
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                if (url.startsWith("data:")) {
                    saveDataUriToDownloads(url, mimeType)
                } else if (url.startsWith("blob:")) {
                    // Fetch blob contents via JavaScript and pass to AndroidBridge
                    val js = """
                        (async function() {
                            try {
                                const response = await fetch('$url');
                                const blob = await response.blob();
                                const reader = new FileReader();
                                reader.onload = function() {
                                    AndroidBridge.saveBase64File(reader.result, '$mimeType');
                                };
                                reader.readAsDataURL(blob);
                            } catch (err) {
                                console.error('Blob fetch failed: ', err);
                            }
                        })();
                    """.trimIndent()
                    webView.evaluateJavascript(js, null)
                    Toast.makeText(this, "Processing export...", Toast.LENGTH_SHORT).show()
                } else {
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    val request = DownloadManager.Request(Uri.parse(url)).apply {
                        setMimeType(mimeType)
                        val cookies = CookieManager.getInstance().getCookie(url)
                        addRequestHeader("cookie", cookies)
                        addRequestHeader("User-Agent", userAgent)
                        setDescription("Downloading Glyphtone export...")
                        setTitle(fileName)
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    }

                    val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    downloadManager.enqueue(request)
                    Toast.makeText(this, "Downloading $fileName to Downloads...", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    class BlobDownloadBridge(private val activity: MainActivity) {
        @JavascriptInterface
        fun saveBase64File(dataUri: String, mimeType: String) {
            activity.runOnUiThread {
                activity.saveDataUriToDownloads(dataUri, mimeType)
            }
        }
    }

    fun saveDataUriToDownloads(dataUri: String, mimeType: String) {
        try {
            val extension = when {
                mimeType.contains("csv") || dataUri.contains("text/csv") -> ".csv"
                mimeType.contains("wav") || dataUri.contains("audio/wav") -> ".wav"
                mimeType.contains("ogg") || dataUri.contains("audio/ogg") -> ".ogg"
                else -> ".bin"
            }
            val fileName = "glyphtone_export_${System.currentTimeMillis()}$extension"
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, fileName)

            val base64Data = dataUri.substringAfter(",")
            val fileBytes = Base64.decode(base64Data, Base64.DEFAULT)

            FileOutputStream(file).use { fos ->
                fos.write(fileBytes)
            }

            Toast.makeText(this, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to save file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
