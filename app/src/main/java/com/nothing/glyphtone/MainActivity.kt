package com.nothing.glyphtone

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    private var uploadCallback: ValueCallback<Array<Uri>>? = null
    private val audioPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uploadCallback?.onReceiveValue(uri?.let { arrayOf(it) })
        uploadCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        window.decorView.setBackgroundColor(android.graphics.Color.BLACK)

        val webView = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    return !(url.startsWith("file:///android_asset/") || url.startsWith("blob:") || url.startsWith("data:"))
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    uploadCallback?.onReceiveValue(null)
                    uploadCallback = filePathCallback
                    audioPicker.launch("audio/*")
                    return true
                }
            }

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }

            // Register the bridge to receive file data streams from the web app
            addJavascriptInterface(BlobDownloadBridge(this@MainActivity), "BlobDownloadBridge")
        }

        setContentView(webView)

        // Load thy local application interface from the assets vault
        webView.loadUrl("file:///android_asset/index.html")
    }

    // Save exports through MediaStore so this works with Android's scoped storage.
    class BlobDownloadBridge(private val context: Context) {
        @JavascriptInterface
        fun downloadBase64Data(base64Data: String, fileName: String, mimeType: String): Boolean = try {
                val cleanBase64 = base64Data.substringAfter(",")
                val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                val safeName = fileName.substringAfterLast('/').substringAfterLast('\\')
                    .replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "glyphtone-export" }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    val downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?: throw IllegalStateException("Export directory unavailable")
                    downloads.mkdirs()
                    FileOutputStream(File(downloads, safeName)).use { it.write(decodedBytes) }
                } else {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("Could not create export destination")
                    try {
                        resolver.openOutputStream(uri)?.use { it.write(decodedBytes) }
                            ?: throw IllegalStateException("Could not open export destination")
                    } catch (e: Exception) {
                        resolver.delete(uri, null, null)
                        throw e
                    }
                }
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
    }
}
