package com.ember.assistant

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val requestMic = 4101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                runOnUiThread {
                    val audio = request.resources.contains(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    if (audio && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(arrayOf(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else if (audio) {
                        requestMicPermission()
                    } else request.deny()
                }
            }
        }
        web.addJavascriptInterface(EmberNativeBridge(), "EmberNative")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    private fun requestMicPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), requestMic)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestMic && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            web.evaluateJavascript("window.dispatchEvent(new Event('ember-mic-permission-granted'))", null)
        }
    }

    inner class EmberNativeBridge {
        @JavascriptInterface fun platform(): String = "android-kotlin"
        @JavascriptInterface fun requestMicrophone() { runOnUiThread { requestMicPermission() } }
        @JavascriptInterface fun saveScale(scale: Float) {
            getSharedPreferences("ember_display", MODE_PRIVATE).edit().putFloat("scale", scale.coerceIn(0.8f, 1.5f)).apply()
        }
        @JavascriptInterface fun getScale(): Float = getSharedPreferences("ember_display", MODE_PRIVATE).getFloat("scale", 1.0f)
    }

    override fun onBackPressed() {
        if (::web.isInitialized) web.evaluateJavascript("document.querySelector('#ov')?.classList.remove('on'); document.querySelector('#drawer')?.classList.remove('on')", null)
        else super.onBackPressed()
    }

    override fun onDestroy() { if (::web.isInitialized) web.destroy(); super.onDestroy() }
}
