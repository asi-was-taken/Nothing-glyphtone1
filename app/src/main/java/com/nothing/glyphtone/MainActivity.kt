package com.nothing.glyphtone

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.Window
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Establish full-screen mode and an obsidian black canvas to banish unsightly white flashes
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.decorView.setBackgroundColor(android.graphics.Color.BLACK)

        val webView = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    return !(url.startsWith("file:///android_asset/") || url.startsWith("blob:") || url.startsWith("data:"))
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
        fun downloadBase64Data(base64Data: String, fileName: String, mimeType: String) {
            try {
                val cleanBase64 = base64Data.substringAfter(",")
                val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                val safeName = fileName.substringAfterLast('/').substringAfterLast('\\')
                    .replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "glyphtone-export" }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    val downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?: throw IllegalStateException("Export directory unavailable")
                    downloads.mkdirs()
                    FileOutputStream(File(downloads, safeName)).use { it.write(decodedBytes) }
                    return
                }
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                try {
                    resolver.openOutputStream(uri)?.use { it.write(decodedBytes) }
                        ?: throw IllegalStateException("Could not open export destination")
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
