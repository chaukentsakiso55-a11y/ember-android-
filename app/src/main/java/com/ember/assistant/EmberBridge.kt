package com.ember.assistant

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.speech.tts.TextToSpeech
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import android.os.Process
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

class EmberBridge(private val context: Context, private val webView: WebView) {
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy {
        val master = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context, "ember_secure", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    @Volatile private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
        }
    }

    @JavascriptInterface fun saveSecret(json: String): String = try {
        prefs.edit().putString("gemini_keys", json).apply()
        "OK"
    } catch (e: Exception) { "ERROR" }

    @JavascriptInterface fun loadSecret(): String =
        prefs.getString("gemini_keys", "{\"keys\":[]}") ?: "{\"keys\":[]}"

    @JavascriptInterface fun gemini(id: String, bodyJson: String, key: String, model: String): String = try {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/" + java.net.URLEncoder.encode(model, "UTF-8") + ":generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply { requestMethod = "POST"; connectTimeout = 15000; readTimeout = 45000; doOutput = true; setRequestProperty("Content-Type", "application/json"); setRequestProperty("x-goog-api-key", key) }
        conn.outputStream.use { it.write(bodyJson.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        conn.disconnect()
        JSONObject().put("status", code).put("body", body).toString()
    } catch (e: Exception) { JSONObject().put("status", 0).put("body", JSONObject().put("error", JSONObject().put("message", e.message ?: "Network error")).toString()).toString() }

    @JavascriptInterface fun listModels(key: String, pageToken: String): String = try {
        val tokenQuery = if (pageToken.isBlank()) "" else "&pageToken=" + java.net.URLEncoder.encode(pageToken, "UTF-8")
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000" + tokenQuery)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("x-goog-api-key", key)
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        conn.disconnect()
        JSONObject().put("status", code).put("body", body).toString()
    } catch (e: Exception) {
        JSONObject().put("status", 0).put("body",
            JSONObject().put("error", JSONObject().put("message", e.message ?: "Network error")).toString()
        ).toString()
    }

    @JavascriptInterface fun act(actionJson: String): String {
        val a = JSONObject(actionJson); val type = a.optString("type")
        return try {
            when (type) {
                "open_app" -> openApp(a.optString("name"))
                "set_alarm" -> setAlarm(a.optString("time"), a.optString("label", "EMBER reminder"))
                "call" -> { val i = Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + a.optString("to"))); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(i); record(type, "dialler opened"); "DIALER_OPENED" }
                "send_message" -> { val i = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:" + a.optString("to"))); i.putExtra("sms_body", a.optString("text")); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(i); record(type, "message composer opened"); "COMPOSER_OPENED" }
                else -> { record(type, "unsupported on this build"); "UNSUPPORTED" }
            }
        } catch (e: Exception) { record(type, "failed"); "ERROR" }
    }

    @JavascriptInterface fun saveScale(value: Float) {
        context.getSharedPreferences("ember_ui", Context.MODE_PRIVATE).edit().putFloat("scale", value.coerceIn(.8f, 1.5f)).apply()
    }

    @JavascriptInterface fun getScale(): Float =
        context.getSharedPreferences("ember_ui", Context.MODE_PRIVATE).getFloat("scale", 1f)

    @JavascriptInterface fun speak(text: String, voice: String = "") {
        val safe = text.take(5000)
        tts?.speak(safe, TextToSpeech.QUEUE_FLUSH, null, "ember-" + System.currentTimeMillis())
        record("speak", "spoken")
    }

    @JavascriptInterface fun stopSpeak() { tts?.stop() }

    private var lastRx = android.net.TrafficStats.getUidRxBytes(Process.myUid())
    private var lastTx = android.net.TrafficStats.getUidTxBytes(Process.myUid())
    private var lastMetricAt = SystemClock.elapsedRealtime()
    private var lastCpuMs = Process.getElapsedCpuTime()

    @JavascriptInterface fun metrics(): String {
        val battery = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val temp = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1) / 10.0
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val mem = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mem)
        val used = max(0L, mem.totalMem - mem.availMem)
        val memPct = if (mem.totalMem > 0) (used * 100 / mem.totalMem).toInt() else 0
        val now = SystemClock.elapsedRealtime()
        val rx = android.net.TrafficStats.getUidRxBytes(Process.myUid()); val tx = android.net.TrafficStats.getUidTxBytes(Process.myUid())
        val seconds = max(1L, (now - lastMetricAt) / 1000L)
        val kb = if (rx >= 0 && tx >= 0 && lastRx >= 0 && lastTx >= 0) ((rx-lastRx+tx-lastTx).coerceAtLeast(0) / seconds / 1024).toInt() else 0
        lastRx = rx; lastTx = tx; lastMetricAt = now
        val cpuNow = Process.getElapsedCpuTime()
        val elapsed = max(1L, now - (now - seconds * 1000L))
        val cpu = (((cpuNow - lastCpuMs).coerceAtLeast(0L).toDouble() / elapsed.toDouble()) * 100.0 /
            Runtime.getRuntime().availableProcessors().coerceAtLeast(1)).toInt().coerceIn(0, 100)
        lastCpuMs = cpuNow
        val up = now / 1000
        return JSONObject().put("bat", pct).put("charging", charging).put("tmp", temp)
            .put("mem", memPct).put("net", if (kb > 0) 1 else 0).put("netKb", kb)
            .put("cpu", cpu).put("up", up).put("gpu", JSONObject.NULL).toString()
    }

    @JavascriptInterface fun setAlarm(time: String, label: String): String {
        return try {
            val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label.take(120))
                .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
            val parts = time.split(":")
            if (parts.size >= 2) {
                intent.putExtra(android.provider.AlarmClock.EXTRA_HOUR, parts[0].toInt())
                intent.putExtra(android.provider.AlarmClock.EXTRA_MINUTES, parts[1].toInt())
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            record("set_alarm", "alarm screen opened")
            "OPENED"
        } catch (e: Exception) { record("set_alarm", "failed"); "ERROR" }
    }

    @JavascriptInterface fun openApp(label: String): String {
        val pm = context.packageManager
        val matches = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledApplications(android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }
        val app = matches.firstOrNull { runCatching { pm.getApplicationLabel(it).toString().equals(label, true) }.getOrDefault(false) }
        val launch = app?.let { pm.getLaunchIntentForPackage(it.packageName) }
        return if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(launch)
            record("open_app", "opened " + label.take(80)); "OPENED"
        } else { record("open_app", "not found"); "NOT_FOUND" }
    }

    @JavascriptInterface fun logAction(action: String, result: String) { record(action.take(80), result.take(240)) }

    @JavascriptInterface fun getActionLog(): String =
        context.getSharedPreferences("ember_log", Context.MODE_PRIVATE).getString("items", "[]") ?: "[]"

    private fun record(action: String, result: String) {
        val p = context.getSharedPreferences("ember_log", Context.MODE_PRIVATE)
        val list = org.json.JSONArray(p.getString("items", "[]"))
        list.put(JSONObject().put("time", System.currentTimeMillis()).put("action", action).put("result", result))
        while (list.length() > 500) list.remove(0)
        p.edit().putString("items", list.toString()).apply()
    }

    fun close() { tts?.stop(); tts?.shutdown(); tts = null; io.shutdown() }
}
