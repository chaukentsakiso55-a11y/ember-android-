package com.ember.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private val EmberBg = Color(0xFF070B12)
private val EmberPanel = Color(0xFF101A27)
private val EmberLine = Color(0xFF214052)
private val EmberCyan = Color(0xFF55E7F2)
private val EmberAmber = Color(0xFFFFB84D)
private val EmberText = Color(0xFFE5F3F7)
private val EmberMuted = Color(0xFF7E9BA8)

data class ChatLine(val fromUser: Boolean, val text: String)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        tts = TextToSpeech(this, this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                background = EmberBg, surface = EmberPanel, primary = EmberCyan,
                onBackground = EmberText, onSurface = EmberText
            )) {
                EmberApp(
                    readKey = { loadKey() },
                    saveKey = { saveKey(it) },
                    speak = { say(it) },
                    requestMic = { requestMicPermission() },
                    launchVoice = { launchVoiceInput() },
                    permissionStatus = { microphoneGranted() }
                )
            }
        }
    }

    private fun encryptedPrefs() = EncryptedSharedPreferences.create(
        this,
        "ember_secure",
        MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private fun loadKey(): String = runCatching { encryptedPrefs().getString("gemini_key", "").orEmpty() }.getOrDefault("")
    private fun saveKey(value: String) { encryptedPrefs().edit().putString("gemini_key", value.trim()).apply() }

    private fun say(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ember_reply")
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts?.language = Locale.getDefault()
    }

    private fun microphoneGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestMicPermission() {
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 4101)
    }

    private fun launchVoiceInput() {
        if (!microphoneGranted()) {
            requestMicPermission()
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { startActivityForResult(intent, 4102) }
    }

    @Deprecated("Legacy callback used for Android speech recognizer result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 4102 && resultCode == RESULT_OK) {
            val phrase = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (phrase.isNotBlank()) {
                // The native Compose screen receives voice input through its activity event.
                VoiceInputBus.lastPhrase = phrase
                VoiceInputBus.revision++
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

private object VoiceInputBus {
    @Volatile var lastPhrase: String = ""
    @Volatile var revision: Int = 0
}

@Composable
private fun EmberApp(
    readKey: () -> String,
    saveKey: (String) -> Unit,
    speak: (String) -> Unit,
    requestMic: () -> Unit,
    launchVoice: () -> Unit,
    permissionStatus: () -> Boolean
) {
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf(readKey()) }
    var showSettings by remember { mutableStateOf(false) }
    var keyDraft by remember { mutableStateOf(apiKey) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("SYSTEM READY") }
    var error by remember { mutableStateOf("") }
    var micGranted by remember { mutableStateOf(permissionStatus()) }
    val chat = remember { mutableStateListOf<ChatLine>() }
    var voiceRevision by remember { mutableIntStateOf(VoiceInputBus.revision) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(250)
            micGranted = permissionStatus()
            if (VoiceInputBus.revision != voiceRevision) {
                voiceRevision = VoiceInputBus.revision
                input = VoiceInputBus.lastPhrase
                if (input.isNotBlank()) sendNativeMessage(input, apiKey, chat, scope, speak,
                    onBusy = { busy = it }, onStatus = { status = it }, onError = { error = it })
            }
        }
    }

    Row(Modifier.fillMaxSize().background(EmberBg).padding(12.dp)) {
        Column(Modifier.weight(0.22f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HudPanel("SYSTEM MONITOR") {
                Metric("CPU", "—")
                Metric("MEM", "—")
                Metric("NET", "ONLINE")
                Metric("GPU", "N/A")
                Metric("TMP", "—")
            }
            HudPanel("SYSTEM LOG") {
                Text(status, color = EmberCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (error.isNotBlank()) Text(error, color = EmberAmber, fontSize = 10.sp)
            }
            Spacer(Modifier.weight(1f))
            HudButton("SETTINGS") { keyDraft = apiKey; showSettings = true }
        }

        Column(Modifier.weight(0.56f).fillMaxHeight().padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("EMBER", color = EmberCyan, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 5.sp)
                Text("CYBER PULSE  /  NATIVE ANDROID", color = EmberMuted, fontSize = 9.sp)
                Text(if (busy) "THINKING" else "ONLINE", color = if (busy) EmberAmber else EmberCyan, fontSize = 10.sp)
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(0.48f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                NativeFace()
            }
            Text("EMBER CORE", color = EmberCyan, fontSize = 13.sp, letterSpacing = 4.sp, fontWeight = FontWeight.Bold)
            Text(if (busy) "PROCESSING REQUEST" else "READY FOR INPUT", color = EmberMuted, fontSize = 9.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(8.dp))
            HudPanel("CONVERSATION") {
                LazyColumn(Modifier.fillMaxWidth().heightIn(min = 70.dp, max = 170.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(chat) { line ->
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (line.fromUser) Alignment.End else Alignment.Start) {
                            Text(if (line.fromUser) "YOU" else "EMBER", color = if (line.fromUser) EmberAmber else EmberCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(line.text, color = EmberText, fontSize = 12.sp)
                        }
                    }
                    if (chat.isEmpty()) item { Text("Your conversation will appear here.", color = EmberMuted, fontSize = 11.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicTextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f).background(EmberPanel, RoundedCornerShape(12.dp)).border(1.dp, EmberLine, RoundedCornerShape(12.dp)).padding(12.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(color = EmberText, fontSize = 13.sp),
                    decorationBox = { inner -> if (input.isEmpty()) Text("Enter a command for Ember...", color = EmberMuted, fontSize = 12.sp); inner() }
                )
                HudButton("SEND", enabled = !busy && input.isNotBlank()) {
                    val prompt = input.trim()
                    input = ""
                    sendNativeMessage(prompt, apiKey, chat, scope, speak,
                        onBusy = { busy = it }, onStatus = { status = it }, onError = { error = it })
                }
                HudButton("MIC", enabled = !busy) {
                    if (micGranted) launchVoice() else requestMic()
                }
            }
        }

        Column(Modifier.weight(0.22f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HudPanel("QUICK ACTIONS") {
                HudButton("VOICE OUTPUT") { if (chat.isNotEmpty()) speak(chat.last().text) }
                HudButton("INTERRUPT") { status = "VOICE STOP REQUESTED" }
                HudButton("CLEAR CHAT") { chat.clear() }
                HudButton("PERMISSIONS") { keyDraft = apiKey; showSettings = true }
            }
            HudPanel("SESSION") {
                Metric("AI", "GEMINI")
                Metric("VOICE", if (micGranted) "READY" else "LOCKED")
                Metric("MODE", "NATIVE")
            }
            Spacer(Modifier.weight(1f))
            Text("DarthWolf  •  Cyber Pulse", color = EmberMuted, fontSize = 9.sp)
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            containerColor = EmberPanel,
            title = { Text("EMBER SETTINGS", color = EmberCyan) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Gemini API key", color = EmberMuted, fontSize = 12.sp)
                    OutlinedTextField(
                        value = keyDraft, onValueChange = { keyDraft = it },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        label = { Text("Google AI Studio key") }
                    )
                    Text("The key is stored using Android encrypted preferences.", color = EmberMuted, fontSize = 10.sp)
                    Text("Microphone: " + if (micGranted) "Granted" else "Not granted", color = EmberText, fontSize = 12.sp)
                    TextButton(onClick = requestMic) { Text("REQUEST MICROPHONE") }
                    Text("Accessibility must be enabled manually in Android Settings.", color = EmberMuted, fontSize = 10.sp)
                    TextButton(onClick = {
                        // Android's accessibility settings are opened by a native system intent.
                    }) { Text("ACCESSIBILITY SETUP IS IN ANDROID SETTINGS") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    apiKey = keyDraft.trim()
                    saveKey(apiKey)
                    showSettings = false
                }) { Text("SAVE", color = EmberCyan) }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("CANCEL") } }
        )
    }
}

@Composable
private fun NativeFace() {
    val transition = rememberInfiniteTransition(label = "ember-face")
    val pulse by transition.animateFloat(
        initialValue = 0.82f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "pulse"
    )
    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = minOf(size.width, size.height) * .34f * pulse
        for (i in 0..3) {
            drawCircle(EmberCyan.copy(alpha = .10f - i * .015f), radius = radius + i * 16f,
                center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f))
        }
        drawCircle(EmberCyan.copy(alpha = .07f), radius = radius * .78f, center = Offset(cx, cy))
        val faceW = radius * .86f
        val faceH = radius * 1.13f
        drawOval(EmberPanel, topLeft = Offset(cx - faceW, cy - faceH), size = androidx.compose.ui.geometry.Size(faceW * 2, faceH * 2))
        drawOval(EmberCyan.copy(alpha = .65f), topLeft = Offset(cx - faceW, cy - faceH), size = androidx.compose.ui.geometry.Size(faceW * 2, faceH * 2),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
        val eyeY = cy - radius * .18f
        drawLine(EmberCyan, Offset(cx - radius * .53f, eyeY), Offset(cx - radius * .13f, eyeY), 4f, cap = StrokeCap.Round)
        drawLine(EmberCyan, Offset(cx + radius * .13f, eyeY), Offset(cx + radius * .53f, eyeY), 4f, cap = StrokeCap.Round)
        drawLine(EmberAmber.copy(alpha = .8f), Offset(cx - radius * .25f, cy + radius * .42f), Offset(cx + radius * .25f, cy + radius * .42f), 2f, cap = StrokeCap.Round)
        for (i in 0..12) {
            val y = cy - radius * .75f + i * radius * .115f
            drawLine(EmberCyan.copy(alpha = .13f), Offset(cx - faceW * .8f, y), Offset(cx + faceW * .8f, y), 1f)
        }
    }
}

@Composable
private fun HudPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(EmberPanel.copy(alpha = .92f), RoundedCornerShape(12.dp))
        .border(1.dp, EmberLine, RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = EmberCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        content()
    }
}

@Composable
private fun Metric(name: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, color = EmberMuted, fontSize = 10.sp)
        Text(value, color = EmberText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HudButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.background(if (enabled) Color(0xFF122432) else Color(0xFF151B21), RoundedCornerShape(9.dp))
        .border(1.dp, if (enabled) EmberLine else EmberLine.copy(alpha = .4f), RoundedCornerShape(9.dp))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 11.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) EmberCyan else EmberMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp)
    }
}

private fun sendNativeMessage(
    prompt: String,
    key: String,
    chat: MutableList<ChatLine>,
    scope: kotlinx.coroutines.CoroutineScope,
    speak: (String) -> Unit,
    onBusy: (Boolean) -> Unit,
    onStatus: (String) -> Unit,
    onError: (String) -> Unit
) {
    if (key.isBlank()) {
        onError("Add your Gemini API key in Settings first.")
        onStatus("API KEY REQUIRED")
        return
    }
    chat.add(ChatLine(true, prompt))
    onBusy(true)
    onError("")
    onStatus("CONNECTING TO GEMINI")
    scope.launch {
        try {
            val reply = withContext(Dispatchers.IO) { GeminiClient.generate(key, prompt) }
            chat.add(ChatLine(false, reply))
            onStatus("RESPONSE RECEIVED")
            speak(reply)
        } catch (e: Exception) {
            val message = e.message ?: "Gemini request failed"
            chat.add(ChatLine(false, "I couldn't complete that request. $message"))
            onError(message)
            onStatus("REQUEST FAILED")
        } finally {
            onBusy(false)
        }
    }
}

private object GeminiClient {
    fun generate(apiKey: String, prompt: String): String {
        val models = listOf("gemini-2.5-flash", "gemini-2.0-flash")
        var lastError = "No Gemini model responded."
        for (model in models) {
            val connection = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey").openConnection() as HttpURLConnection)
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 15000
                connection.readTimeout = 45000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code in 200..299) {
                    val root = JSONObject(raw)
                    val candidates = root.optJSONArray("candidates")
                    val parts = candidates?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    val answer = parts?.optJSONObject(0)?.optString("text").orEmpty()
                    if (answer.isNotBlank()) return answer
                    lastError = "Gemini returned an empty response."
                } else {
                    val detail = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull()
                    lastError = "Gemini HTTP $code: ${detail ?: raw.take(240)}"
                    if (code != 404 && code != 429 && code < 500) break
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException(lastError)
    }
}
