package com.example.photos

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private val permissionRequestCode = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.addJavascriptInterface(GalleryBridge(), "AndroidGallery")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url ?: return null
                if (url.scheme != "photos") return null
                if (url.host != "media") return response(404, "Not Found", "text/plain", ByteArray(0))
                return try {
                    val id = url.lastPathSegment?.toLongOrNull()
                        ?: return response(400, "Bad Request", "text/plain", ByteArray(0))
                    val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
                    val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                    val stream = contentResolver.openInputStream(uri)
                        ?: return response(404, "Not Found", "text/plain", ByteArray(0))
                    WebResourceResponse(mime, null, stream).apply {
                        responseHeaders = mapOf(
                            "Access-Control-Allow-Origin" to "*",
                            "Cache-Control" to "private, max-age=3600"
                        )
                    }
                } catch (_: Exception) {
                    response(404, "Not Found", "text/plain", ByteArray(0))
                }
            }
        }
        webView.loadUrl("file:///android_asset/index.html")
        requestMediaPermissionIfNeeded()
    }

    private fun requestMediaPermissionIfNeeded() {
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), permissionRequestCode)
        else webView.evaluateJavascript("window.refreshGallery && window.refreshGallery()", null)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) {
            webView.evaluateJavascript("window.refreshGallery && window.refreshGallery()", null)
        }
    }

    private fun response(status: Int, reason: String, mime: String, bytes: ByteArray) =
        WebResourceResponse(mime, "UTF-8", status, reason, mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(bytes))

    inner class GalleryBridge {
        @JavascriptInterface
        fun hasMediaPermission(): Boolean {
            val imagePermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            val videoPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
            return checkSelfPermission(imagePermission) == PackageManager.PERMISSION_GRANTED ||
                   checkSelfPermission(videoPermission) == PackageManager.PERMISSION_GRANTED
        }

        @JavascriptInterface
        fun getMedia(): String {
            val result = JSONArray()
            if (!hasMediaPermission()) return result.toString()

            val collection = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Files.FileColumns.MEDIA_TYPE
            )
            val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
            val args = arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
            )
            val sort = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
            var cursor: Cursor? = null
            try {
                cursor = contentResolver.query(collection, projection, selection, args, sort)
                cursor?.use {
                    val idCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val nameCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val mimeCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                    val dateCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                    val typeCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                    while (it.moveToNext()) {
                        val id = it.getLong(idCol)
                        val mime = it.getString(mimeCol) ?: ""
                        val item = JSONObject()
                        item.put("id", id)
                        item.put("name", it.getString(nameCol) ?: "Untitled")
                        item.put("mime", mime)
                        item.put("dateAdded", it.getLong(dateCol))
                        item.put("kind", if (it.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) "video" else "image")
                        item.put("url", "photos://media/$id")
                        result.put(item)
                    }
                }
            } catch (_: Exception) {
                // Return any successfully collected results rather than crashing the WebView.
            } finally {
                cursor?.close()
            }
            return result.toString()
        }

        @JavascriptInterface
        fun requestPermission() {
            runOnUiThread { requestMediaPermissionIfNeeded() }
        }
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("AndroidGallery")
        webView.destroy()
        super.onDestroy()
    }
}
