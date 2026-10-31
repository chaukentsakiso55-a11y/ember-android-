package com.ember.assistant

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
    private val requestOther = 4103
    private var pendingWebPermission: PermissionRequest? = null
    private var speechRecognizer: SpeechRecognizer? = null
    @Volatile private var speechWanted = false
    private var speechRestartPending = false

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

    private fun requestRuntime(permission: String) {
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            web.evaluateJavascript("window.dispatchEvent(new Event('ember-permission-refresh'))", null)
            return
        }
        ActivityCompat.requestPermissions(this, arrayOf(permission), requestOther)
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
            if (granted && speechWanted) startNativeSpeech()
        }
        if (requestCode == requestOther && ::web.isInitialized) {
            web.evaluateJavascript("window.dispatchEvent(new Event('ember-permission-refresh'))", null)
        }
        if (requestCode == requestNotifications && ::web.isInitialized) {
            web.evaluateJavascript("window.dispatchEvent(new Event('ember-permission-refresh'))", null)
        }
    }

    private fun openPermission(name: String) {
        val intent = when (name) {
            "microphone" -> { requestMicPermission(); return }
            "camera" -> { requestRuntime(Manifest.permission.CAMERA); return }
            "location" -> { requestRuntime(Manifest.permission.ACCESS_FINE_LOCATION); return }
            "contacts" -> { requestRuntime(Manifest.permission.READ_CONTACTS); return }
            "calendar" -> { requestRuntime(Manifest.permission.READ_CALENDAR); return }
            "notifications" -> {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestNotificationPermission(); return
                }
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            "notification_access" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            "modify_settings" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
            "usage" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            "battery" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            "app_info" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        try { startActivity(intent) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    }

    inner class EmberNativeBridge {
        @JavascriptInterface fun platform(): String = "android-kotlin"
        @JavascriptInterface fun requestMicrophone() { runOnUiThread { requestMicPermission() } }
        @JavascriptInterface fun startVoiceInput(): Boolean {
            speechWanted = true
            runOnUiThread {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requestMicPermission()
                else startNativeSpeech()
            }
            return true
        }
        @JavascriptInterface fun stopVoiceInput() {
            speechWanted = false
            runOnUiThread { stopNativeSpeech(destroy = false) }
        }
        @JavascriptInterface fun requestNotifications() { runOnUiThread { requestNotificationPermission() } }
        @JavascriptInterface fun openPermission(name: String) { runOnUiThread { openPermission(name) } }
        @JavascriptInterface fun permissionStatus(): String {
            val enabledNotif = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
            val enabledA11y = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
            val usage = try { ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED } catch (_: Exception) { false }
            val power = getSystemService(POWER_SERVICE) as PowerManager
            return JSONObject()
                .put("microphone", ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                .put("camera", ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                .put("location", ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                .put("contacts", ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
                .put("calendar", ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
                .put("notifications", Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                .put("notification_access", enabledNotif.contains(packageName + "/" + EmberNotificationListener::class.java.name, true))
                .put("accessibility", enabledA11y.contains(packageName + "/" + EmberAccessibilityService::class.java.name, true))
                .put("overlay", Settings.canDrawOverlays(this@MainActivity))
                .put("modify_settings", Settings.System.canWrite(this@MainActivity))
                .put("usage", usage)
                .put("battery", power.isIgnoringBatteryOptimizations(packageName))
                .put("service", getSharedPreferences("ember_runtime", MODE_PRIVATE).getBoolean("service_active", false))
                .toString()
        }
        @JavascriptInterface fun startAssistantService(): Boolean {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread { requestNotificationPermission() }
                return false
            }
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
        @JavascriptInterface fun act(actionJson: String): String {
            return try {
                val action = JSONObject(actionJson)
                EmberAccessibilityService.perform(action)
            } catch (e: Exception) { "ERROR: " + (e.message ?: "Invalid action") }
        }
        @JavascriptInterface fun saveScale(scale: Float) {
            getSharedPreferences("ember_display", MODE_PRIVATE).edit().putFloat("scale", scale.coerceIn(0.8f, 1.5f)).apply()
        }
        @JavascriptInterface fun getScale(): Float = getSharedPreferences("ember_display", MODE_PRIVATE).getFloat("scale", 1.0f)
    }

    private fun startNativeSpeech() {
        if (!speechWanted || isFinishing || isDestroyed) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMicPermission()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            web.evaluateJavascript("window.emberNativeSpeechError && window.emberNativeSpeechError('Speech recognition service unavailable')", null)
            return
        }
        try {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) { web.evaluateJavascript("window.emberNativeSpeechState && window.emberNativeSpeechState('LISTENING')", null) }
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit
                        override fun onError(error: Int) {
                            if (!speechWanted || isFinishing || isDestroyed) return
                            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                                speechWanted = false
                                web.evaluateJavascript("window.emberNativeSpeechError && window.emberNativeSpeechError('Microphone permission is required')", null)
                                return
                            }
                            scheduleSpeechRestart(700)
                        }
                        override fun onResults(results: Bundle?) {
                            val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                            if (spoken.isNotBlank()) web.evaluateJavascript("window.emberNativeSpeechResult && window.emberNativeSpeechResult(${JSONObject.quote(spoken)})", null)
                            scheduleSpeechRestart(350)
                        }
                        override fun onPartialResults(partialResults: Bundle?) = Unit
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    })
                }
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            web.evaluateJavascript("window.emberNativeSpeechError && window.emberNativeSpeechError(${JSONObject.quote(e.message ?: "Could not start microphone")})", null)
            scheduleSpeechRestart(1200)
        }
    }

    private fun scheduleSpeechRestart(delay: Long) {
        if (speechRestartPending) return
        speechRestartPending = true
        web.postDelayed({
            speechRestartPending = false
            if (speechWanted && !isFinishing && !isDestroyed) {
                try { speechRecognizer?.cancel() } catch (_: Exception) { }
                startNativeSpeech()
            }
        }, delay)
    }

    private fun stopNativeSpeech(destroy: Boolean) {
        speechRestartPending = false
        try { speechRecognizer?.cancel() } catch (_: Exception) { }
        if (destroy) {
            try { speechRecognizer?.destroy() } catch (_: Exception) { }
            speechRecognizer = null
        }
    }

    override fun onPause() {
        stopNativeSpeech(destroy = false)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.postDelayed({
            web.evaluateJavascript("window.dispatchEvent(new Event('ember-permission-refresh'))", null)
            if (speechWanted) startNativeSpeech()
        }, 250)
    }

    override fun onBackPressed() {
        if (::web.isInitialized) web.evaluateJavascript("document.querySelector('#ov')?.classList.remove('on'); document.querySelector('#drawer')?.classList.remove('on')", null)
        else super.onBackPressed()
    }

    override fun onDestroy() {
        speechWanted = false
        stopNativeSpeech(destroy = true)
        pendingWebPermission?.deny()
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
