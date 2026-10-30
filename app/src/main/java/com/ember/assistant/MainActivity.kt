package com.ember.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val requestMic = 4101
    private val requestNotifications = 4102
    private var pendingWebPermission: PermissionRequest? = null

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
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val audio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    if (audio && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else if (audio) {
                        pendingWebPermission?.deny()
                        pendingWebPermission = request
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

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestNotifications
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestMic) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            val request = pendingWebPermission
            pendingWebPermission = null
            if (granted && request != null) request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            else request?.deny()
            if (::web.isInitialized) web.evaluateJavascript("window.dispatchEvent(new Event('ember-mic-permission-result'))", null)
        }
        if (requestCode == requestNotifications && ::web.isInitialized) {
            web.evaluateJavascript("window.dispatchEvent(new Event('ember-permission-refresh'))", null)
        }
    }

    private fun openPermission(name: String) {
        val intent = when (name) {
            "microphone" -> { requestMicPermission(); return }
            "notifications" -> {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestNotificationPermission(); return
                }
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }
            "notification_access" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            "modify_settings" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
            "usage" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            "battery" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            "app_info" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            "camera", "location", "contacts", "calendar" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        try { startActivity(intent) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    }

    inner class EmberNativeBridge {
        @JavascriptInterface fun platform(): String = "android-kotlin"
        @JavascriptInterface fun requestMicrophone() { runOnUiThread { requestMicPermission() } }
        @JavascriptInterface fun requestNotifications() { runOnUiThread { requestNotificationPermission() } }
        @JavascriptInterface fun openPermission(name: String) { runOnUiThread { openPermission(name) } }
        @JavascriptInterface fun startAssistantService(): Boolean {
            return try {
                val i = Intent(this@MainActivity, EmberKeepAliveService::class.java).setAction(EmberKeepAliveService.ACTION_START)
                ContextCompat.startForegroundService(this@MainActivity, i)
                true
            } catch (_: Exception) { false }
        }
        @JavascriptInterface fun stopAssistantService() {
            runOnUiThread {
                stopService(Intent(this@MainActivity, EmberKeepAliveService::class.java))
                web.evaluateJavascript("window.dispatchEvent(new Event('ember-service-stopped'))", null)
            }
        }
        @JavascriptInterface fun saveScale(scale: Float) {
            getSharedPreferences("ember_display", MODE_PRIVATE).edit().putFloat("scale", scale.coerceIn(0.8f, 1.5f)).apply()
        }
        @JavascriptInterface fun getScale(): Float = getSharedPreferences("ember_display", MODE_PRIVATE).getFloat("scale", 1.0f)
    }

    override fun onBackPressed() {
        if (::web.isInitialized) web.evaluateJavascript("document.querySelector('#ov')?.classList.remove('on'); document.querySelector('#drawer')?.classList.remove('on')", null)
        else super.onBackPressed()
    }

    override fun onDestroy() {
        pendingWebPermission?.deny()
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
