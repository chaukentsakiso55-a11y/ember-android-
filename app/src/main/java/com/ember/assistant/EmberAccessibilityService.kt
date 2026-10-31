package com.ember.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/** User-enabled accessibility bridge. Actions are requested by the user and confirmed in EMBER. */
class EmberAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var active: EmberAccessibilityService? = null
        fun perform(action: JSONObject): String {
            val service = active ?: return "Enable EMBER Accessibility in Android settings first."
            return service.performAction(action)
        }
    }

    override fun onServiceConnected() { super.onServiceConnected(); active = this }
    override fun onDestroy() { if (active === this) active = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    private fun performAction(a: JSONObject): String = when (a.optString("type")) {
        "back" -> if (performGlobalAction(GLOBAL_ACTION_BACK)) "Back pressed" else "Back unavailable"
        "home" -> if (performGlobalAction(GLOBAL_ACTION_HOME)) "Home opened" else "Home unavailable"
        "open_app" -> openApp(a.optString("name"))
        "tap" -> tapText(a.optString("text"))
        "type" -> typeText(a.optString("text"))
        "swipe" -> swipe(a.optString("dir", "up"))
        else -> "Unsupported action. Use open_app, back, home, tap, type or swipe."
    }

    private fun openApp(raw: String): String {
        val key = raw.trim().lowercase()
        val packages = mapOf(
            "whatsapp" to "com.whatsapp", "chrome" to "com.android.chrome",
            "youtube" to "com.google.android.youtube", "settings" to "com.android.settings",
            "phone" to "com.google.android.dialer", "messages" to "com.google.android.apps.messaging",
            "gmail" to "com.google.android.gm", "files" to "com.google.android.documentsui"
        )
        val pkg = packages[key] ?: if (key.matches(Regex("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+"))) key
            else return "App not in EMBER's supported launch list."
        val launch = packageManager.getLaunchIntentForPackage(pkg)
            ?: return "App is not installed or Android does not expose its launch activity."
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        return "Opened $raw"
    }

    private fun tapText(text: String): String {
        val root = rootInActiveWindow ?: return "No active screen. Open the target app first."
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            var candidate: AccessibilityNodeInfo? = node
            while (candidate != null) {
                if (candidate.isVisibleToUser && candidate.isEnabled && candidate.isClickable) {
                    return if (candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) "Tapped $text" else "Android rejected the tap."
                }
                candidate = candidate.parent
            }
        }
        return "Could not find a clickable item matching: $text"
    }

    private fun typeText(text: String): String {
        val root = rootInActiveWindow ?: return "No active screen."
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return "Focus a text field first, then confirm the type action."
        if (!node.isEditable) return "Focused item is not an editable text field."
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "Text entered" else "Could not enter text."
    }

    private fun swipe(direction: String): String {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val x = w * .5f
        val points = when (direction.lowercase()) {
            "down" -> floatArrayOf(x, h*.3f, x, h*.8f)
            "left" -> floatArrayOf(w*.8f, h*.5f, w*.2f, h*.5f)
            "right" -> floatArrayOf(w*.2f, h*.5f, w*.8f, h*.5f)
            else -> floatArrayOf(x, h*.8f, x, h*.3f)
        }
        val path = Path().apply { moveTo(points[0], points[1]); lineTo(points[2], points[3]) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 350)).build()
        return if (dispatchGesture(gesture, null, null)) "Swipe requested" else "Android rejected the swipe."
    }
}