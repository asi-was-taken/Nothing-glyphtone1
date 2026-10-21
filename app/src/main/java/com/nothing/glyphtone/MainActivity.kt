package com.nothing.glyphtone

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
                    if (url.startsWith("blob:") || url.startsWith("data:")) {
                        return false
                    }
                    return false
                }
            }

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            // Register the bridge to receive file data streams from the web app
            addJavascriptInterface(BlobDownloadBridge(this@MainActivity), "BlobDownloadBridge")
        }

        setContentView(webView)

        // Load thy local application interface from the assets vault
        webView.loadUrl("file:///android_asset/index.html")
    }

    // The bridge responsible for capturing base64 streams and committing them to the Downloads repository
    class BlobDownloadBridge(private val context: Context) {
        @JavascriptInterface
        fun downloadBase64Data(base64Data: String, fileName: String, mimeType: String) {
            try {
                val cleanBase64 = if (base64Data.contains(",")) {
                    base64Data.substringAfter(",")
                } else {
                    base64Data
                }
                val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                
                val targetFile = File(downloadsDir, fileName)
                val outputStream = FileOutputStream(targetFile)
                outputStream.write(decodedBytes)
                outputStream.close()

                // Register the achievement within the system DownloadManager for user notifications
                val request = DownloadManager.Request(Uri.fromFile(targetFile)).apply {
                    setTitle(fileName)
                    setDescription("Thy Glyphtone export hath been successfully saved.")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    setAllowedOverMetered(true)
                    setAllowedOverRoaming(true)
                }
                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                downloadManager.enqueue(request)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
