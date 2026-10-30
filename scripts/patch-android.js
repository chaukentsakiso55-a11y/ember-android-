// Patch the generated Capacitor Android project with EMBER branding and safe shell defaults.
const fs = require("fs");
const path = require("path");

const main = path.join("android", "app", "src", "main");
const manifest = path.join(main, "AndroidManifest.xml");
if (!fs.existsSync(manifest)) throw new Error("AndroidManifest.xml was not generated");

let xml = fs.readFileSync(manifest, "utf8");
const permissions = [
  "android.permission.INTERNET",
  "android.permission.ACCESS_NETWORK_STATE",
  "android.permission.RECORD_AUDIO",
  "android.permission.MODIFY_AUDIO_SETTINGS",
  "android.permission.VIBRATE",
  "android.permission.WAKE_LOCK",
  "android.permission.POST_NOTIFICATIONS"
];
for (const permission of permissions) {
  if (!xml.includes(permission)) {
    xml = xml.replace("<application", `    <uses-permission android:name="${permission}" />\n    <application`);
  }
}
if (!xml.includes("android:screenOrientation")) {
  xml = xml.replace("<activity", '<activity android:screenOrientation="sensorLandscape"');
}
if (!xml.includes("android:usesCleartextTraffic")) {
  xml = xml.replace("<application", '<application android:usesCleartextTraffic="true"');
}
xml = xml.replace(/android:icon="[^"]+"/, 'android:icon="@mipmap/ic_launcher"');
if (xml.includes("android:roundIcon=")) {
  xml = xml.replace(/android:roundIcon="[^"]+"/, 'android:roundIcon="@mipmap/ic_launcher_round"');
}
if (!xml.includes("sensorLandscape")) throw new Error("Landscape orientation patch did not apply");
fs.writeFileSync(manifest, xml);

// EMBER adaptive launcher icon: a cyan energy core inside a precision-cut E/flame mark.
const res = path.join(main, "res");
const write = (relative, content) => {
  const target = path.join(res, relative);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, content);
};
const foreground = `<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#00D9FF" android:pathData="M54,12 C34,12 20,27 20,47 C20,69 35,88 54,96 C73,88 88,69 88,47 C88,27 74,12 54,12 Z"/>
    <path android:fillColor="#03111F" android:pathData="M54,20 C39,20 28,31 28,47 C28,63 38,77 54,85 C70,77 80,63 80,47 C80,31 69,20 54,20 Z"/>
    <path android:fillColor="#00D9FF" android:pathData="M66,34 L43,34 L43,44 L62,44 L62,51 L43,51 L43,61 L67,61 L67,72 L32,72 L32,24 L66,24 Z"/>
    <path android:fillColor="#FF8A35" android:pathData="M72,17 L76,27 L86,31 L76,35 L72,45 L68,35 L58,31 L68,27 Z"/>
    <path android:fillColor="#FFFFFF" android:pathData="M54,40 L58,48 L66,52 L58,56 L54,64 L50,56 L42,52 L50,48 Z"/>
</vector>`;
const adaptive = `<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ember_icon_background"/>
    <foreground android:drawable="@drawable/ember_icon_foreground"/>
</adaptive-icon>`;
const legacy = `<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#03111F" android:pathData="M0,0h108v108h-108z"/>
    <path android:fillColor="#00D9FF" android:pathData="M54,10 C30,10 14,28 14,52 C14,75 32,94 54,100 C76,94 94,75 94,52 C94,28 78,10 54,10 Z"/>
    <path android:fillColor="#03111F" android:pathData="M54,19 C35,19 23,33 23,52 C23,70 36,84 54,91 C72,84 85,70 85,52 C85,33 73,19 54,19 Z"/>
    <path android:fillColor="#00D9FF" android:pathData="M70,34 L42,34 L42,45 L64,45 L64,53 L42,53 L42,64 L71,64 L71,76 L29,76 L29,23 L70,23 Z"/>
    <path android:fillColor="#FF8A35" android:pathData="M76,16 L80,26 L90,30 L80,34 L76,44 L72,34 L62,30 L72,26 Z"/>
</vector>`;
write("drawable/ember_icon_foreground.xml", foreground);
write("color/ember_icon_background.xml", '<resources><color name="ember_icon_background">#030B16</color></resources>');
write("mipmap-anydpi-v26/ic_launcher.xml", adaptive);
write("mipmap-anydpi-v26/ic_launcher_round.xml", adaptive);
write("mipmap-anydpi/ic_launcher.xml", legacy);
write("mipmap-anydpi/ic_launcher_round.xml", legacy);

const javaDir = path.join(main, "java", "com", "ember", "assistant");
fs.mkdirSync(javaDir, { recursive: true });
fs.writeFileSync(path.join(javaDir, "MainActivity.java"), `package com.ember.assistant;

import android.os.Bundle;
import android.view.WindowManager;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersiveMode();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersiveMode();
    }

    private void applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        );
    }
}
`);
console.log("EMBER branding patched: adaptive launcher icon + immersive Android shell.");
