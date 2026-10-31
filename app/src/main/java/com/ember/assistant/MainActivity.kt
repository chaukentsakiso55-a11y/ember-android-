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
import androidx.compose.ui.graphics.toArgb
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

private val EmberBg = Color(0xFF00060A)
private val EmberPanel = Color(0xFF010D14)
private val EmberLine = Color(0xFF0D3347)
private val EmberCyan = Color(0xFF00D4FF)
private val EmberAmber = Color(0xFFFF6B00)
private val EmberText = Color(0xFFD8F8FF)
private val EmberMuted = Color(0xFF3A8A9A)

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

    Column(Modifier.fillMaxSize().background(EmberBg)) {
        Row(
            Modifier.fillMaxWidth().height(42.dp).background(Color(0xFF000D14))
                .border(width = 1.dp, color = Color(0xFF1A5C7A)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("EMBER", Modifier.padding(start = 12.dp), color = EmberMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            HudButton("⚙") { keyDraft = apiKey; showSettings = true }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("EMBER", color = EmberCyan, fontSize = 19.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text("A FRIENDLY ASSISTANT", color = EmberMuted, fontSize = 8.sp, letterSpacing = 1.sp)
            }
            Column(Modifier.padding(end = 12.dp), horizontalAlignment = Alignment.End) {
                Text(if (busy) "THINKING" else "ONLINE", color = if (busy) EmberAmber else Color(0xFF00FF88), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(if (micGranted) "MIC READY" else "MIC LOCKED", color = EmberMuted, fontSize = 8.sp)
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.weight(.17f).fillMaxHeight().background(Color(0xFF000D14))
                    .border(width = 1.dp, color = EmberLine).padding(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text("◈ SYS MONITOR", color = EmberCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Divider(color = EmberLine)
                MetricBar("CPU", 22, EmberCyan)
                MetricBar("MEM", 48, Color(0xFFFFCC00))
                MetricBar("NET", 8, Color(0xFF00FF88))
                MetricBar("GPU", 14, EmberAmber)
                MetricBar("TMP", 41, Color(0xFFFF6688))
                Spacer(Modifier.height(3.dp))
                HudPanel("SYSTEM") {
                    Text("UP  ACTIVE", color = Color(0xFF00FF88), fontSize = 9.sp)
                    Text("OS  ANDROID", color = Color(0xFFFFCC00), fontSize = 9.sp)
                    Text("CORE  ONLINE", color = EmberCyan, fontSize = 9.sp)
                }
                Spacer(Modifier.weight(1f))
                StatusTag("AI CORE", "ACTIVE", Color(0xFF00FF88))
                StatusTag("SECURITY", "CLEARED", EmberCyan)
                StatusTag("PROTOCOL", "EMBER", EmberMuted)
            }
            Box(Modifier.weight(.48f).fillMaxHeight().background(EmberBg)) {
                NativeFace(if (busy) "THINKING" else "LISTENING", false)
            }
            Column(
                Modifier.weight(.35f).fillMaxHeight().background(Color(0xFF000D14))
                    .border(width = 1.dp, color = EmberLine).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("▸ ACTIVITY LOG", color = Color(0xFF5AB8CC), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().background(EmberPanel)
                        .border(1.dp, EmberLine).padding(7.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (chat.isEmpty()) item { Text("EMBER SYSTEM INITIALISED\nAwaiting command input...", color = EmberMuted, fontSize = 10.sp) }
                    items(chat) { line ->
                        Column {
                            Text(if (line.fromUser) "YOU:" else "EMBER:", color = if (line.fromUser) EmberText else EmberCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(line.text, color = EmberText, fontSize = 10.sp)
                        }
                    }
                    if (error.isNotBlank()) item { Text("ERR: $error", color = Color(0xFFFF3355), fontSize = 9.sp) }
                }
                Divider(color = EmberLine)
                Text("▸ FILE UPLOAD", color = Color(0xFF5AB8CC), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Box(
                    Modifier.fillMaxWidth().height(55.dp).background(Color(0xFF010F18))
                        .border(1.dp, EmberLine).clickable { status = "FILE PICKER NOT YET CONNECTED" },
                    contentAlignment = Alignment.Center
                ) {
                    Text("＋  DROP OR SELECT FILE", color = EmberMuted, fontSize = 9.sp)
                }
                Text("No file loaded", color = EmberMuted, fontSize = 8.sp)
                Divider(color = EmberLine)
                Text("▸ COMMAND INPUT", color = Color(0xFF5AB8CC), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = input, onValueChange = { input = it },
                        modifier = Modifier.weight(1f).background(Color(0xFF000D14))
                            .border(1.dp, EmberLine).padding(8.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(color = EmberText, fontSize = 10.sp),
                        decorationBox = { inner -> if (input.isEmpty()) Text("Type a command...", color = EmberMuted, fontSize = 9.sp); inner() }
                    )
                    HudButton("▸", enabled = !busy && input.isNotBlank()) {
                        val prompt = input.trim()
                        input = ""
                        sendNativeMessage(prompt, apiKey, chat, scope, speak,
                            onBusy = { busy = it }, onStatus = { status = it }, onError = { error = it })
                    }
                    HudButton("MIC", enabled = !busy) { if (micGranted) launchVoice() else requestMic() }
                }
                HudButton("✋  INTERRUPT  [ESC]") { status = "INTERRUPT REQUESTED" }
                HudButton(if (micGranted) "🎙  MICROPHONE ACTIVE" else "MICROPHONE LOCKED") { if (micGranted) launchVoice() else requestMic() }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(22.dp).background(Color(0xFF000D14))
                .border(width = 1.dp, color = EmberLine).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("[F4] Mute  ·  [F11] Fullscreen", color = Color(0xFF5AB8CC), fontSize = 8.sp)
            Spacer(Modifier.weight(1f))
            Text("Cyber Pulse  •  DarthWolf", color = EmberMuted, fontSize = 8.sp)
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
private fun NativeFace(state: String, muted: Boolean) {
    val transition = rememberInfiniteTransition(label = "ember-hologram")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3600), RepeatMode.Restart), label = "scan"
    )
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h * .43f
        val unit = minOf(w, h)
        val faceW = unit * .25f
        val faceH = unit * .34f
        val glow = if (muted) Color(0xFFFF3355) else if (state == "THINKING") EmberAmber else EmberCyan
        for (x in 0..(w / 30f).toInt()) for (y in 0..(h / 30f).toInt()) {
            drawCircle(EmberCyan.copy(alpha = .12f), 1.dp.toPx(), Offset(x * 30f, y * 30f))
        }
        for (i in 0..4) {
            drawCircle(glow.copy(alpha = .10f - i * .014f), unit * (.29f + i * .045f), Offset(cx, cy),
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
        drawCircle(Color.Transparent, unit * .28f, Offset(cx, cy),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5f, 8f))))
        val left = cx - faceW
        val right = cx + faceW
        val top = cy - faceH
        val bottom = cy + faceH
        val outline = androidx.compose.ui.graphics.Path().apply {
            moveTo(cx, top)
            cubicTo(left + faceW * .2f, top, left - faceW * .05f, cy - faceH * .1f, left + faceW * .18f, cy + faceH * .55f)
            cubicTo(left + faceW * .35f, bottom, right - faceW * .35f, bottom, right - faceW * .18f, cy + faceH * .55f)
            cubicTo(right + faceW * .05f, cy - faceH * .1f, right - faceW * .2f, top, cx, top)
            close()
        }
        drawPath(outline, Color(0xFF001520).copy(alpha = .8f))
        drawPath(outline, glow.copy(alpha = .9f), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        // Faceted hologram mesh
        val rows = 12
        for (i in 1 until rows) {
            val y = top + (bottom - top) * i / rows
            val half = faceW * (if (i < 3) .45f + i * .12f else if (i > 9) .88f - (i - 9) * .15f else 1f)
            drawLine(glow.copy(alpha = .22f), Offset(cx - half, y), Offset(cx + half, y), 1.dp.toPx())
        }
        for (i in -5..5) {
            val x = cx + i * faceW / 5f
            drawLine(glow.copy(alpha = .16f), Offset(cx + (x-cx)*.15f, top + faceH*.12f), Offset(x, bottom - faceH*.06f), 1.dp.toPx())
        }
        val eyeY = cy - faceH * .12f
        drawLine(glow, Offset(cx - faceW*.62f, eyeY), Offset(cx - faceW*.12f, eyeY - faceH*.015f), 3.dp.toPx(), cap = StrokeCap.Round)
        drawLine(glow, Offset(cx + faceW*.12f, eyeY - faceH*.015f), Offset(cx + faceW*.62f, eyeY), 3.dp.toPx(), cap = StrokeCap.Round)
        drawLine(glow.copy(alpha=.8f), Offset(cx, eyeY + faceH*.05f), Offset(cx-faceW*.08f, cy+faceH*.25f), 1.dp.toPx())
        drawLine(glow.copy(alpha=.8f), Offset(cx-faceW*.08f, cy+faceH*.25f), Offset(cx+faceW*.08f, cy+faceH*.25f), 1.dp.toPx())
        drawLine(glow, Offset(cx-faceW*.25f, cy+faceH*.48f), Offset(cx+faceW*.25f, cy+faceH*.48f), 2.dp.toPx(), cap = StrokeCap.Round)
        val scanY = top + (bottom-top)*phase
        drawLine(EmberCyan.copy(alpha=.75f), Offset(left-faceW*.1f, scanY), Offset(right+faceW*.1f, scanY), 1.dp.toPx())
        val label = if (state == "THINKING") "◈  THINKING" else "●  LISTENING"
        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint().apply {
                color = glow.toArgb()
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = 13.sp.toPx()
                typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
            }
            drawText(label, cx, bottom + unit*.11f, paint)
        }
        val waveY = bottom + unit*.16f
        for (i in 0 until 36) {
            val env = 1f - kotlin.math.abs(i - 17.5f) / 18f
            val amp = (3f + 12f * env * kotlin.math.abs(kotlin.math.sin(phase * 6.28f + i*.7f))).coerceAtLeast(2f)
            drawLine(glow.copy(alpha=.75f), Offset(cx - 72.dp.toPx() + i*4.dp.toPx(), waveY),
                Offset(cx - 72.dp.toPx() + i*4.dp.toPx(), waveY - amp), 2.dp.toPx())
        }
    }
}


@Composable
private fun MetricBar(name: String, value: Int, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, color = EmberMuted, fontSize = 9.sp)
            Text("$value%", color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFF011520))) {
            Box(Modifier.fillMaxWidth(value / 100f).fillMaxHeight().background(color))
        }
    }
}

@Composable
private fun StatusTag(title: String, value: String, color: Color) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF010F18)).border(1.dp, EmberLine).padding(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = EmberMuted, fontSize = 8.sp)
        Text(value, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
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
